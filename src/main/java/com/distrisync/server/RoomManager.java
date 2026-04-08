package com.distrisync.server;

import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageCodec.LobbyRoomEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Multi-tenant room registry plus a global discovery lobby.
 *
 * <p>Maintains a {@link ConcurrentHashMap} of {@link RoomContext}s keyed by
 * room ID.  Room creation is atomic via
 * {@link ConcurrentHashMap#computeIfAbsent}, so concurrent clients racing to
 * join the same room for the first time always receive the same
 * {@link RoomContext} instance.
 *
 * <p>Clients that have completed {@code HANDSHAKE} but not yet {@code JOIN_ROOM}
 * are tracked in {@link #lobbyClients}.  Any change to lobby membership or to
 * per-room occupancy triggers a fresh {@link com.distrisync.protocol.MessageType#LOBBY_STATE}
 * fan-out via the {@link #setLobbyFanout} callback (installed by {@link NioServer}).
 *
 * <h2>WAL integration</h2>
 * An optional {@link WalManager} may be supplied at construction.  When present
 * each {@link #appendToWal} call persists the message before it is broadcast;
 * room construction replays the WAL so state survives server restarts.
 *
 * <h2>Thread safety</h2>
 * All public methods are safe to call from multiple threads concurrently.
 */
public final class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final ConcurrentHashMap<String, RoomContext> rooms = new ConcurrentHashMap<>();
    private final WalManager walManager;

    /** TCP keys waiting in the discovery lobby (post-handshake, pre-join). */
    private final Set<SelectionKey> lobbyClients = ConcurrentHashMap.newKeySet();

    /**
     * Delivers a fully encoded {@code LOBBY_STATE} frame to every lobby client.
     * Set by {@link NioServer} on the selector thread; may also be invoked from
     * the storage lifecycle daemon after GC.
     */
    private volatile Consumer<ByteBuffer> lobbyFanout;

    /** Creates a room manager with no WAL persistence (rooms are ephemeral). */
    public RoomManager() {
        this.walManager = null;
    }

    /**
     * Creates a WAL-backed room manager.
     *
     * @param walManager the WAL engine used for append and recovery;
     *                   must not be {@code null}
     */
    public RoomManager(WalManager walManager) {
        if (walManager == null) throw new IllegalArgumentException("walManager must not be null");
        this.walManager = walManager;
    }

    /**
     * Installs the callback that broadcasts {@code LOBBY_STATE} to all current
     * lobby subscribers.  Typically invoked once from {@link NioServer#run()}.
     */
    public void setLobbyFanout(Consumer<ByteBuffer> fanout) {
        this.lobbyFanout = fanout;
    }

    /** @return {@code true} if {@code key} is registered in the discovery lobby */
    public boolean isInLobby(SelectionKey key) {
        return lobbyClients.contains(key);
    }

    /**
     * After a successful {@code HANDSHAKE}, registers the client in the lobby and
     * pushes an updated {@code LOBBY_STATE} to all lobby clients (including the new one).
     */
    public void registerHandshakeToLobby(SelectionKey key) {
        if (lobbyClients.add(key)) {
            notifyLobbySubscribers();
        }
    }

    /**
     * Removes a key from the lobby set without touching canvas rooms.  Used on disconnect.
     */
    public void removeFromLobby(SelectionKey key) {
        if (lobbyClients.remove(key)) {
            notifyLobbySubscribers();
        }
    }

    /**
     * Moves a client from the lobby (or from {@code previousRoomId} if non-blank)
     * into {@code newRoomId}, then broadcasts {@code LOBBY_STATE}.
     *
     * @param previousRoomId room the client was in, or blank if coming from lobby only
     * @return the {@link RoomContext} for {@code newRoomId}
     */
    public RoomContext assignClientToRoom(SelectionKey key, String newRoomId, String previousRoomId) {
        if (newRoomId == null || newRoomId.isBlank()) {
            throw new IllegalArgumentException("newRoomId must not be null or blank");
        }
        lobbyClients.remove(key);
        if (previousRoomId != null && !previousRoomId.isBlank()) {
            RoomContext prev = rooms.get(previousRoomId);
            if (prev != null) {
                prev.removeKey(key);
            }
        }
        RoomContext ctx = getOrCreateRoom(newRoomId);
        ctx.addKey(key);
        notifyLobbySubscribers();
        return ctx;
    }

    /**
     * Removes a client from their canvas room and places them back in the lobby.
     */
    public void returnClientToLobby(SelectionKey key, String currentRoomId) {
        if (currentRoomId == null || currentRoomId.isBlank()) {
            return;
        }
        RoomContext room = rooms.get(currentRoomId);
        if (room != null) {
            room.removeKey(key);
            if (room.getActiveClientCount() == 0) {
                rooms.remove(currentRoomId, room);
            }
        }
        lobbyClients.add(key);
        notifyLobbySubscribers();
    }

    // =========================================================================
    // Room lifecycle
    // =========================================================================

    /**
     * Returns the existing {@link RoomContext} for {@code roomId}, or creates
     * one if none exists.  The creation is atomic — concurrent callers always
     * receive the same instance.
     *
     * <p>When this manager was constructed with a {@link WalManager} the new
     * room's constructor will replay any existing WAL for this room ID.
     *
     * @param roomId non-null, non-blank room identifier
     * @return the canonical {@link RoomContext} for this room
     * @throws IllegalArgumentException if {@code roomId} is {@code null} or blank
     */
    public RoomContext getOrCreateRoom(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId must not be null or blank");
        }
        return rooms.computeIfAbsent(roomId, id -> {
            log.info("Creating room  roomId='{}'", id);
            return new RoomContext(id, walManager);
        });
    }

    /**
     * Returns the {@link RoomContext} for {@code roomId}, or {@code null} if
     * no room with that ID exists.
     *
     * @param roomId the room to look up; {@code null} returns {@code null}
     */
    public RoomContext getRoom(String roomId) {
        if (roomId == null) return null;
        return rooms.get(roomId);
    }

    /**
     * Returns a point-in-time snapshot of the canvas for {@code roomId}.
     *
     * <p>Returns an empty, unmodifiable list if the room does not exist.
     *
     * @param roomId the room to query; may be {@code null}
     */
    public List<Shape> getRoomSnapshot(String roomId) {
        RoomContext room = getRoom(roomId);
        return room != null ? room.stateManager.snapshot() : List.of();
    }

    // =========================================================================
    // Client management
    // =========================================================================

    /**
     * Removes {@code key} from the active-key set of {@code roomId}.
     *
     * <p>A {@code null} or blank {@code roomId} is silently ignored —
     * sessions that disconnect before completing their HANDSHAKE have
     * {@code roomId == ""} and must not trigger an exception.
     *
     * @param roomId the room to remove from; null/blank → no-op
     * @param key    the client key to remove
     */
    public void removeClientFromRoom(String roomId, SelectionKey key) {
        if (roomId == null || roomId.isBlank()) return;
        RoomContext room = rooms.get(roomId);
        if (room != null && room.removeKey(key)) {
            notifyLobbySubscribers();
        }
    }

    /**
     * Returns an unmodifiable view of {@link SelectionKey}s currently registered
     * in {@code roomId}, or an empty set if the room does not exist.
     *
     * <p>Used by {@link NioServer} for room-scoped broadcast fan-out.
     *
     * @param roomId the room to query; {@code null} yields an empty set
     */
    public Set<SelectionKey> getActiveClientKeys(String roomId) {
        RoomContext ctx = getRoom(roomId);
        return ctx != null ? ctx.getActiveKeys() : Set.of();
    }

    /**
     * Snapshot of lobby keys for iteration on the NIO thread (avoids concurrent
     * modification while fanning out {@code LOBBY_STATE}).
     */
    List<SelectionKey> snapshotLobbyKeys() {
        return List.copyOf(lobbyClients);
    }

    private List<LobbyRoomEntry> buildLobbyRoomEntries() {
        List<LobbyRoomEntry> list = new ArrayList<>(rooms.size());
        for (var e : rooms.entrySet()) {
            list.add(new LobbyRoomEntry(e.getKey(), e.getValue().getActiveClientCount()));
        }
        list.sort(Comparator.comparing(LobbyRoomEntry::roomId));
        return list;
    }

    private void notifyLobbySubscribers() {
        Consumer<ByteBuffer> fan = lobbyFanout;
        if (fan == null) {
            return;
        }
        ByteBuffer frame = MessageCodec.encodeLobbyState(buildLobbyRoomEntries());
        fan.accept(frame);
    }

    // =========================================================================
    // WAL delegation
    // =========================================================================

    /**
     * Appends {@code msg} to the WAL for {@code roomId}.
     *
     * <p>No-op if this manager was constructed without a {@link WalManager}.
     * I/O errors are logged but not re-thrown so that a WAL write failure
     * never disrupts the NIO event loop.
     *
     * @param roomId the room whose WAL receives the record
     * @param msg    the message to persist
     */
    public void appendToWal(String roomId, Message msg) {
        if (walManager == null) return;
        try {
            walManager.append(roomId, msg);
        } catch (IOException e) {
            log.error("WAL append failed  room='{}': {}", roomId, e.getMessage(), e);
        }
    }

    // =========================================================================
    // Package-private hooks for StorageLifecycleManager
    // =========================================================================

    /**
     * Removes the room from the routing table.  Called by
     * {@link StorageLifecycleManager} when a room is GC-eligible.
     */
    void removeRoom(String roomId) {
        rooms.remove(roomId);
        log.info("Room evicted by GC  roomId='{}'", roomId);
        notifyLobbySubscribers();
    }

    /**
     * Exposes the room map for iteration by the lifecycle manager.
     * The caller must not modify the returned map.
     */
    ConcurrentHashMap<String, RoomContext> getRooms() {
        return rooms;
    }
}
