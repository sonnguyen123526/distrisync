package com.distrisync.client;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Thread-safe registry of active remote-user pointer positions.
 *
 * <h2>Responsibilities</h2>
 * <ol>
 *   <li><b>Upsert</b> — {@link #updatePointer} records or refreshes a client's
 *       (x, y) position and stamps the current time from the injected clock.</li>
 *   <li><b>Eviction</b> — {@link #evictStalePointers} removes any entry whose
 *       {@link PointerState#lastUpdatedAt()} is more than {@code staleThresholdMs}
 *       milliseconds behind the current clock reading.  The caller decides when
 *       to invoke this; typical callers are the JavaFX {@code AnimationTimer}
 *       render loop or a scheduled background task.</li>
 * </ol>
 *
 * <h2>Clock injection</h2>
 * All time readings go through a {@link LongSupplier} that returns milliseconds.
 * The production default is {@code System::currentTimeMillis}.  Tests supply an
 * {@link java.util.concurrent.atomic.AtomicLong} to manipulate perceived time
 * without sleeping, keeping every test instant and deterministic.
 *
 * <h2>Thread safety</h2>
 * The internal {@link ConcurrentHashMap} allows concurrent readers and a single
 * eviction writer without any external locking.  {@link #updatePointer} and
 * {@link #evictStalePointers} are safe to call from different threads
 * simultaneously.
 */
public final class PointerStateManager {

    /** Default eviction threshold: 500 ms matches the UdpPointerTracker contract. */
    public static final long DEFAULT_STALE_THRESHOLD_MS = 500L;

    private final ConcurrentHashMap<String, PointerState> pointers = new ConcurrentHashMap<>();

    /**
     * Milliseconds after which an entry is considered stale.
     * An entry is evicted when {@code clock.getAsLong() - state.lastUpdatedAt() > staleThresholdMs}.
     */
    private final long staleThresholdMs;

    /** Pluggable time source; defaults to {@link System#currentTimeMillis}. */
    private final LongSupplier clock;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Production constructor — uses wall-clock time and the default 500 ms threshold.
     */
    public PointerStateManager() {
        this(DEFAULT_STALE_THRESHOLD_MS, System::currentTimeMillis);
    }

    /**
     * Full constructor for testing and non-default deployments.
     *
     * @param staleThresholdMs milliseconds after which an un-refreshed pointer is stale;
     *                         must be positive
     * @param clock            time source returning epoch-milliseconds; must not be {@code null}
     */
    public PointerStateManager(long staleThresholdMs, LongSupplier clock) {
        if (staleThresholdMs <= 0)
            throw new IllegalArgumentException("staleThresholdMs must be positive");
        if (clock == null)
            throw new IllegalArgumentException("clock must not be null");
        this.staleThresholdMs = staleThresholdMs;
        this.clock            = clock;
    }

    // =========================================================================
    // Mutation
    // =========================================================================

    /**
     * Creates a new {@link PointerState} for {@code clientId} if one does not
     * yet exist, or replaces the existing entry if it does.  The
     * {@link PointerState#lastUpdatedAt()} of the stored record is set to the
     * current clock reading at the moment this method is called.
     *
     * <p>Safe to call from any thread.
     *
     * @param clientId the stable short identifier of the remote user; must not be blank
     * @param x        horizontal canvas coordinate in logical pixels
     * @param y        vertical canvas coordinate in logical pixels
     */
    public void updatePointer(String clientId, double x, double y) {
        long now = clock.getAsLong();
        pointers.put(clientId, new PointerState(clientId, x, y, now));
    }

    // =========================================================================
    // Eviction
    // =========================================================================

    /**
     * Scans every tracked pointer and removes entries whose
     * {@link PointerState#lastUpdatedAt()} is more than {@link #staleThresholdMs}
     * milliseconds behind the current clock reading.
     *
     * <p>An entry at <em>exactly</em> the threshold boundary is considered fresh
     * and is not evicted ({@code elapsed > threshold} evicts, not {@code >=}).
     * This prevents a pointer that was just updated from immediately disappearing
     * if the eviction and the update happen to read the same clock value.
     *
     * <p>Safe to call from any thread, including the JavaFX Application Thread.
     */
    public void evictStalePointers() {
        long now = clock.getAsLong();
        pointers.values().removeIf(state -> now - state.lastUpdatedAt() > staleThresholdMs);
    }

    // =========================================================================
    // Queries
    // =========================================================================

    /**
     * Returns the {@link PointerState} for the given client ID, or
     * {@link Optional#empty()} if no entry exists (or it was evicted).
     *
     * @param clientId the client identifier to look up
     * @return an {@link Optional} wrapping the current state, never {@code null}
     */
    public Optional<PointerState> getPointer(String clientId) {
        return Optional.ofNullable(pointers.get(clientId));
    }

    /**
     * Returns a live, unmodifiable view of the entire pointer map.
     * The view reflects subsequent {@link #updatePointer} and
     * {@link #evictStalePointers} calls without requiring a new call to this method.
     *
     * @return an unmodifiable {@link Map} of clientId → {@link PointerState}
     */
    public Map<String, PointerState> getPointers() {
        return Collections.unmodifiableMap(pointers);
    }

    /**
     * Returns all currently tracked {@link PointerState} values as an unordered
     * collection.  The collection is a <em>snapshot</em> view backed by the live map.
     *
     * @return collection of active pointer states
     */
    public Collection<PointerState> activePointers() {
        return Collections.unmodifiableCollection(pointers.values());
    }

    /**
     * Returns the number of active (non-evicted) tracked pointers.
     *
     * @return current map size; {@code 0} when no remote users are tracked
     */
    public int size() {
        return pointers.size();
    }
}
