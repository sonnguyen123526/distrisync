package com.distrisync.server;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SelectionKey;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated state container for a single collaborative room.
 *
 * <p>Each room owns:
 * <ul>
 *   <li>A dedicated {@link CanvasStateManager} — mutations in one room never
 *       affect any other room's canvas.</li>
 *   <li>A {@link ConcurrentHashMap}-backed set of the {@link SelectionKey}s
 *       currently registered in this room — used by the routing engine to
 *       scope broadcasts to only the relevant connections.</li>
 * </ul>
 *
 * <h2>WAL recovery</h2>
 * When constructed with a non-{@code null} {@link WalManager}, the constructor
 * immediately calls {@link WalManager#recover(String)} and replays every
 * persisted state-mutating message ({@code MUTATION}, {@code SHAPE_DELETE},
 * {@code CLEAR_USER_SHAPES}) into {@link #stateManager} <em>before</em> any
 * client is allowed to join.  This ensures a server restart delivers a fully
 * recovered {@code SNAPSHOT} to reconnecting peers.
 *
 * <p>Instances are created lazily by {@link RoomManager#getOrCreateRoom} and
 * live for the duration of the server process.  There is currently no
 * garbage-collection of empty rooms; a room that loses all its clients simply
 * becomes quiescent until new clients join.
 */
final class RoomContext {

    private static final Logger log = LoggerFactory.getLogger(RoomContext.class);

    final String roomId;

    /** Authoritative canvas state for this room. */
    final CanvasStateManager stateManager;

    /**
     * Set of active {@link SelectionKey}s whose sessions have joined this room.
     * Uses a {@link ConcurrentHashMap} as the backing store so that add/remove
     * and iteration are all safe without external locking.
     */
    private final Set<SelectionKey> activeKeys =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // -------------------------------------------------------------------------
    // Construction + WAL recovery
    // -------------------------------------------------------------------------

    /**
     * Creates a room with WAL recovery.
     *
     * <p>If {@code walManager} is non-{@code null} and a WAL file exists for
     * {@code roomId}, its records are replayed into a freshly created
     * {@link CanvasStateManager} before this constructor returns.  The room is
     * then ready to serve a consistent {@code SNAPSHOT} to the first client.
     *
     * @param roomId     the room identifier; must not be {@code null} or blank
     * @param walManager the WAL engine used for recovery; may be {@code null}
     *                   (disables recovery, useful in tests)
     * @throws UncheckedIOException if WAL recovery fails due to an I/O error
     */
    RoomContext(String roomId, WalManager walManager) {
        if (roomId == null || roomId.isBlank())
            throw new IllegalArgumentException("roomId must not be blank");

        this.roomId       = roomId;
        this.stateManager = new CanvasStateManager();

        if (walManager != null) {
            replayWal(walManager);
        }
    }

    /**
     * Reads all persisted frames from the WAL and applies them to
     * {@link #stateManager} in order.
     *
     * <p>Only state-mutating frame types are replayed:
     * <ul>
     *   <li>{@code MUTATION} — shape add / update via
     *       {@link CanvasStateManager#applyMutation}</li>
     *   <li>{@code SHAPE_DELETE} — shape removal via
     *       {@link CanvasStateManager#deleteShape}</li>
     *   <li>{@code CLEAR_USER_SHAPES} — bulk per-user removal via
     *       {@link CanvasStateManager#clearUserShapes}</li>
     * </ul>
     * All other frame types are skipped (they were never persisted to the WAL).
     */
    private void replayWal(WalManager walManager) {
        List<Message> records;
        try {
            records = walManager.recover(roomId);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "WAL recovery failed for roomId='" + roomId + "'", e);
        }

        if (records.isEmpty()) {
            return;
        }

        log.info("Replaying {} WAL record(s) for roomId='{}'", records.size(), roomId);
        int replayed = 0;

        for (Message msg : records) {
            try {
                switch (msg.type()) {
                    case MUTATION -> {
                        var shape = ShapeCodec.decodeMutation(msg.payload());
                        stateManager.applyMutation(shape);
                        replayed++;
                    }
                    case SHAPE_DELETE -> {
                        // Payload: {"shapeId":"<uuid>"}
                        JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
                        UUID shapeId = UUID.fromString(p.get("shapeId").getAsString());
                        stateManager.deleteShape(shapeId);
                        replayed++;
                    }
                    case CLEAR_USER_SHAPES -> {
                        String clientId = MessageCodec.decodeClearUserShapes(msg);
                        stateManager.clearUserShapes(clientId);
                        replayed++;
                    }
                    default -> log.warn("WAL replay: unexpected frame type={} in WAL for roomId='{}' — skipping",
                            msg.type(), roomId);
                }
            } catch (Exception e) {
                log.error("WAL replay: failed to apply frame type={} roomId='{}' — skipping record: {}",
                        msg.type(), roomId, e.getMessage());
            }
        }

        log.info("WAL replay complete  roomId='{}' applied={} total={} shapesAfterReplay={}",
                roomId, replayed, records.size(), stateManager.size());
    }

    // -------------------------------------------------------------------------
    // Key management
    // -------------------------------------------------------------------------

    void addKey(SelectionKey key) {
        activeKeys.add(key);
    }

    void removeKey(SelectionKey key) {
        activeKeys.remove(key);
    }

    /**
     * Returns an unmodifiable snapshot-view of the currently active keys.
     * The underlying set is live; callers should not hold this reference
     * across potential mutations (add/remove) unless they require the
     * point-in-time snapshot semantics from {@link Set#copyOf}.
     */
    Set<SelectionKey> getActiveKeys() {
        return Collections.unmodifiableSet(activeKeys);
    }
}
