package com.distrisync.server;

import com.distrisync.client.CanvasUpdateListener;
import com.distrisync.client.NetworkClient;
import com.distrisync.model.Circle;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.channels.SelectionKey;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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
