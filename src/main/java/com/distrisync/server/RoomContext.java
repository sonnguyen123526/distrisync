package com.distrisync.server;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-room runtime context: authoritative canvas state + active-key routing table.
 *
 * <h2>WAL replay on construction</h2>
 * When a {@link WalManager} is supplied the constructor immediately replays
 * every persisted frame into the blank {@link CanvasStateManager}.  Only
 * {@code MUTATION}, {@code SHAPE_DELETE}, and {@code CLEAR_USER_SHAPES} frames
 * alter canvas state; all other frame types (e.g. {@code SHAPE_COMMIT}) are
 * silently skipped — they carry no durable state.
 *
 * <h2>Activity timestamp</h2>
 * {@link #lastActivityTimestamp} is refreshed on every {@link #addKey} and
 * explicit {@link #touchActivity()} call.  {@link StorageLifecycleManager}
 * reads this via {@link #getLastActivityTimestamp()} to decide whether an
 * idle room has aged past the GC TTL.
 *
 * <h2>Thread safety</h2>
 * The active-key set is backed by {@link ConcurrentHashMap#newKeySet()}.
 * {@code lastActivityTimestamp} is {@code volatile}.  {@link CanvasStateManager}
 * is independently thread-safe.
 */
final class RoomContext {

    private static final Logger log = LoggerFactory.getLogger(RoomContext.class);

    /** Stable room identifier; written once at construction. */
    final String roomId;

    /** Authoritative in-memory canvas; replayed from WAL on construction. */
    final CanvasStateManager stateManager;

    /** Keys of currently connected clients routing to this room. */
    private final Set<SelectionKey> activeKeys = ConcurrentHashMap.newKeySet();

    /**
     * Wall-clock milliseconds of the most recent client activity for this room.
     *
     * <p>Marked {@code volatile} for visibility across the NIO loop and the
     * lifecycle daemon.
     *
     * <p><strong>Test note:</strong> integration tests back-date this field via
     * reflection ({@code getDeclaredField("lastActivityTimestamp")}) to simulate
     * an idle room without real-time waiting.  The field name must not be renamed
     * without updating those tests.
     */
    private volatile long lastActivityTimestamp = System.currentTimeMillis();

    /**
     * Creates a new context and replays any persisted WAL for this room.
     *
     * @param roomId the stable room identifier; must not be {@code null}
     * @param wal    WAL engine to replay from; {@code null} means no persistence
     */
    RoomContext(String roomId, WalManager wal) {
        if (roomId == null) throw new IllegalArgumentException("roomId must not be null");
        this.roomId       = roomId;
        this.stateManager = new CanvasStateManager();
        replayWal(wal);
    }

    // =========================================================================
    // Key management
    // =========================================================================

    /**
     * Registers a client {@link SelectionKey} and refreshes the activity timestamp.
     */
    void addKey(SelectionKey key) {
        activeKeys.add(key);
        touchActivity();
    }

    /**
     * Removes a client {@link SelectionKey} on disconnect.
     *
     * @return {@code true} if the key was present and removed
     */
    boolean removeKey(SelectionKey key) {
        return activeKeys.remove(key);
    }

    /**
     * Returns an unmodifiable view of the active-key set.
     */
    Set<SelectionKey> getActiveKeys() {
        return Collections.unmodifiableSet(activeKeys);
    }

    /** Number of currently connected clients in this room. */
    int getActiveClientCount() {
        return activeKeys.size();
    }

    // =========================================================================
    // Activity tracking
    // =========================================================================

    /** Updates the activity timestamp to the current wall clock. */
    void touchActivity() {
        lastActivityTimestamp = System.currentTimeMillis();
    }

    /** Returns the last-activity timestamp (ms). Package-private for GC. */
    long getLastActivityTimestamp() {
        return lastActivityTimestamp;
    }

    // =========================================================================
    // WAL replay
    // =========================================================================

    private void replayWal(WalManager wal) {
        if (wal == null) return;

        List<Message> frames;
        try {
            frames = wal.recover(roomId);
        } catch (IOException e) {
            log.error("WAL replay failed  room='{}': {}", roomId, e.getMessage(), e);
            return;
        }

        int applied = 0;
        for (Message msg : frames) {
            switch (msg.type()) {
                case MUTATION -> {
                    try {
                        if (stateManager.applyMutation(ShapeCodec.decodeMutation(msg.payload()))) {
                            applied++;
                        }
                    } catch (Exception e) {
                        log.warn("WAL replay: skipping malformed MUTATION  room='{}': {}",
                                roomId, e.getMessage());
                    }
                }
                case SHAPE_DELETE -> {
                    try {
                        JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
                        UUID shapeId = UUID.fromString(p.get("shapeId").getAsString());
                        if (stateManager.deleteShape(shapeId)) applied++;
                    } catch (Exception e) {
                        log.warn("WAL replay: skipping malformed SHAPE_DELETE  room='{}': {}",
                                roomId, e.getMessage());
                    }
                }
                case CLEAR_USER_SHAPES -> {
                    try {
                        String clientId = MessageCodec.decodeClearUserShapes(msg);
                        stateManager.clearUserShapes(clientId);
                        applied++;
                    } catch (Exception e) {
                        log.warn("WAL replay: skipping malformed CLEAR_USER_SHAPES  room='{}': {}",
                                roomId, e.getMessage());
                    }
                }
                default -> { /* SHAPE_COMMIT and others carry no durable canvas state */ }
            }
        }

        log.info("WAL replay complete  room='{}' framesRead={} framesApplied={} shapes={}",
                roomId, frames.size(), applied, stateManager.size());
    }
}
