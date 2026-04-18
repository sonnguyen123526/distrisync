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
 * Per-room runtime context: workspace boards (each with authoritative canvas state) + active-key table.
 *
 * <p>Board state is created lazily via {@link #getBoard(String)}; each new board replays
 * {@link WalManager#recover(String, String)} for that room/board pair when a {@link WalManager} is configured.
 *
 * <p>{@link #lastActivityTimestamp} is refreshed on {@link #addKey} and {@link #touchActivity()}.
 */
final class RoomContext {

    private static final Logger log = LoggerFactory.getLogger(RoomContext.class);

    final String roomId;

    private final WalManager wal;

    private final ConcurrentHashMap<String, CanvasStateManager> boards = new ConcurrentHashMap<>();

    private final Set<SelectionKey> activeKeys = ConcurrentHashMap.newKeySet();

    private volatile long lastActivityTimestamp = System.currentTimeMillis();

    RoomContext(String roomId, WalManager wal) {
        if (roomId == null) throw new IllegalArgumentException("roomId must not be null");
        this.roomId = roomId;
        this.wal   = wal;
    }

    /**
     * Returns the {@link CanvasStateManager} for {@code boardId}, creating it if needed and
     * replaying the WAL for this room/board when {@link WalManager} is non-null.
     */
    CanvasStateManager getBoard(String boardId) {
        if (boardId == null || boardId.isBlank()) {
            throw new IllegalArgumentException("boardId must not be null or blank");
        }
        return boards.computeIfAbsent(boardId, id -> {
            CanvasStateManager csm = new CanvasStateManager();
            replayWalForBoard(id, csm);
            return csm;
        });
    }

    /**
     * Snapshot of workspace board ids that currently have a {@link CanvasStateManager}
     * (including empty boards created via {@link #getBoard(String)}).
     */
    Set<String> getActiveBoardIds() {
        return Set.copyOf(boards.keySet());
    }

    void addKey(SelectionKey key) {
        activeKeys.add(key);
        touchActivity();
    }

    boolean removeKey(SelectionKey key) {
        return activeKeys.remove(key);
    }

    Set<SelectionKey> getActiveKeys() {
        return Collections.unmodifiableSet(activeKeys);
    }

    /**
     * Live backing set for selector-thread read-only iteration (no per-call unmodifiable wrapper).
     * Do not mutate membership except via {@link #addKey} / {@link #removeKey}.
     */
    Set<SelectionKey> activeKeysForSelectorIteration() {
        return activeKeys;
    }

    int getActiveClientCount() {
        return activeKeys.size();
    }

    void touchActivity() {
        lastActivityTimestamp = System.currentTimeMillis();
    }

    long getLastActivityTimestamp() {
        return lastActivityTimestamp;
    }

    private void replayWalForBoard(String boardId, CanvasStateManager stateManager) {
        if (wal == null) return;

        long t0 = System.nanoTime();
        List<Message> frames;
        try {
            frames = wal.recover(roomId, boardId);
        } catch (IOException e) {
            log.error("WAL replay failed  room='{}' board='{}': {}", roomId, boardId, e.getMessage(), e);
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
                        log.warn("WAL replay: skipping malformed MUTATION  room='{}' board='{}': {}",
                                roomId, boardId, e.getMessage());
                    }
                }
                case SHAPE_DELETE -> {
                    try {
                        JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
                        UUID shapeId = UUID.fromString(p.get("shapeId").getAsString());
                        if (stateManager.deleteShape(shapeId)) applied++;
                    } catch (Exception e) {
                        log.warn("WAL replay: skipping malformed SHAPE_DELETE  room='{}' board='{}': {}",
                                roomId, boardId, e.getMessage());
                    }
                }
                case CLEAR_USER_SHAPES -> {
                    try {
                        String clientId = MessageCodec.decodeClearUserShapes(msg);
                        stateManager.clearUserShapes(clientId);
                        applied++;
                    } catch (Exception e) {
                        log.warn("WAL replay: skipping malformed CLEAR_USER_SHAPES  room='{}' board='{}': {}",
                                roomId, boardId, e.getMessage());
                    }
                }
                default -> { /* non-durable */ }
            }
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("[WAL] Replayed {} frames for {}_{} in {}ms. Canvas restored.",
                frames.size(), roomId, boardId, elapsedMs);
    }
}
