package com.distrisync.server;

import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central routing registry that maps room identifiers to their isolated
 * {@link RoomContext}s.
 *
 * <h2>Lifecycle</h2>
 * Rooms are created lazily on first join and currently persist for the
 * lifetime of the server.  A room whose last client disconnects becomes
 * quiescent: its {@link CanvasStateManager} retains the canvas state so
 * that rejoining clients receive the same snapshot they left behind.
 *
 * <h2>WAL integration</h2>
 * Each {@code RoomManager} is constructed with an optional {@link WalManager}.
 * When provided:
 * <ul>
 *   <li>New {@link RoomContext}s are initialised via
 *       {@link WalManager#recover(String)} so that a restarted server
 *       immediately reconstructs canvas state from the durable log.</li>
 *   <li>Callers use {@link #appendToWal(String, Message)} to persist every
 *       accepted state-mutating message before broadcasting it.</li>
 * </ul>
 * Passing {@code null} for {@code walManager} disables persistence entirely
 * (useful in tests and ephemeral deployments).
 *
 * <h2>Thread safety</h2>
 * All public methods delegate to a {@link ConcurrentHashMap} and are safe
 * to call concurrently from the NIO event loop thread and any admin thread.
 */
public final class RoomManager {

    private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

    private final ConcurrentHashMap<String, RoomContext> rooms = new ConcurrentHashMap<>();

    /** May be {@code null} when WAL persistence is disabled. */
    private final WalManager walManager;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code RoomManager} backed by the supplied {@link WalManager}.
     *
     * @param walManager the WAL engine used for both recovery and appending;
     *                   {@code null} disables persistence
     */
    public RoomManager(WalManager walManager) {
        this.walManager = walManager;
    }

    /**
     * Convenience constructor for deployments that do not require persistence.
     * Equivalent to {@code new RoomManager(null)}.
     */
    public RoomManager() {
        this(null);
    }

    // -------------------------------------------------------------------------
    // Room access
    // -------------------------------------------------------------------------

    /**
     * Returns the existing {@link RoomContext} for {@code roomId}, or atomically
     * creates and registers a new one if none exists yet.
     *
     * <p>When a new room is created and a {@link WalManager} is present, the
     * room's {@link RoomContext} constructor immediately replays any persisted
     * WAL records so that the first joining client receives a fully recovered
     * {@code SNAPSHOT}.
     *
     * @param roomId the room identifier; must not be {@code null} or blank
     * @return the (possibly newly created) {@link RoomContext}
     */
    public RoomContext getOrCreateRoom(String roomId) {
        if (roomId == null || roomId.isBlank())
            throw new IllegalArgumentException("roomId must not be blank");
        return rooms.computeIfAbsent(roomId, id -> {
            log.info("Room created  roomId='{}'", id);
            return new RoomContext(id, walManager);
        });
    }

    /**
     * Returns the {@link RoomContext} for {@code roomId}, or {@code null} if
     * no such room has been created yet.
     *
     * @param roomId the room identifier
     * @return the {@link RoomContext}, or {@code null}
     */
    public RoomContext getRoom(String roomId) {
        return roomId != null ? rooms.get(roomId) : null;
    }

    /**
     * Returns a point-in-time snapshot of all shapes in the named room.
     * Returns an empty list if the room does not exist.
     *
     * @param roomId the room identifier
     * @return an immutable snapshot of the room's current shapes
     */
    public List<Shape> getRoomSnapshot(String roomId) {
        RoomContext ctx = getRoom(roomId);
        return ctx != null ? ctx.stateManager.snapshot() : Collections.emptyList();
    }

    /**
     * Removes {@code key} from the active-key set of the named room.
     * A no-op if the room does not exist or the key was not registered.
     *
     * @param roomId the room identifier the disconnecting session belonged to
     * @param key    the {@link SelectionKey} being cancelled
     */
    public void removeClientFromRoom(String roomId, SelectionKey key) {
        if (roomId == null || roomId.isBlank()) return;
        RoomContext ctx = rooms.get(roomId);
        if (ctx != null) {
            ctx.removeKey(key);
            log.debug("Client removed from room  roomId='{}' key={}", roomId, key);
        }
    }

    // -------------------------------------------------------------------------
    // WAL append
    // -------------------------------------------------------------------------

    /**
     * Appends a state-mutating {@link Message} to the room's WAL file.
     *
     * <p>Should be called by {@code NioServer} immediately <em>before</em>
     * broadcasting any accepted mutation so that the WAL record is written
     * even if the broadcast subsequently fails.
     *
     * <p>If no {@link WalManager} was supplied at construction time, this
     * method is a no-op.  Any {@link IOException} from the underlying
     * {@link FileChannel} write is logged at {@code WARN} level rather than
     * propagated; the mutation has already been applied in memory and dropping
     * a single WAL record degrades durability but does not break the live
     * session.
     *
     * @param roomId  the room to which the message belongs
     * @param message the accepted, state-mutating message to persist
     */
    public void appendToWal(String roomId, Message message) {
        if (walManager == null) return;
        try {
            walManager.append(roomId, message);
        } catch (IOException e) {
            log.warn("WAL append failed  roomId='{}' type={} — durability degraded: {}",
                    roomId, message.type(), e.getMessage());
        }
    }
}
