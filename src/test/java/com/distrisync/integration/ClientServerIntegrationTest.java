package com.distrisync.integration;

import com.distrisync.client.CanvasUpdateListener;
import com.distrisync.client.NetworkClient;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import com.distrisync.server.NioServer;
import com.distrisync.server.RoomManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toUnmodifiableMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test that exercises the full network stack:
 * {@link NioServer} ↔ four concurrent {@link NetworkClient} instances.
 *
 * <h2>Lifecycle per test</h2>
 * <ol>
 *   <li>{@code @BeforeEach} — spins up {@link NioServer} on an OS-assigned
 *       ephemeral port (port 0), connects four clients, and waits for every
 *       client's initial {@code SNAPSHOT} to arrive.</li>
 *   <li>Test body — client 1 sends a {@code MUTATION}; Awaitility asserts
 *       clients 2–4 receive it within 2 s; full state equality is checked.</li>
 *   <li>{@code @AfterEach} — closes all four clients, calls
 *       {@link NioServer#stop()}, and joins the server thread to prevent port
 *       exhaustion between repeated test runs.</li>
 * </ol>
 */
class ClientServerIntegrationTest {

    private static final String HOST            = "127.0.0.1";
    private static final int    SETUP_TIMEOUT_S = 5;
    private static final int    BCAST_TIMEOUT_S = 2;
    private static final long   POLL_MS         = 50;

    // =========================================================================
    // Server-side fixtures
    // =========================================================================

    private RoomManager roomManager;
    private NioServer   server;
    private Thread      serverThread;
    private int         serverPort;

    // =========================================================================
    // Client-side fixtures
    // =========================================================================

    private NetworkClient client1;
    private NetworkClient client2;
    private NetworkClient client3;
    private NetworkClient client4;

    private TestListener listener1;
    private TestListener listener2;
    private TestListener listener3;
    private TestListener listener4;

    // =========================================================================
    // Set-up
    // =========================================================================

    @BeforeEach
    void setUp() throws Exception {
        // --- Server -----------------------------------------------------------
        roomManager = new RoomManager();
        server      = new NioServer(0 /* ephemeral */, roomManager);

        serverThread = new Thread(server, "test-nio-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // Block until the OS has assigned a port so we have something to connect to.
        serverPort = server.getBoundPortFuture().get(SETUP_TIMEOUT_S, TimeUnit.SECONDS);

        // --- Clients ----------------------------------------------------------
        listener1 = new TestListener("client-1");
        listener2 = new TestListener("client-2");
        listener3 = new TestListener("client-3");
        listener4 = new TestListener("client-4");

        client1 = buildAndConnect(listener1);
        client2 = buildAndConnect(listener2);
        client3 = buildAndConnect(listener3);
        client4 = buildAndConnect(listener4);

        // Wait for all four initial (empty) SNAPSHOT frames to land before the
        // test body starts issuing mutations.
        await("all clients receive initial SNAPSHOT")
                .atMost(SETUP_TIMEOUT_S, TimeUnit.SECONDS)
                .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                .until(() -> listener1.snapshotReceived.get()
                        && listener2.snapshotReceived.get()
                        && listener3.snapshotReceived.get()
                        && listener4.snapshotReceived.get());
    }

    private NetworkClient buildAndConnect(TestListener listener) throws Exception {
        NetworkClient client = new NetworkClient(HOST, serverPort);
        client.addListener(listener);
        client.connect();
        client.sendJoinRoom("Global");
        return client;
    }

    // =========================================================================
    // Tear-down
    // =========================================================================

    @AfterEach
    void tearDown() throws InterruptedException {
        // Close clients first so the server sees clean EOFs, not resets.
        for (NetworkClient client : List.of(client1, client2, client3, client4)) {
            if (client != null) {
                try { client.close(); } catch (Exception ignored) {}
            }
        }

        // Signal the NIO event loop to exit via wakeup() + stopped flag.
        if (server != null) server.stop();

        // Belt-and-suspenders: interrupt in case stop() raced with select().
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread.join(3_000);
        }
    }

    // =========================================================================
    // Test
    // =========================================================================

    /**
     * Verifies the core broadcast invariant:
     * <ul>
     *   <li>Client 1 sends a {@code MUTATION} containing a {@link Line}.</li>
     *   <li>The server applies it and broadcasts it to clients 2, 3, and 4.</li>
     *   <li>Within 2 seconds all peer listeners fire.</li>
     *   <li>The canvas state held by every party (server + all 4 clients) is
     *       identical — one shape, all fields round-trip equal to the original.</li>
     * </ul>
     */
    @Test
    void mutationFromClient1IsBroadcastToPeersAndAllStatesConverge() {
        // ---- Given: a Line shape produced locally by client 1 ----------------
        Line line = Line.create("#E63946", 10.0, 20.0, 250.0, 180.0, 3.0);

        // ---- When: client 1 publishes the mutation ---------------------------
        client1.sendMutation(line);

        // ---- Then: clients 2, 3, 4 receive the MUTATION within 2 s ----------
        await("MUTATION broadcast to peer clients")
                .atMost(BCAST_TIMEOUT_S, TimeUnit.SECONDS)
                .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                .until(() ->
                        listener2.state.containsKey(line.objectId())
                        && listener3.state.containsKey(line.objectId())
                        && listener4.state.containsKey(line.objectId()));

        // =====================================================================
        // Assert: server state
        // =====================================================================

        // All test clients join the default "Global" room (NetworkClient default).
        List<Shape> serverSnapshot = roomManager.getRoomSnapshot("Global");

        assertThat(serverSnapshot)
                .as("server must hold exactly the one mutated shape")
                .hasSize(1);

        Map<UUID, Shape> serverState = toStateMap(serverSnapshot);

        assertThat(serverState.get(line.objectId()))
                .as("server-stored shape must round-trip equal to the original Line")
                .isEqualTo(line);

        // =====================================================================
        // Assert: clients 2, 3, 4 — state populated entirely via MUTATION frames
        // =====================================================================

        assertClientMatchesServer("client-2", listener2.state, serverState);
        assertClientMatchesServer("client-3", listener3.state, serverState);
        assertClientMatchesServer("client-4", listener4.state, serverState);

        // =====================================================================
        // Assert: client 1 — the server does NOT echo mutations back to the
        // sender, so the listener's state is empty.  The full logical state is
        // obtained by merging the listener state with the shape client 1 produced
        // locally (which is what a real UI would already have on-screen).
        // =====================================================================

        Map<UUID, Shape> client1LogicalState = new HashMap<>(listener1.state);
        client1LogicalState.put(line.objectId(), line);

        assertClientMatchesServer("client-1 (logical)", client1LogicalState, serverState);
    }

    // =========================================================================
    // Assertion helpers
    // =========================================================================

    /** Converts a snapshot list to a {@code objectId → shape} map. */
    private static Map<UUID, Shape> toStateMap(List<Shape> shapes) {
        return shapes.stream().collect(toUnmodifiableMap(Shape::objectId, s -> s));
    }

    /**
     * Asserts that a client's accumulated state map has the same entries as the
     * server's authoritative state map, with per-entry value equality.
     */
    private static void assertClientMatchesServer(
            String clientLabel,
            Map<UUID, Shape> clientState,
            Map<UUID, Shape> serverState) {

        assertThat(clientState)
                .as("%s: state size must equal server state size", clientLabel)
                .hasSameSizeAs(serverState);

        serverState.forEach((id, serverShape) ->
                assertThat(clientState.get(id))
                        .as("%s: shape %s must equal server shape", clientLabel, id)
                        .isEqualTo(serverShape));
    }

    // =========================================================================
    // Test listener
    // =========================================================================

    /**
     * Thread-safe {@link CanvasUpdateListener} that accumulates canvas state.
     *
     * <ul>
     *   <li>{@link #snapshotReceived} — set to {@code true} on the first
     *       {@code SNAPSHOT}, used by {@code @BeforeEach} to confirm all four
     *       clients are fully connected before the test body runs.</li>
     *   <li>{@link #state} — a running canvas state map keyed by
     *       {@link Shape#objectId()}, updated by both SNAPSHOT and MUTATION
     *       events.  MUTATION events follow last-writer-wins semantics.</li>
     *   <li>{@link #mutationsReceived} — append-only ordered list of every
     *       MUTATION shape received; useful for failure diagnosis.</li>
     * </ul>
     */
    static final class TestListener implements CanvasUpdateListener {

        final String name;

        /** Flipped to {@code true} when the first SNAPSHOT frame arrives. */
        final AtomicBoolean snapshotReceived = new AtomicBoolean(false);

        /**
         * Accumulated canvas state: shapes keyed by objectId.
         * Written from the {@code distrisync-read} thread; read from the
         * test thread — must be thread-safe.
         */
        final ConcurrentHashMap<UUID, Shape> state = new ConcurrentHashMap<>();

        /** Ordered record of every MUTATION received; for diagnostic output. */
        final CopyOnWriteArrayList<Shape> mutationsReceived = new CopyOnWriteArrayList<>();

        TestListener(String name) {
            this.name = name;
        }

        @Override
        public void onSnapshotReceived(List<Shape> shapes) {
            // A reconnect re-sends the SNAPSHOT; clear stale state first.
            state.clear();
            shapes.forEach(s -> state.put(s.objectId(), s));
            snapshotReceived.set(true);
        }

        @Override
        public void onMutationReceived(Shape shape) {
            // Last-writer-wins: newer timestamp replaces an older entry.
            state.merge(shape.objectId(), shape, (existing, incoming) ->
                    incoming.timestamp() > existing.timestamp() ? incoming : existing);
            mutationsReceived.add(shape);
        }

        @Override
        public String toString() {
            return "TestListener[" + name + ", snapshot=" + snapshotReceived.get()
                    + ", mutations=" + mutationsReceived.size() + "]";
        }
    }
}
