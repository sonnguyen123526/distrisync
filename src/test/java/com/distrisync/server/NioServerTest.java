package com.distrisync.server;

import com.distrisync.client.CanvasUpdateListener;
import com.distrisync.client.NetworkClient;
import com.distrisync.model.Circle;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * Integration-style tests for {@link NioServer} multi-board routing: scoped broadcast and
 * {@code SWITCH_BOARD} snapshot delivery.
 */
class NioServerTest {

    private static final String HOST = "127.0.0.1";
    private static final int SETUP_TIMEOUT_S = 5;
    private static final int BCAST_TIMEOUT_S = 2;
    private static final long POLL_MS = 50;

    private RoomManager roomManager;
    private NioServer server;
    private Thread serverThread;
    private int serverPort;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (server != null) {
            server.stop();
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread.join(3_000);
        }
    }

    private void startServer() throws Exception {
        roomManager = new RoomManager();
        server = new NioServer(0, roomManager);
        serverThread = new Thread(server, "nio-server-test");
        serverThread.setDaemon(true);
        serverThread.start();
        serverPort = server.getBoundPortFuture().get(SETUP_TIMEOUT_S, TimeUnit.SECONDS);
    }

    private static ClientSession findSession(RoomManager rm, String roomId, String clientId) {
        for (SelectionKey key : rm.getActiveClientKeys(roomId)) {
            if (key.attachment() instanceof ClientSession s && clientId.equals(s.clientId)) {
                return s;
            }
        }
        return null;
    }

    private static ByteBuffer tokenUtf8(String token) {
        byte[] raw = token.getBytes(StandardCharsets.UTF_8);
        assertThat(raw.length)
                .as("test tokens must be exactly 36 UTF-8 bytes (standard UUID string)")
                .isEqualTo(36);
        return ByteBuffer.wrap(raw);
    }

    /**
     * After {@code JOIN_ROOM}, the server issues a {@code udpToken}, stores it in
     * {@link NioServer#udpTokenRegistryForTests()}, and resolves it to the owning {@link ClientSession}.
     */
    @Test
    @DisplayName("JOIN_ROOM issues udpToken and registers it in udpTokenToSession")
    void testUdpTokenAdmission() throws Exception {
        startServer();

        var listener = new MutationTrackingListener("A");
        NetworkClient clientA = new NetworkClient(HOST, serverPort, "UserA", "client-a");

        clientA.addListener(listener);

        try {
            clientA.connect();
            clientA.sendJoinRoom("Room-1", "Board-1");

            await("JOIN_ROOM processed and UDP token issued")
                    .atMost(SETUP_TIMEOUT_S, TimeUnit.SECONDS)
                    .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        ClientSession s = findSession(roomManager, "Room-1", "client-a");
                        return s != null
                                && s.udpToken != null
                                && s.udpToken.length() == 36
                                && !server.udpTokenRegistryForTests().isEmpty();
                    });

            ClientSession session = findSession(roomManager, "Room-1", "client-a");
            assertThat(session).isNotNull();
            assertThat(session.udpToken).hasSize(36);

            ConcurrentHashMap<String, ClientSession> registry = server.udpTokenRegistryForTests();
            assertThat(registry).containsKey(session.udpToken);
            assertThat(registry.get(session.udpToken)).isSameAs(session);
            assertThat(registry).hasSize(1);
        } finally {
            clientA.close();
        }
    }

    /**
     * A 36-byte UDP datagram carrying a valid admission token registers the sender's
     * {@link InetSocketAddress} on the corresponding {@link ClientSession}.
     */
    @Test
    @DisplayName("36-byte UDP token packet registers ClientSession.udpEndpoint to sender address")
    void testUdpEndpointRegistration() throws Exception {
        startServer();

        var listener = new MutationTrackingListener("A");
        NetworkClient clientA = new NetworkClient(HOST, serverPort, "UserA", "client-a");
        clientA.addListener(listener);

        try (DatagramChannel udpClient = DatagramChannel.open()) {
            udpClient.configureBlocking(false);
            // Bind to IPv4 loopback so the server's recorded InetSocketAddress matches getLocalAddress().
            udpClient.bind(new InetSocketAddress(HOST, 0));

            clientA.connect();
            clientA.sendJoinRoom("Room-1", "Board-1");

            await("session has UDP token after join")
                    .atMost(SETUP_TIMEOUT_S, TimeUnit.SECONDS)
                    .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        ClientSession s = findSession(roomManager, "Room-1", "client-a");
                        return s != null && s.udpToken != null && s.udpToken.length() == 36;
                    });

            ClientSession session = findSession(roomManager, "Room-1", "client-a");
            InetSocketAddress localBefore = (InetSocketAddress) udpClient.getLocalAddress();

            ByteBuffer reg = tokenUtf8(session.udpToken);
            udpClient.send(reg, new InetSocketAddress(HOST, serverPort));

            await("UDP registration updates session.udpEndpoint")
                    .atMost(SETUP_TIMEOUT_S, TimeUnit.SECONDS)
                    .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                    .until(() -> session.udpEndpoint != null);

            assertThat(session.udpEndpoint).isEqualTo(localBefore);
        } finally {
            clientA.close();
        }
    }

    /**
     * Audio relay from a client in {@code Room-1} reaches another registered peer in the same room
     * but never crosses into {@code Room-2}.
     */
    @Test
    @DisplayName("UDP audio from Room-1 is relayed to same-room peer only, not Room-2")
    void testUdpRoomIsolation() throws Exception {
        startServer();

        String idA = "11111111-1111-1111-1111-111111111111";
        String idB = "22222222-2222-2222-2222-222222222222";
        String idC = "33333333-3333-3333-3333-333333333333";

        var listenerA = new MutationTrackingListener("A");
        var listenerB = new MutationTrackingListener("B");
        var listenerC = new MutationTrackingListener("C");

        NetworkClient clientA = new NetworkClient(HOST, serverPort, "UserA", idA);
        NetworkClient clientB = new NetworkClient(HOST, serverPort, "UserB", idB);
        NetworkClient clientC = new NetworkClient(HOST, serverPort, "UserC", idC);

        clientA.addListener(listenerA);
        clientB.addListener(listenerB);
        clientC.addListener(listenerC);

        try (DatagramChannel udpA = DatagramChannel.open();
             DatagramChannel udpB = DatagramChannel.open();
             DatagramChannel udpC = DatagramChannel.open()) {

            udpA.configureBlocking(false);
            udpB.configureBlocking(false);
            udpC.configureBlocking(false);
            udpA.bind(new InetSocketAddress(HOST, 0));
            udpB.bind(new InetSocketAddress(HOST, 0));
            udpC.bind(new InetSocketAddress(HOST, 0));

            clientA.connect();
            clientB.connect();
            clientC.connect();

            clientA.sendJoinRoom("Room-1", "Board-1");
            clientB.sendJoinRoom("Room-1", "Board-1");
            clientC.sendJoinRoom("Room-2", "Board-1");

            await("all clients received SNAPSHOT after join")
                    .atMost(SETUP_TIMEOUT_S, TimeUnit.SECONDS)
                    .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                    .until(() -> listenerA.snapshotSeen.get()
                            && listenerB.snapshotSeen.get()
                            && listenerC.snapshotSeen.get());

            ClientSession sessionA = findSession(roomManager, "Room-1", idA);
            ClientSession sessionB = findSession(roomManager, "Room-1", idB);
            ClientSession sessionC = findSession(roomManager, "Room-2", idC);
            assertThat(sessionA).isNotNull();
            assertThat(sessionB).isNotNull();
            assertThat(sessionC).isNotNull();

            InetSocketAddress serverUdp = new InetSocketAddress(HOST, serverPort);

            udpClientSendRegistration(udpA, serverUdp, sessionA.udpToken);
            udpClientSendRegistration(udpB, serverUdp, sessionB.udpToken);
            udpClientSendRegistration(udpC, serverUdp, sessionC.udpToken);

            await("all three UDP endpoints registered on server")
                    .atMost(SETUP_TIMEOUT_S, TimeUnit.SECONDS)
                    .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                    .until(() -> sessionA.udpEndpoint != null
                            && sessionB.udpEndpoint != null
                            && sessionC.udpEndpoint != null);

            byte[] pcmSuffix = {0x0D, 0x0E, 0x0A, 0x0D};
            ByteBuffer audioFromA = ByteBuffer.allocate(36 + pcmSuffix.length);
            audioFromA.put(sessionA.udpToken.getBytes(StandardCharsets.UTF_8));
            audioFromA.put(pcmSuffix);
            audioFromA.flip();
            udpA.send(audioFromA, serverUdp);

            ByteBuffer receivedAtB = ByteBuffer.allocate(2048);
            await("peer B receives relayed audio from A")
                    .atMost(SETUP_TIMEOUT_S, TimeUnit.SECONDS)
                    .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        receivedAtB.clear();
                        return udpB.receive(receivedAtB) != null;
                    });

            receivedAtB.flip();
            assertThat(receivedAtB.remaining()).isEqualTo(36 + pcmSuffix.length);

            byte[] header = new byte[36];
            receivedAtB.get(header);
            assertThat(header).isEqualTo(idA.getBytes(StandardCharsets.UTF_8));

            byte[] tail = new byte[pcmSuffix.length];
            receivedAtB.get(tail);
            assertThat(tail).isEqualTo(pcmSuffix);

            assertNoDatagramArrives(udpC, 600);
        } finally {
            clientA.close();
            clientB.close();
            clientC.close();
        }
    }

    private static void udpClientSendRegistration(DatagramChannel ch, InetSocketAddress server, String token)
            throws IOException {
        ByteBuffer reg = tokenUtf8(token);
        ch.send(reg, server);
    }

    /**
     * Polls a non-blocking {@link DatagramChannel} for up to {@code windowMs} and fails if any
     * datagram is consumed (proves cross-room isolation).
     */
    private static void assertNoDatagramArrives(DatagramChannel ch, long windowMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMs);
        while (System.nanoTime() < deadline) {
            ByteBuffer probe = ByteBuffer.allocate(2048);
            try {
                if (ch.receive(probe) != null) {
                    fail("Unexpected UDP datagram received on isolated channel (cross-room leak)");
                }
            } catch (IOException e) {
                fail("UDP poll failed: " + e.getMessage());
            }
            Thread.sleep(20);
        }
    }

    /**
     * Two clients share one room but different boards. A mutation from the Math-Notes client
     * must apply only to that board's {@link CanvasStateManager} and must be broadcast only to
     * peers on the same board — not to clients on another board.
     */
    @Test
    void testBoardCreationAndIsolation() throws Exception {
        startServer();

        var listenerA = new MutationTrackingListener("A");
        var listenerB = new MutationTrackingListener("B");
        var listenerC = new MutationTrackingListener("C");

        NetworkClient clientA = new NetworkClient(HOST, serverPort, "UserA", "client-a");
        NetworkClient clientB = new NetworkClient(HOST, serverPort, "UserB", "client-b");
        NetworkClient clientC = new NetworkClient(HOST, serverPort, "UserC", "client-c");

        clientA.addListener(listenerA);
        clientB.addListener(listenerB);
        clientC.addListener(listenerC);

        try {
            clientA.connect();
            clientB.connect();
            clientC.connect();

            clientA.sendJoinRoom("SharedRoom", "Math-Notes");
            clientB.sendJoinRoom("SharedRoom", "Diagrams");
            clientC.sendJoinRoom("SharedRoom", "Math-Notes");

            await("all three clients receive initial SNAPSHOT")
                    .atMost(SETUP_TIMEOUT_S, TimeUnit.SECONDS)
                    .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                    .until(() -> listenerA.snapshotSeen.get()
                            && listenerB.snapshotSeen.get()
                            && listenerC.snapshotSeen.get());

            Line line = Line.create("#E63946", 1.0, 2.0, 100.0, 200.0, 2.0);
            clientA.sendMutation(line);

            await("peer on Math-Notes receives the MUTATION")
                    .atMost(BCAST_TIMEOUT_S, TimeUnit.SECONDS)
                    .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                    .until(() -> line.objectId().equals(listenerC.lastMutationShapeId.get()));

            assertThat(listenerB.mutationCount.get())
                    .as("Client B is on Diagrams — must not receive Math-Notes MUTATION frames")
                    .isZero();

            assertThat(listenerA.mutationCount.get())
                    .as("Server does not echo MUTATION back to the sender")
                    .isZero();

            RoomContext room = roomManager.getRoom("SharedRoom");
            assertThat(room).isNotNull();

            List<Shape> mathShapes = room.getBoard("Math-Notes").snapshot();
            List<Shape> diagramShapes = room.getBoard("Diagrams").snapshot();

            assertThat(mathShapes)
                    .as("authoritative Math-Notes board must contain the applied line")
                    .hasSize(1);
            assertThat(mathShapes.get(0).objectId()).isEqualTo(line.objectId());

            assertThat(diagramShapes)
                    .as("Diagrams board must be untouched by a Math-Notes mutation")
                    .isEmpty();
        } finally {
            clientA.close();
            clientB.close();
            clientC.close();
        }
    }

    /**
     * After {@code SWITCH_BOARD}, the server must update the session's {@code currentBoardId}
     * (otherwise the follow-up SNAPSHOT would not be sourced from the Diagrams manager) and
     * must push a full {@code SNAPSHOT} containing that board's shapes.
     */
    @Test
    void testBoardSwitching() throws Exception {
        startServer();

        UUID diagramShapeId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Circle diagramOnly = new Circle(
                diagramShapeId, 42L, "#ABCDEF",
                10.0, 20.0, 5.0, false, 1.0, "seed", "server");

        RoomContext preCtx = roomManager.getOrCreateRoom("SwitchRoom");
        preCtx.getBoard("Diagrams").applyMutation(diagramOnly);

        var listener = new SnapshotCountingListener();
        NetworkClient client = new NetworkClient(HOST, serverPort, "Switcher", "client-switch");

        client.addListener(listener);

        try {
            client.connect();
            client.sendJoinRoom("SwitchRoom", "Math-Notes");

            await("first SNAPSHOT after join (empty Math-Notes)")
                    .atMost(SETUP_TIMEOUT_S, TimeUnit.SECONDS)
                    .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                    .until(() -> listener.snapshotCount.get() >= 1);

            assertThat(listener.state)
                    .as("Math-Notes starts empty for this client")
                    .isEmpty();

            client.sendSwitchBoard("Diagrams");

            await("second SNAPSHOT after SWITCH_BOARD includes Diagrams state")
                    .atMost(BCAST_TIMEOUT_S, TimeUnit.SECONDS)
                    .pollInterval(POLL_MS, TimeUnit.MILLISECONDS)
                    .until(() -> listener.snapshotCount.get() >= 2
                            && listener.state.containsKey(diagramShapeId));

            assertThat(listener.state.get(diagramShapeId))
                    .as("SNAPSHOT must materialise the pre-seeded Diagrams circle")
                    .isEqualTo(diagramOnly);

            assertThat(listener.snapshotCount.get())
                    .as("join + switch produces at least two SNAPSHOT frames")
                    .isGreaterThanOrEqualTo(2);

            assertThat(roomManager.getActiveClientKeys("SwitchRoom"))
                    .as("client must still be attached to the room for session inspection")
                    .isNotEmpty();

            boolean sawSession = false;
            for (SelectionKey key : roomManager.getActiveClientKeys("SwitchRoom")) {
                if (key.attachment() instanceof ClientSession s) {
                    assertThat(s.currentBoardId)
                            .as("SWITCH_BOARD must update the session's active board")
                            .isEqualTo("Diagrams");
                    sawSession = true;
                }
            }
            assertThat(sawSession).as("expected a ClientSession on the room's SelectionKey").isTrue();
        } finally {
            client.close();
        }
    }

    // -------------------------------------------------------------------------
    // Test listeners
    // -------------------------------------------------------------------------

    private static final class MutationTrackingListener implements CanvasUpdateListener {

        final AtomicBoolean snapshotSeen = new AtomicBoolean(false);
        final AtomicInteger mutationCount = new AtomicInteger(0);
        final AtomicReference<UUID> lastMutationShapeId = new AtomicReference<>();

        MutationTrackingListener(@SuppressWarnings("unused") String label) {
        }

        @Override
        public void onSnapshotReceived(List<Shape> shapes) {
            snapshotSeen.set(true);
        }

        @Override
        public void onMutationReceived(Shape shape) {
            mutationCount.incrementAndGet();
            lastMutationShapeId.set(shape.objectId());
        }
    }

    private static final class SnapshotCountingListener implements CanvasUpdateListener {

        final AtomicInteger snapshotCount = new AtomicInteger(0);
        final ConcurrentHashMap<UUID, Shape> state = new ConcurrentHashMap<>();

        @Override
        public void onSnapshotReceived(List<Shape> shapes) {
            snapshotCount.incrementAndGet();
            state.clear();
            shapes.forEach(s -> state.put(s.objectId(), s));
        }

        @Override
        public void onMutationReceived(Shape shape) {
            state.merge(shape.objectId(), shape, (existing, incoming) ->
                    incoming.timestamp() > existing.timestamp() ? incoming : existing);
        }
    }
}
