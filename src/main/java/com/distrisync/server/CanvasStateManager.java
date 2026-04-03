package com.distrisync.server;

import com.distrisync.model.Shape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store of the authoritative canvas state.
 *
 * <p>Conflict resolution follows strict last-writer-wins by Lamport timestamp:
 * an incoming shape replaces the stored version <em>only</em> when its
 * {@link Shape#timestamp()} is strictly greater than the currently stored value.
 * Ties are resolved in favour of the existing entry (idempotent re-delivery
 * of the same mutation is therefore a no-op).
 */
public final class CanvasStateManager {

    private static final Logger log = LoggerFactory.getLogger(CanvasStateManager.class);

    private final ConcurrentHashMap<UUID, Shape> shapeMap = new ConcurrentHashMap<>();

    /**
     * Applies a mutation using last-writer-wins conflict resolution.
     *
     * <p>Uses {@link ConcurrentHashMap#compute} to perform the compare-and-swap
     * atomically: no external synchronisation is required.
     *
     * @param incoming the shape to apply
     * @return {@code true} if the shape was stored; {@code false} if the existing
     *         entry had an equal or newer timestamp (mutation rejected)
     */
    public boolean applyMutation(Shape incoming) {
        final boolean[] applied = {false};

        shapeMap.compute(incoming.objectId(), (id, existing) -> {
            if (existing == null || incoming.timestamp() > existing.timestamp()) {
                applied[0] = true;
                return incoming;
            }
            return existing;
        });

        if (applied[0]) {
            log.debug("Applied mutation  type={} id={} ts={}",
                    incoming.getClass().getSimpleName(), incoming.objectId(), incoming.timestamp());
        } else {
            Shape stored = shapeMap.get(incoming.objectId());
            long storedTs = stored != null ? stored.timestamp() : -1L;
            log.debug("Rejected mutation (stale) id={} incomingTs={} storedTs={}",
                    incoming.objectId(), incoming.timestamp(), storedTs);
        }

        return applied[0];
    }

    /**
     * Atomically removes a shape by its {@link UUID}.
     *
     * @param id the objectId of the shape to delete
     * @return {@code true} if the shape was present and has been removed;
     *         {@code false} if no shape with that id existed (UNDO_REQUEST
     *         should be silently ignored in that case)
     */
    public boolean deleteShape(UUID id) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        boolean removed = shapeMap.remove(id) != null;
        if (removed) {
            log.debug("Shape deleted id={}", id);
        } else {
            log.debug("deleteShape no-op — id={} not found", id);
        }
        return removed;
    }

    /**
     * Removes every shape from the store, atomically from the perspective of
     * any single reader.  Any snapshot taken after this call returns an empty
     * list.
     */
    public void clearAll() {
        int count = shapeMap.size();
        shapeMap.clear();
        log.info("Canvas cleared — {} shape(s) removed", count);
    }

    /**
     * Removes all shapes whose {@link Shape#clientId()} matches
     * {@code targetClientId}.
     *
     * <p>Uses {@link ConcurrentHashMap#values()}{@code .removeIf} which
     * acquires per-segment locks internally, making the operation safe to call
     * from any thread without external synchronisation.
     *
     * @param targetClientId the session-scoped client identifier whose shapes
     *                       should be purged; must not be {@code null}
     */
    public void clearUserShapes(String targetClientId) {
        if (targetClientId == null) throw new IllegalArgumentException("targetClientId must not be null");
        int before = shapeMap.size();
        shapeMap.values().removeIf(shape -> shape.clientId().equals(targetClientId));
        int removed = before - shapeMap.size();
        log.info("clearUserShapes clientId='{}' — {} shape(s) removed", targetClientId, removed);
    }

    /**
     * Returns a point-in-time snapshot of all shapes as an immutable list.
     * Safe to call from any thread; the list is a defensive copy so subsequent
     * mutations do not affect it.
     */
    public List<Shape> snapshot() {
        return List.copyOf(shapeMap.values());
    }

    /** Number of distinct shapes currently held. */
    public int size() {
        return shapeMap.size();
    }
}
