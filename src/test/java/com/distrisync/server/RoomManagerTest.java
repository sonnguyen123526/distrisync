package com.distrisync.server;

import com.distrisync.model.Circle;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageCodec.LobbyRoomEntry;
import com.distrisync.protocol.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link RoomManager}.
 *
 * <h2>Stub strategy</h2>
 * {@link SelectionKey} is abstract and cannot be directly instantiated.
 * Rather than introducing a mocking framework, each test method that needs
 * keys calls {@link #stubKey(String)} to obtain a minimal anonymous
 * implementation whose only behaviour is a descriptive {@link #toString()}.
 * All methods that NioServer actually calls ({@link SelectionKey#isValid()},
 * {@link SelectionKey#cancel()}) are present and safe to invoke.
 *
 * <h2>Package access</h2>
 * This test class lives in the {@code com.distrisync.server} package so it
 * can read the package-private {@link RoomContext} API
 * ({@code getBoard}, {@code getActiveKeys()}) and call
 * {@link RoomContext#touchActivity()} without reflection.
 * Private fields ({@code lastActivityTimestamp}) and private methods
 * ({@code StorageLifecycleManager.runCycle()}) that are needed by lifecycle
 * tests are accessed via {@link java.lang.reflect.Field} /
 * {@link java.lang.reflect.Method}.
 */
class RoomManagerTest {

    private RoomManager roomManager;

    @BeforeEach
    void setUp() {
        roomManager = new RoomManager();
    }

    // =========================================================================
    // Lobby discovery (LOBBY_STATE fan-out)
    // =========================================================================

    /**
     * <h2>Scenario</h2>
     * <ul>
     *   <li>Client A completes handshake and waits in the discovery lobby.</li>
     *   <li>Client B completes handshake, then {@code JOIN_ROOM} creates/joins
     *       {@code "StudyGroup"} (modeled as {@link RoomManager#assignClientToRoom}).</li>
     * </ul>
     *
     * <h2>Invariants</h2>
     * <ol>
     *   <li>The final {@code LOBBY_STATE} payload lists {@code StudyGroup} with
     *       {@code userCount == 1} (only B is in the room; A is still in the lobby).</li>
     *   <li>{@link RoomManager#snapshotLobbyKeys()} contains A and not B at that
     *       point — matching what {@link NioServer} would fan out to A.</li>
     * </ol>
     */
    @Test
    void testLobbyStateBroadcast() {
        List<Message> capturedLobbyStates = new CopyOnWriteArrayList<>();
        roomManager.setLobbyFanout(buf -> {
            ByteBuffer dup = buf.duplicate();
            capturedLobbyStates.add(MessageCodec.decode(dup));
        });

        SelectionKey keyA = stubKey("Client-A");
        SelectionKey keyB = stubKey("Client-B");

        roomManager.registerHandshakeToLobby(keyA);
        assertThat(roomManager.isInLobby(keyA))
                .as("after HANDSHAKE, Client A must be registered in the lobby")
                .isTrue();

        roomManager.registerHandshakeToLobby(keyB);
        assertThat(roomManager.isInLobby(keyB))
                .as("after HANDSHAKE, Client B must be registered in the lobby")
                .isTrue();

        roomManager.assignClientToRoom(keyB, "StudyGroup", "");

        assertThat(roomManager.isInLobby(keyA))
                .as("Client A must remain in the lobby while B is in StudyGroup")
                .isTrue();
        assertThat(roomManager.isInLobby(keyB))
                .as("after JOIN_ROOM, Client B must no longer be in the lobby set")
                .isFalse();

        assertThat(roomManager.snapshotLobbyKeys())
                .as("only lobby clients receive LOBBY_STATE — A is subscribed, B is not")
                .containsExactly(keyA);

        assertThat(capturedLobbyStates)
                .as("each lobby mutation must invoke the fan-out callback")
                .isNotEmpty();

        Message latest = capturedLobbyStates.get(capturedLobbyStates.size() - 1);
        assertThat(latest.type())
                .as("fan-out must encode LOBBY_STATE frames")
                .isEqualTo(MessageType.LOBBY_STATE);

        List<LobbyRoomEntry> entries = MessageCodec.decodeLobbyState(latest);
        assertThat(entries)
                .as("with one active room and one occupant, LOBBY_STATE must contain exactly one row")
                .containsExactly(new LobbyRoomEntry("StudyGroup", 1));
    }

    /**
     * <h2>Scenario</h2>
     * Client A is in {@code Room1} (sole occupant). A {@code LEAVE_ROOM} is modeled
     * as {@link RoomManager#returnClientToLobby(SelectionKey, String)}.
     *
     * <h2>Invariants</h2>
     * <ol>
     *   <li>A's key is removed from Room1's active-key set.</li>
     *   <li>A is re-registered in {@code lobbyClients}.</li>
     *   <li>When A was the last user, {@code Room1} is removed from the routing
     *       map (graceful teardown of an empty room).</li>
     * </ol>
     */
    @Test
    void testLeaveRoomReturnsToLobby() {
        SelectionKey keyA = stubKey("Client-A");

        roomManager.registerHandshakeToLobby(keyA);
        roomManager.assignClientToRoom(keyA, "Room1", "");

        RoomContext room1 = roomManager.getRoom("Room1");
        assertThat(room1)
                .as("pre-condition: Room1 must exist after JOIN_ROOM")
                .isNotNull();
        assertThat(room1.getActiveKeys())
                .as("pre-condition: Client A must be the sole active key in Room1")
                .containsExactly(keyA);
        assertThat(roomManager.isInLobby(keyA))
                .as("pre-condition: Client A must not be in the lobby while in Room1")
                .isFalse();

        roomManager.returnClientToLobby(keyA, "Room1");

        assertThat(roomManager.getRoom("Room1"))
                .as("when the last client leaves, Room1 must be removed from the routing map")
                .isNull();
        assertThat(roomManager.getActiveClientKeys("Room1"))
                .as("lookup by evicted room id must yield an empty key set")
                .isEmpty();
        assertThat(roomManager.isInLobby(keyA))
                .as("after LEAVE_ROOM, Client A must be back in the lobby")
                .isTrue();
    }

    /**
     * Negative-space control: leaving a room must <em>not</em> delete the
     * {@link RoomContext} while other clients remain connected.
     */
    @Test
    void testLeaveRoomReturnsToLobby_preservesRoomWhenOthersRemain() {
        SelectionKey keyA = stubKey("Client-A");
        SelectionKey keyB = stubKey("Client-B");

        roomManager.registerHandshakeToLobby(keyA);
        roomManager.registerHandshakeToLobby(keyB);
        roomManager.assignClientToRoom(keyA, "Room1", "");
        roomManager.assignClientToRoom(keyB, "Room1", "");

        assertThat(roomManager.getRoom("Room1").getActiveKeys())
                .as("pre-condition: both clients share Room1")
                .containsExactlyInAnyOrder(keyA, keyB);

        roomManager.returnClientToLobby(keyA, "Room1");

        assertThat(roomManager.getRoom("Room1"))
                .as("Room1 must survive while Client B is still connected")
                .isNotNull();
        assertThat(roomManager.getRoom("Room1").getActiveKeys())
                .as("only Client A should have been removed from the room")
                .containsExactly(keyB);
        assertThat(roomManager.isInLobby(keyA)).isTrue();
        assertThat(roomManager.isInLobby(keyB)).isFalse();
    }

    // =========================================================================
    // testRoomCreationAndIsolation
    // =========================================================================

    /**
     * End-to-end isolation proof for the multi-tenant routing layer.
     *
     * <h2>Scenario</h2>
     * <ul>
     *   <li>Client A and Client B join <b>Room1</b>.</li>
     *   <li>Client C joins <b>Room2</b>.</li>
     * </ul>
     *
     * <h2>Invariants asserted</h2>
     * <ol>
     *   <li><b>Distinct state managers</b> — each room owns its own
     *       {@link CanvasStateManager} instance; a mutation applied in
     *       Room1 is invisible in Room2's snapshot and vice-versa.</li>
     *   <li><b>Active-key membership</b> — Room1's key-set contains exactly
     *       Client A and Client B; Room2's key-set contains exactly Client C.
     *       No cross-room leakage is present in either direction.</li>
     *   <li><b>Idempotent room lookup</b> — a second call to
     *       {@code getOrCreateRoom("Room1")} returns the <em>identical</em>
     *       {@link RoomContext} instance (reference equality), confirming
     *       that {@code computeIfAbsent} does not create a duplicate.</li>
     * </ol>
     */
    @Test
    void testRoomCreationAndIsolation() {
        SelectionKey keyA = stubKey("Client-A");
        SelectionKey keyB = stubKey("Client-B");
        SelectionKey keyC = stubKey("Client-C");

        // ── Room setup ────────────────────────────────────────────────────────
        RoomContext room1 = roomManager.getOrCreateRoom("Room1");
        room1.addKey(keyA);
        room1.addKey(keyB);

        RoomContext room2 = roomManager.getOrCreateRoom("Room2");
        room2.addKey(keyC);

        // ── 1. State manager identity — must be distinct objects ─────────────
        String board = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        assertThat(room1.getBoard(board))
                .as("Room1 must have its own CanvasStateManager instance")
                .isNotNull();
        assertThat(room2.getBoard(board))
                .as("Room2 must have its own CanvasStateManager instance")
                .isNotNull();
        assertThat(room1.getBoard(board))
                .as("Room1 and Room2 must NOT share a CanvasStateManager — " +
                    "a mutation in one room must never affect the other")
                .isNotSameAs(room2.getBoard(board));

        // ── 2. Canvas state isolation — apply a mutation in each room and
        //      verify neither snapshot leaks into the other room's view ────────
        UUID shapeInRoom1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID shapeInRoom2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

        room1.getBoard(board).applyMutation(
                new Line(shapeInRoom1, 1000L, "#FF0000", 0, 0, 100, 100, 2.0, "Alice", "client-A"));
        room2.getBoard(board).applyMutation(
                new Circle(shapeInRoom2, 2000L, "#0000FF", 50, 50, 25, false, 1.5, "Carol", "client-C"));

        List<Shape> snap1 = room1.getBoard(board).snapshot();
        List<Shape> snap2 = room2.getBoard(board).snapshot();

        assertThat(snap1)
                .as("Room1's snapshot must contain exactly the one shape inserted into Room1")
                .hasSize(1);
        assertThat(snap1.get(0).objectId())
                .as("Room1's shape must be the Line inserted by Client A, not the Circle from Room2")
                .isEqualTo(shapeInRoom1);

        assertThat(snap2)
                .as("Room2's snapshot must contain exactly the one shape inserted into Room2")
                .hasSize(1);
        assertThat(snap2.get(0).objectId())
                .as("Room2's shape must be the Circle inserted by Client C, not the Line from Room1")
                .isEqualTo(shapeInRoom2);

        // ── 3. Active-key sets — correct membership, no cross-room leakage ────
        Set<SelectionKey> keys1 = room1.getActiveKeys();
        Set<SelectionKey> keys2 = room2.getActiveKeys();

        assertThat(keys1)
                .as("Room1 active-key set must contain Client A")
                .contains(keyA);
        assertThat(keys1)
                .as("Room1 active-key set must contain Client B")
                .contains(keyB);
        assertThat(keys1)
                .as("Room1 active-key set must NOT contain Client C (Client C joined Room2)")
                .doesNotContain(keyC);

        assertThat(keys2)
                .as("Room2 active-key set must contain Client C")
                .contains(keyC);
        assertThat(keys2)
                .as("Room2 active-key set must NOT contain Client A (Client A joined Room1)")
                .doesNotContain(keyA);
        assertThat(keys2)
                .as("Room2 active-key set must NOT contain Client B (Client B joined Room1)")
                .doesNotContain(keyB);

        assertThat(keys1)
                .as("Room1 must have exactly 2 active clients: A and B")
                .hasSize(2);
        assertThat(keys2)
                .as("Room2 must have exactly 1 active client: C")
                .hasSize(1);

        // ── 4. Idempotent lookup — same room ID must return the same instance ──
        RoomContext room1Again = roomManager.getOrCreateRoom("Room1");
        assertThat(room1Again)
                .as("getOrCreateRoom(\"Room1\") called a second time must return the " +
                    "exact same RoomContext (reference equality) — no duplicate room must be created")
                .isSameAs(room1);
    }

    // =========================================================================
    // testClientRemoval
    // =========================================================================

    /**
     * Verifies the full behavioural contract of
     * {@link RoomManager#removeClientFromRoom}.
     *
     * <h2>Scenario</h2>
     * Client A and Client B both join Room1.  Client A disconnects.
     *
     * <h2>Invariants asserted</h2>
     * <ol>
     *   <li><b>Targeted removal</b> — after the call, Room1's active-key set
     *       contains Client B but no longer contains Client A.</li>
     *   <li><b>Survivor count</b> — the set size drops from 2 to exactly 1.</li>
     *   <li><b>Canvas state unaffected</b> — the room's
     *       {@link CanvasStateManager} is not modified by the key removal;
     *       any shape previously committed by Client A persists in the room's
     *       snapshot (disconnect does not retroactively clear shapes).</li>
     *   <li><b>Idempotency</b> — removing the same key a second time does not
     *       throw and leaves the set size unchanged at 1.</li>
     *   <li><b>Room2 unaffected</b> — removing a key from Room1 has no side
     *       effect on Room2's key-set (Client C remains in Room2).</li>
     *   <li><b>Non-existent room no-op</b> — calling
     *       {@code removeClientFromRoom} for a room that was never created
     *       silently completes without throwing.</li>
     * </ol>
     */
    @Test
    void testClientRemoval() {
        SelectionKey keyA = stubKey("Client-A");
        SelectionKey keyB = stubKey("Client-B");
        SelectionKey keyC = stubKey("Client-C");

        // ── Setup ─────────────────────────────────────────────────────────────
        RoomContext room1 = roomManager.getOrCreateRoom("Room1");
        room1.addKey(keyA);
        room1.addKey(keyB);

        RoomContext room2 = roomManager.getOrCreateRoom("Room2");
        room2.addKey(keyC);

        // Client A commits one shape before disconnecting.
        UUID shapeByA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        room1.getBoard(MessageCodec.DEFAULT_INITIAL_BOARD_ID).applyMutation(
                new Line(shapeByA, 1000L, "#FF0000", 0, 0, 50, 50, 1.0, "Alice", "client-A"));

        assertThat(room1.getActiveKeys())
                .as("pre-condition: Room1 must have 2 active keys before removal")
                .hasSize(2);

        // ── Act: Client A disconnects ─────────────────────────────────────────
        roomManager.removeClientFromRoom("Room1", keyA);

        // ── 1. Targeted removal ───────────────────────────────────────────────
        assertThat(room1.getActiveKeys())
                .as("Client A's key must be absent from Room1 after removal")
                .doesNotContain(keyA);
        assertThat(room1.getActiveKeys())
                .as("Client B's key must still be present in Room1 after Client A's removal")
                .contains(keyB);

        // ── 2. Survivor count ────────────────────────────────────────────────
        assertThat(room1.getActiveKeys())
                .as("Room1 must have exactly 1 active key remaining after Client A disconnects")
                .hasSize(1);

        // ── 3. Canvas state unaffected by disconnect ─────────────────────────
        List<Shape> snapshot = room1.getBoard(MessageCodec.DEFAULT_INITIAL_BOARD_ID).snapshot();
        assertThat(snapshot)
                .as("Room1's canvas must still contain the shape committed by Client A — " +
                    "disconnect must not retroactively delete shapes")
                .hasSize(1);
        assertThat(snapshot.get(0).objectId())
                .as("the persisted shape must be the one Client A committed before disconnecting")
                .isEqualTo(shapeByA);

        // ── 4. Idempotency ───────────────────────────────────────────────────
        assertThatCode(() -> roomManager.removeClientFromRoom("Room1", keyA))
                .as("a second removeClientFromRoom for the same key must not throw")
                .doesNotThrowAnyException();
        assertThat(room1.getActiveKeys())
                .as("size must remain 1 after the idempotent second removal attempt")
                .hasSize(1);

        // ── 5. Room2 unaffected ──────────────────────────────────────────────
        assertThat(room2.getActiveKeys())
                .as("Room2's key-set must not be affected by a removal targeting Room1")
                .containsExactly(keyC);

        // ── 6. Non-existent room is a safe no-op ─────────────────────────────
        assertThatCode(() -> roomManager.removeClientFromRoom("NonExistentRoom", keyA))
                .as("removeClientFromRoom for a room that was never created must not throw")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // testGetOrCreateRoom_ReturnsSameInstanceOnIdempotentCall
    // =========================================================================

    /**
     * Confirms that {@link RoomManager#getOrCreateRoom} is idempotent: N
     * successive calls for the same {@code roomId} must all return the
     * <em>same</em> {@link RoomContext} object without spawning duplicates.
     *
     * <p>Idempotency is critical for correctness: if two callers racing on
     * the first join both received <em>different</em> context objects, one
     * set of clients would have a snapshot invisible to the other set.
     */
    @Test
    void testGetOrCreateRoom_ReturnsSameInstanceOnIdempotentCall() {
        RoomContext first  = roomManager.getOrCreateRoom("Lobby");
        RoomContext second = roomManager.getOrCreateRoom("Lobby");
        RoomContext third  = roomManager.getOrCreateRoom("Lobby");

        assertThat(second)
                .as("second getOrCreateRoom(\"Lobby\") must return the identical instance as the first call")
                .isSameAs(first);
        assertThat(third)
                .as("third getOrCreateRoom(\"Lobby\") must return the identical instance as the first call")
                .isSameAs(first);
    }

    // =========================================================================
    // testGetRoom_ReturnsNullForUnknownRoom
    // =========================================================================

    /**
     * {@link RoomManager#getRoom} must return {@code null} for a room that
     * has never been created.  Callers (i.e. {@link NioServer#processMessage})
     * rely on a {@code null} return to detect sessions that send mutations
     * before completing their HANDSHAKE routing.
     */
    @Test
    void testGetRoom_ReturnsNullForUnknownRoom() {
        assertThat(roomManager.getRoom("ghost-room"))
                .as("getRoom for a room that was never created must return null")
                .isNull();

        assertThat(roomManager.getRoom(null))
                .as("getRoom(null) must return null without throwing")
                .isNull();
    }

    // =========================================================================
    // testGetRoomSnapshot_EmptyListForUnknownRoom
    // =========================================================================

    /**
     * {@link RoomManager#getRoomSnapshot} must degrade gracefully to an empty,
     * immutable list rather than throwing when queried for a room that does not
     * exist.  This is the contract relied upon by the integration test's
     * server-state assertion.
     */
    @Test
    void testGetRoomSnapshot_EmptyListForUnknownRoom() {
        List<Shape> result = roomManager.getRoomSnapshot("does-not-exist");

        assertThat(result)
                .as("getRoomSnapshot for an unknown room must return an empty list, not null")
                .isNotNull()
                .isEmpty();

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("the empty list returned for an unknown room must be unmodifiable")
                .isThrownBy(() -> result.add(Circle.create("#000000", 0, 0, 1)));
    }

    // =========================================================================
    // testGetOrCreateRoom_BlankOrNullRoomIdThrows
    // =========================================================================

    /**
     * A blank or {@code null} {@code roomId} is a programming error.
     * {@link RoomManager#getOrCreateRoom} must reject both with an
     * {@link IllegalArgumentException} rather than silently creating an
     * unnamed room that could become a routing black-hole.
     */
    @Test
    void testGetOrCreateRoom_BlankOrNullRoomIdThrows() {
        assertThatIllegalArgumentException()
                .as("getOrCreateRoom(null) must throw IllegalArgumentException")
                .isThrownBy(() -> roomManager.getOrCreateRoom(null));

        assertThatIllegalArgumentException()
                .as("getOrCreateRoom(\"\") must throw IllegalArgumentException")
                .isThrownBy(() -> roomManager.getOrCreateRoom(""));

        assertThatIllegalArgumentException()
                .as("getOrCreateRoom(\"   \") must throw IllegalArgumentException — whitespace is blank")
                .isThrownBy(() -> roomManager.getOrCreateRoom("   "));
    }

    // =========================================================================
    // testActiveKeysView_IsUnmodifiable
    // =========================================================================

    /**
     * The set returned by {@link RoomContext#getActiveKeys()} must be
     * unmodifiable.  This prevents external callers from bypassing the
     * {@link RoomContext#addKey}/{@link RoomContext#removeKey} contract
     * and corrupting the routing table.
     */
    @Test
    void testActiveKeysView_IsUnmodifiable() {
        RoomContext room = roomManager.getOrCreateRoom("Vault");
        room.addKey(stubKey("X"));

        Set<SelectionKey> view = room.getActiveKeys();

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("add() on the active-key view must be forbidden")
                .isThrownBy(() -> view.add(stubKey("Y")));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("clear() on the active-key view must be forbidden")
                .isThrownBy(view::clear);
    }

    // =========================================================================
    // testRemoveFromBlankRoomId_IsNoOp
    // =========================================================================

    /**
     * Passing a blank or {@code null} {@code roomId} to
     * {@link RoomManager#removeClientFromRoom} must silently succeed because
     * sessions that disconnect before completing their HANDSHAKE have
     * {@code roomId == ""} and must not cause an exception in the NIO event loop.
     */
    @Test
    void testRemoveFromBlankRoomId_IsNoOp() {
        assertThatCode(() -> roomManager.removeClientFromRoom("",   stubKey("Z")))
                .as("removeClientFromRoom with a blank roomId must not throw")
                .doesNotThrowAnyException();

        assertThatCode(() -> roomManager.removeClientFromRoom(null, stubKey("Z")))
                .as("removeClientFromRoom with a null roomId must not throw")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // testMultipleRoomsAreIndependentlyTracked
    // =========================================================================

    /**
     * Verifies that the manager correctly tracks N rooms simultaneously with
     * no interference between their key-sets or state managers.
     *
     * <p>Three distinct rooms are created, each receiving a unique key.
     * The test asserts:
     * <ul>
     *   <li>All three rooms return distinct {@link CanvasStateManager}
     *       instances (verified by reference inequality across all pairs).</li>
     *   <li>Each room's active-key set contains exactly its own key, not the
     *       keys of the other rooms.</li>
     * </ul>
     */
    @Test
    void testMultipleRoomsAreIndependentlyTracked() {
        String[] roomIds = {"Alpha", "Beta", "Gamma"};
        SelectionKey[] keys = {
            stubKey("key-alpha"),
            stubKey("key-beta"),
            stubKey("key-gamma")
        };

        RoomContext[] rooms = new RoomContext[3];
        for (int i = 0; i < 3; i++) {
            rooms[i] = roomManager.getOrCreateRoom(roomIds[i]);
            rooms[i].addKey(keys[i]);
        }

        // All three state managers must be distinct objects.
        String board = MessageCodec.DEFAULT_INITIAL_BOARD_ID;
        assertThat(rooms[0].getBoard(board))
                .as("Alpha and Beta must not share a state manager")
                .isNotSameAs(rooms[1].getBoard(board));
        assertThat(rooms[1].getBoard(board))
                .as("Beta and Gamma must not share a state manager")
                .isNotSameAs(rooms[2].getBoard(board));
        assertThat(rooms[0].getBoard(board))
                .as("Alpha and Gamma must not share a state manager")
                .isNotSameAs(rooms[2].getBoard(board));

        // Each room contains exactly its own key.
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            assertThat(rooms[i].getActiveKeys())
                    .as("room '%s' must contain only its own key", roomIds[i])
                    .containsExactly(keys[i]);

            for (int j = 0; j < 3; j++) {
                if (j != idx) {
                    assertThat(rooms[i].getActiveKeys())
                            .as("room '%s' must NOT contain the key of room '%s'",
                                roomIds[idx], roomIds[j])
                            .doesNotContain(keys[j]);
                }
            }
        }
    }

    // =========================================================================
    // testConcurrentRoomCreation_OnlyOneRoomCreatedPerRoomId
    // =========================================================================

    /**
     * Race-condition test for the {@code computeIfAbsent} atomic guarantee.
     *
     * <p>100 virtual threads simultaneously call
     * {@code getOrCreateRoom("RaceRoom")}.  Because {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent}
     * is atomic, exactly <em>one</em> {@link RoomContext} must be created and
     * every thread must receive the same instance (verified via reference
     * equality: all 100 returned references equal the one held by the first
     * caller).
     *
     * <p>If the implementation used a non-atomic check-then-act pattern, a
     * subset of threads could receive different context objects — meaning some
     * clients would see a completely separate canvas.
     */
    @Test
    void testConcurrentRoomCreation_OnlyOneRoomCreatedPerRoomId() throws InterruptedException {
        final int THREAD_COUNT = 100;
        final String ROOM_ID   = "RaceRoom";

        CountDownLatch startGate   = new CountDownLatch(1);
        CountDownLatch doneLatch   = new CountDownLatch(THREAD_COUNT);
        AtomicInteger  errorCount  = new AtomicInteger(0);

        // All threads grab the context and stash a reference; we verify they
        // all point to the exact same object afterwards.
        List<RoomContext> collected =
                java.util.Collections.synchronizedList(new ArrayList<>(THREAD_COUNT));

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < THREAD_COUNT; i++) {
                exec.submit(() -> {
                    try {
                        startGate.await();
                        collected.add(roomManager.getOrCreateRoom(ROOM_ID));
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown(); // release all threads simultaneously
            boolean finished = doneLatch.await(10, TimeUnit.SECONDS);

            assertThat(finished)
                    .as("all %d virtual threads must complete within the 10-second guard timeout",
                        THREAD_COUNT)
                    .isTrue();
        }

        assertThat(errorCount.get())
                .as("no thread must encounter an exception during concurrent room creation")
                .isZero();

        assertThat(collected)
                .as("all threads must receive a non-null RoomContext")
                .hasSize(THREAD_COUNT)
                .doesNotContainNull();

        // Reference equality: every collected instance must be the same object.
        RoomContext canonical = roomManager.getRoom(ROOM_ID);
        assertThat(canonical)
                .as("the room must exist after concurrent creation")
                .isNotNull();

        long distinctInstances = collected.stream().distinct().count();
        assertThat(distinctInstances)
                .as("all %d threads must have received the exact same RoomContext instance — " +
                    "computeIfAbsent must not create duplicates under contention", THREAD_COUNT)
                .isEqualTo(1L);
    }

    // =========================================================================
    // testRoomGc_EvictsIdleRoom
    // =========================================================================

    /**
     * Verifies that {@link StorageLifecycleManager} evicts a quiescent room
     * whose {@code lastActivityTimestamp} has aged beyond the GC TTL, while
     * leaving recently-active rooms untouched.
     *
     * <h2>Setup</h2>
     * <ul>
     *   <li>{@code "GcRoom"} — created with 0 active clients; its
     *       {@code lastActivityTimestamp} is back-dated to 10 minutes ago
     *       (2× the 5-minute GC TTL of {@link StorageLifecycleManager#GC_TTL_MS})
     *       via reflection.</li>
     *   <li>{@code "ActiveRoom"} — created with a fresh timestamp; must
     *       survive the sweep as a negative-space control.</li>
     * </ul>
     *
     * <h2>Execution</h2>
     * The private {@link StorageLifecycleManager} scan method
     * ({@code runCycle()}) is invoked directly via reflection rather than
     * waiting for the 60-second scheduled tick.  This keeps the test
     * deterministic and free of real-time delays.
     *
     * <h2>Invariants asserted</h2>
     * <ol>
     *   <li><b>Precondition — room exists</b> — {@link RoomManager#getRoom}
     *       returns non-null for both rooms before the sweep.</li>
     *   <li><b>Precondition — client count</b> — {@code GcRoom} has 0 active
     *       clients; the GC must not evict rooms that still have connected
     *       sessions.</li>
     *   <li><b>Precondition — timestamp injected</b> — the reflected field
     *       value is verified to be exactly the value written, confirming the
     *       reflection accessor worked as intended.</li>
     *   <li><b>Eviction</b> — after {@code runCycle()}, {@code getRoom("GcRoom")}
     *       returns {@code null}, confirming the room was removed from the
     *       routing table.</li>
     *   <li><b>Selective eviction</b> — {@code "ActiveRoom"} is still present
     *       in the routing table after the sweep, confirming the daemon applies
     *       the TTL filter and does not evict all rooms indiscriminately.</li>
     *   <li><b>Re-join creates a fresh context</b> — calling
     *       {@link RoomManager#getOrCreateRoom} for the evicted room ID after
     *       eviction must produce a brand-new {@link RoomContext} with an empty
     *       canvas, not the stale evicted instance.</li>
     * </ol>
     */
    @Test
    void testRoomGc_EvictsIdleRoom(@TempDir Path gcTempDir) throws Exception {
        try (WalManager walManager = new WalManager(gcTempDir)) {
            RoomManager rm = new RoomManager(walManager);

            // ── Setup: create GcRoom (to be evicted) and ActiveRoom (survivor) ──
            RoomContext gcRoom     = rm.getOrCreateRoom("GcRoom");
            RoomContext activeRoom = rm.getOrCreateRoom("ActiveRoom");

            // Explicitly refresh ActiveRoom's timestamp so it is clearly within TTL.
            activeRoom.touchActivity();

            // ── 1. Precondition: both rooms must be present before the sweep ─────
            assertThat(rm.getRoom("GcRoom"))
                    .as("pre-condition: GcRoom must exist in the routing table before the lifecycle sweep")
                    .isNotNull();
            assertThat(rm.getRoom("ActiveRoom"))
                    .as("pre-condition: ActiveRoom must exist in the routing table before the lifecycle sweep")
                    .isNotNull();

            // ── 2. Precondition: GcRoom must have 0 active clients ───────────────
            //    GC eviction only fires when clients == 0; a room with connected
            //    sessions must never be evicted, regardless of its timestamp.
            assertThat(gcRoom.getActiveClientCount())
                    .as("pre-condition: GcRoom must have 0 active clients — " +
                        "GC must not evict rooms that still have live connections")
                    .isZero();

            // ── 3. Backdate GcRoom's lastActivityTimestamp to 10 minutes ago ─────
            //    The GC TTL is StorageLifecycleManager.GC_TTL_MS (5 min = 300 000 ms).
            //    Setting the timestamp to 10 minutes ago (600 000 ms) makes it 2×
            //    past the threshold, giving a comfortable margin against clock jitter.
            Field tsField = RoomContext.class.getDeclaredField("lastActivityTimestamp");
            tsField.setAccessible(true);
            long tenMinutesAgo = System.currentTimeMillis() - 600_000L;
            tsField.setLong(gcRoom, tenMinutesAgo);

            // Verify the reflection write took effect before trusting the sweep outcome.
            assertThat(tsField.getLong(gcRoom))
                    .as("reflected lastActivityTimestamp must equal the value we wrote — " +
                        "if this fails, the field name has changed or reflection is broken")
                    .isEqualTo(tenMinutesAgo);

            // ── 4. Invoke the lifecycle scan synchronously via reflection ─────────
            //    We call the private runCycle() method directly rather than waiting
            //    for the 60-second ScheduledExecutorService tick; this keeps the test
            //    deterministic and free of any real-time delay.
            StorageLifecycleManager lifecycle = new StorageLifecycleManager(rm, walManager);
            Method runCycle = StorageLifecycleManager.class.getDeclaredMethod("runCycle");
            runCycle.setAccessible(true);
            runCycle.invoke(lifecycle);

            // ── 5. GcRoom must have been evicted from the routing table ───────────
            assertThat(rm.getRoom("GcRoom"))
                    .as("GcRoom must be removed from the RoomManager routing table after the " +
                        "lifecycle sweep — it had 0 clients and was quiescent for 10 min " +
                        "(TTL threshold: " + StorageLifecycleManager.GC_TTL_MS / 60_000L + " min)")
                    .isNull();

            // ── 6. ActiveRoom must NOT have been evicted ──────────────────────────
            assertThat(rm.getRoom("ActiveRoom"))
                    .as("ActiveRoom must still be present after the sweep — its timestamp is " +
                        "well within the GC TTL and must not be collateral damage of the eviction")
                    .isNotNull();

            // ── 7. Re-joining the evicted room creates a fresh, empty context ─────
            //    A client reconnecting after GC must get a clean slate, not the
            //    evicted (now-detached) RoomContext.
            RoomContext rejoinedRoom = rm.getOrCreateRoom("GcRoom");

            assertThat(rejoinedRoom)
                    .as("getOrCreateRoom after eviction must return a non-null RoomContext")
                    .isNotNull();
            assertThat(rejoinedRoom.getBoard(MessageCodec.DEFAULT_INITIAL_BOARD_ID).size())
                    .as("the re-created room must have an empty canvas — " +
                        "the WAL was archived during GC so no old shapes are replayed")
                    .isZero();
            assertThat(rejoinedRoom)
                    .as("the re-created RoomContext must be a distinct instance from the evicted one — " +
                        "getOrCreateRoom must not resurrect the old (evicted) reference")
                    .isNotSameAs(gcRoom);
        }
    }

    // =========================================================================
    // Stub factory
    // =========================================================================

    /**
     * Returns a minimal, safe {@link SelectionKey} stub whose only meaningful
     * behaviour is a {@link #toString()} that includes the supplied label.
     *
     * <p>All abstract methods return safe no-op values so that code paths
     * inside {@link NioServer} that call {@code isValid()} or {@code cancel()}
     * on the key do not throw.  The stub has no attached channel or selector,
     * consistent with its test-only role.
     */
    private static SelectionKey stubKey(String label) {
        return new SelectionKey() {
            @Override public SelectableChannel channel()            { return null; }
            @Override public Selector          selector()           { return null; }
            @Override public boolean           isValid()            { return true; }
            @Override public void              cancel()             {}
            @Override public int               interestOps()        { return 0; }
            @Override public SelectionKey      interestOps(int ops) { return this; }
            @Override public int               readyOps()           { return 0; }
            @Override public String            toString()           { return "StubKey[" + label + "]"; }
        };
    }
}
