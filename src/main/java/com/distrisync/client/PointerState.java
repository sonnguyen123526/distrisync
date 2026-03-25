package com.distrisync.client;

/**
 * Immutable snapshot of a single remote user's pointer position.
 *
 * <p>Stored as values in the {@link PointerStateManager}'s tracker map.  A new
 * instance is produced on every {@link PointerStateManager#updatePointer} call
 * so the manager's eviction logic always sees a consistent
 * {@code lastUpdatedAt} without requiring any synchronisation beyond the
 * {@link java.util.concurrent.ConcurrentHashMap} that owns the reference.
 *
 * @param clientId      stable short identifier broadcast in every UDP_POINTER packet
 * @param x             horizontal canvas coordinate in logical pixels
 * @param y             vertical canvas coordinate in logical pixels
 * @param lastUpdatedAt epoch-millisecond timestamp supplied by the manager's clock;
 *                      used to decide when this entry is stale and should be evicted
 */
public record PointerState(String clientId, double x, double y, long lastUpdatedAt) {

    public PointerState {
        if (clientId == null || clientId.isBlank())
            throw new IllegalArgumentException("clientId must not be blank");
    }
}
