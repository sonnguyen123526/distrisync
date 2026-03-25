package com.distrisync.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link PointerStateManager}, exercising pointer upsert
 * semantics and stale-entry eviction without touching any real network socket
 * or JavaFX Platform thread.
 *
 * <h2>Testability strategy</h2>
 * {@link PointerStateManager} accepts a {@link java.util.function.LongSupplier}
 * clock in its constructor.  All tests inject an {@link AtomicLong} whose value
 * can be advanced instantaneously to simulate the passage of time.  This
 * eliminates {@code Thread.sleep()} calls, removes flakiness caused by scheduler
 * jitter, and keeps each test sub-millisecond regardless of host load.
 *
 * <h2>No mocking frameworks</h2>
 * The clock contract is a single-method functional interface, so a plain
 * {@code AtomicLong::get} lambda is sufficient — no Mockito or PowerMock
 * is required.
 */
@DisplayName("PointerStateManager")
class PointerStateTrackerTest {

    private static final long THRESHOLD_MS = 500L;

    // =========================================================================
    // Factory helpers
    // =========================================================================

    /** Creates a fresh controllable clock seeded at the given millisecond value. */
    private static AtomicLong clockAt(long epochMs) {
        return new AtomicLong(epochMs);
    }

    /** Creates the system under test bound to the given clock. */
    private static PointerStateManager managerWith(AtomicLong clock) {
        return new PointerStateManager(THRESHOLD_MS, clock::get);
    }

    // =========================================================================
    // updatePointer()
    // =========================================================================

    /**
     * Verifies that {@link PointerStateManager#updatePointer} stores correct
     * coordinates and stamps the timestamp from the injected clock.
     *
     * <h3>Scenario</h3>
     * <ol>
     *   <li>Clock reads T=1 000 ms.</li>
     *   <li>{@code updatePointer("client-1", 42.5, 99.0)} is called once.</li>
     *   <li>The map must contain exactly one entry whose {@code x}, {@code y},
     *       {@code clientId}, and {@code lastUpdatedAt} all match the supplied
     *       values.</li>
     * </ol>
     */
    @Test
    @DisplayName("testPointerUpdate: creates entry with correct coordinates and clock-stamped timestamp")
    void testPointerUpdate() {
        AtomicLong clock = clockAt(1_000L);
        PointerStateManager sut = managerWith(clock);

        // -----------------------------------------------------------------
        // 1. Issue a single updatePointer call while the clock reads T=1000
        // -----------------------------------------------------------------
        sut.updatePointer("client-1", 42.5, 99.0);

        // -----------------------------------------------------------------
        // 2. Exactly one entry must exist in the tracker
        // -----------------------------------------------------------------
        assertThat(sut.size())
                .as("tracker must contain exactly one entry after a single updatePointer call")
                .isEqualTo(1);

        // -----------------------------------------------------------------
        // 3. The stored state must reflect the supplied values and clock
        // -----------------------------------------------------------------
        assertThat(sut.getPointer("client-1"))
                .as("getPointer must return a present Optional for the just-updated client")
                .isPresent()
                .hasValueSatisfying(state -> {

                    assertThat(state.clientId())
                            .as("clientId must match the argument passed to updatePointer")
                            .isEqualTo("client-1");

                    assertThat(state.x())
                            .as("x coordinate must match the argument passed to updatePointer")
                            .isEqualTo(42.5);

                    assertThat(state.y())
                            .as("y coordinate must match the argument passed to updatePointer")
                            .isEqualTo(99.0);

                    assertThat(state.lastUpdatedAt())
                            .as("lastUpdatedAt must equal the clock reading at the time of the call (T=1000)")
                            .isEqualTo(1_000L);
                });
    }

    /**
     * Verifies that a second {@code updatePointer} call for the same client ID
     * replaces the stored coordinates AND refreshes the timestamp — so the
     * eviction timer is reset — while keeping the map size at exactly one.
     *
     * <h3>Scenario</h3>
     * <ol>
     *   <li>Clock at T=1 000: insert client-1 at (10, 20).</li>
     *   <li>Advance clock to T=1 200 (200 ms later).</li>
     *   <li>Update client-1 to (55, 77).</li>
     *   <li>Assert new coordinates are stored; {@code lastUpdatedAt} is T=1 200;
     *       map size is still 1 (no duplicate entry created).</li>
     * </ol>
     */
    @Test
    @DisplayName("testPointerUpdate: second call refreshes coordinates and resets timestamp for same clientId")
    void testPointerUpdateRefreshesExistingEntry() {
        AtomicLong clock = clockAt(1_000L);
        PointerStateManager sut = managerWith(clock);

        // -----------------------------------------------------------------
        // 1. Initial insertion at T=1000
        // -----------------------------------------------------------------
        sut.updatePointer("client-1", 10.0, 20.0);

        // -----------------------------------------------------------------
        // 2. Advance the clock by 200 ms and issue a second updatePointer
        // -----------------------------------------------------------------
        clock.set(1_200L);
        sut.updatePointer("client-1", 55.0, 77.0);

        // -----------------------------------------------------------------
        // 3. Verify coordinates and timestamp were refreshed; no duplicate
        // -----------------------------------------------------------------
        assertThat(sut.size())
                .as("map size must remain 1 — a second call must update, not duplicate")
                .isOne();

        assertThat(sut.getPointer("client-1"))
                .isPresent()
                .hasValueSatisfying(state -> {

                    assertThat(state.x())
                            .as("x must be overwritten to the value from the second call (55.0)")
                            .isEqualTo(55.0);

                    assertThat(state.y())
                            .as("y must be overwritten to the value from the second call (77.0)")
                            .isEqualTo(77.0);

                    assertThat(state.lastUpdatedAt())
                            .as("lastUpdatedAt must be refreshed to the clock reading at the second call (T=1200)")
                            .isEqualTo(1_200L);
                });
    }

    /**
     * Verifies that distinct client IDs produce independent entries and never
     * overwrite each other.
     */
    @Test
    @DisplayName("testPointerUpdate: independent entries created for distinct client IDs")
    void testPointerUpdateMultipleClients() {
        AtomicLong clock = clockAt(500L);
        PointerStateManager sut = managerWith(clock);

        sut.updatePointer("alice", 100.0, 200.0);
        sut.updatePointer("bob",   300.0, 400.0);

        assertThat(sut.size())
                .as("two distinct client IDs must produce two separate map entries")
                .isEqualTo(2);

        assertThat(sut.getPointer("alice"))
                .isPresent()
                .hasValueSatisfying(s -> {
                    assertThat(s.x()).isEqualTo(100.0);
                    assertThat(s.y()).isEqualTo(200.0);
                });

        assertThat(sut.getPointer("bob"))
                .isPresent()
                .hasValueSatisfying(s -> {
                    assertThat(s.x()).isEqualTo(300.0);
                    assertThat(s.y()).isEqualTo(400.0);
                });
    }

    // =========================================================================
    // evictStalePointers()
    // =========================================================================

    /**
     * Verifies that a pointer last updated more than the 500 ms threshold ago
     * is removed by {@link PointerStateManager#evictStalePointers()}.
     *
     * <h3>Scenario</h3>
     * <ol>
     *   <li>Clock at T=0: insert pointer for Client A.</li>
     *   <li>Advance clock to T=600 ms (600 > 500 — clearly exceeds the threshold).</li>
     *   <li>Call {@code evictStalePointers()}.</li>
     *   <li>Assert Client A is no longer in the tracker and the map is empty.</li>
     * </ol>
     */
    @Test
    @DisplayName("testStalePointerEviction: client exceeding 500ms timeout is removed from the tracker")
    void testStalePointerEviction() {
        AtomicLong clock = clockAt(0L);
        PointerStateManager sut = managerWith(clock);

        // -----------------------------------------------------------------
        // 1. Register Client A at T=0
        // -----------------------------------------------------------------
        sut.updatePointer("client-A", 10.0, 20.0);

        assertThat(sut.size())
                .as("Client A must be registered before the clock is advanced")
                .isOne();

        // -----------------------------------------------------------------
        // 2. Simulate 600 ms passing (600 > 500ms threshold → stale)
        // -----------------------------------------------------------------
        clock.set(600L);

        // -----------------------------------------------------------------
        // 3. Trigger eviction
        // -----------------------------------------------------------------
        sut.evictStalePointers();

        // -----------------------------------------------------------------
        // 4. Client A must be gone
        // -----------------------------------------------------------------
        assertThat(sut.getPointer("client-A"))
                .as("Client A's pointer must be absent after eviction at T=600 (elapsed=600 > threshold=500)")
                .isEmpty();

        assertThat(sut.size())
                .as("tracker map must be empty after all stale entries are evicted")
                .isZero();
    }

    /**
     * Verifies that a pointer updated well within the 500 ms threshold is
     * <em>not</em> removed by {@code evictStalePointers()}.
     *
     * <h3>Scenario</h3>
     * <ol>
     *   <li>Clock at T=0: insert pointer for Client B.</li>
     *   <li>Advance clock to T=300 ms (300 &lt; 500 — still fresh).</li>
     *   <li>Call {@code evictStalePointers()}.</li>
     *   <li>Assert Client B is still present with unchanged state.</li>
     * </ol>
     */
    @Test
    @DisplayName("testStalePointerEviction: pointer below the 500ms threshold survives eviction")
    void testFreshPointerSurvivesEviction() {
        AtomicLong clock = clockAt(0L);
        PointerStateManager sut = managerWith(clock);

        sut.updatePointer("client-B", 50.0, 60.0);

        // -----------------------------------------------------------------
        // Only 300 ms have elapsed — well inside the 500 ms window
        // -----------------------------------------------------------------
        clock.set(300L);
        sut.evictStalePointers();

        assertThat(sut.getPointer("client-B"))
                .as("Client B must survive eviction: elapsed=300ms < threshold=500ms")
                .isPresent()
                .hasValueSatisfying(state -> {
                    assertThat(state.x()).isEqualTo(50.0);
                    assertThat(state.y()).isEqualTo(60.0);
                });
    }

    /**
     * Verifies the boundary condition: a pointer whose age is exactly equal to
     * the threshold ({@code elapsed == 500}) is <em>not</em> evicted.  Only
     * strictly older entries ({@code elapsed > 500}) are removed.
     *
     * <p>This prevents a race condition where a pointer that was just refreshed
     * at a clock tick identical to the eviction tick would be spuriously dropped.
     */
    @Test
    @DisplayName("testStalePointerEviction: pointer at exactly the threshold boundary is not evicted")
    void testExactBoundaryPointerNotEvicted() {
        AtomicLong clock = clockAt(0L);
        PointerStateManager sut = managerWith(clock);

        sut.updatePointer("client-C", 7.0, 8.0);

        // -----------------------------------------------------------------
        // Advance to exactly the threshold value — this is the edge case.
        // The contract is elapsed > threshold evicts; at exactly 500 ms the
        // pointer must still be considered active.
        // -----------------------------------------------------------------
        clock.set(THRESHOLD_MS);  // elapsed == 500 exactly
        sut.evictStalePointers();

        assertThat(sut.getPointer("client-C"))
                .as("pointer at elapsed == threshold (%dms) must NOT be evicted (> required, not >=)", THRESHOLD_MS)
                .isPresent();
    }

    /**
     * Verifies that {@code evictStalePointers()} performs a <em>selective</em>
     * eviction — only entries that have exceeded the threshold are removed; fresh
     * entries for other clients are untouched.
     *
     * <h3>Scenario</h3>
     * <ol>
     *   <li>Clock at T=0: register {@code client-stale}.</li>
     *   <li>Clock at T=400: register {@code client-fresh} (400 ms after the stale one).</li>
     *   <li>Advance clock to T=600.</li>
     *       <ul>
     *         <li>{@code client-stale}: elapsed = 600 ms → stale (must be evicted).</li>
     *         <li>{@code client-fresh}: elapsed = 200 ms → fresh (must survive).</li>
     *       </ul>
     *   <li>Assert only {@code client-stale} is removed.</li>
     * </ol>
     */
    @Test
    @DisplayName("testStalePointerEviction: only stale entries are removed; fresh entries are preserved")
    void testSelectiveEvictionPreservesFreshEntries() {
        AtomicLong clock = clockAt(0L);
        PointerStateManager sut = managerWith(clock);

        // -----------------------------------------------------------------
        // 1. Register the client that will become stale at T=0
        // -----------------------------------------------------------------
        sut.updatePointer("client-stale", 1.0, 2.0);

        // -----------------------------------------------------------------
        // 2. Register the client that will remain fresh at T=400
        // -----------------------------------------------------------------
        clock.set(400L);
        sut.updatePointer("client-fresh", 3.0, 4.0);

        assertThat(sut.size())
                .as("both clients must be registered before eviction")
                .isEqualTo(2);

        // -----------------------------------------------------------------
        // 3. Advance to T=600
        //    client-stale: 600 - 0   = 600 ms → exceeds threshold (stale)
        //    client-fresh: 600 - 400 = 200 ms → below threshold  (fresh)
        // -----------------------------------------------------------------
        clock.set(600L);
        sut.evictStalePointers();

        // -----------------------------------------------------------------
        // 4. Only the stale client must be gone
        // -----------------------------------------------------------------
        assertThat(sut.getPointer("client-stale"))
                .as("client-stale (600ms old) must be evicted")
                .isEmpty();

        assertThat(sut.getPointer("client-fresh"))
                .as("client-fresh (200ms old) must survive eviction")
                .isPresent()
                .hasValueSatisfying(state -> {
                    assertThat(state.x()).isEqualTo(3.0);
                    assertThat(state.y()).isEqualTo(4.0);
                    assertThat(state.lastUpdatedAt())
                            .as("lastUpdatedAt for the surviving entry must still be T=400")
                            .isEqualTo(400L);
                });

        assertThat(sut.size())
                .as("exactly one entry must remain after selective eviction")
                .isOne();
    }

    /**
     * Verifies that {@code evictStalePointers()} is idempotent: calling it
     * multiple times on an already-empty tracker (or after all entries have been
     * evicted) does not throw and leaves the tracker empty.
     */
    @Test
    @DisplayName("testStalePointerEviction: calling evict on an empty tracker is a safe no-op")
    void testEvictionOnEmptyTrackerIsNoop() {
        AtomicLong clock = clockAt(0L);
        PointerStateManager sut = managerWith(clock);

        // No entries registered — eviction must not throw
        sut.evictStalePointers();
        sut.evictStalePointers();

        assertThat(sut.size())
                .as("empty tracker must remain empty after repeated eviction calls")
                .isZero();
    }

    /**
     * Verifies that a pointer whose timestamp was refreshed <em>after</em> the
     * clock crossed the eviction threshold survives eviction, because the update
     * resets the eviction timer.
     *
     * <h3>Scenario</h3>
     * <ol>
     *   <li>Clock at T=0: register {@code client-1}.</li>
     *   <li>Advance clock to T=400.</li>
     *   <li>Call {@code updatePointer} again — resets {@code lastUpdatedAt} to T=400.</li>
     *   <li>Advance clock to T=700.</li>
     *       {@code elapsed = 700 - 400 = 300 ms < 500 ms threshold → fresh}
     *   <li>Evict — entry must survive because it was refreshed at T=400.</li>
     * </ol>
     */
    @Test
    @DisplayName("testPointerUpdate: refresh before timeout resets the eviction timer")
    void testRefreshBeforeTimeoutResetsEvictionTimer() {
        AtomicLong clock = clockAt(0L);
        PointerStateManager sut = managerWith(clock);

        // -----------------------------------------------------------------
        // 1. Initial insertion at T=0
        // -----------------------------------------------------------------
        sut.updatePointer("client-1", 10.0, 10.0);

        // -----------------------------------------------------------------
        // 2. Refresh at T=400 (before the pointer would go stale at T=501)
        // -----------------------------------------------------------------
        clock.set(400L);
        sut.updatePointer("client-1", 20.0, 20.0);

        // -----------------------------------------------------------------
        // 3. Advance to T=700 — stale relative to original T=0, but only
        //    300 ms after the T=400 refresh → still within the window
        // -----------------------------------------------------------------
        clock.set(700L);
        sut.evictStalePointers();

        assertThat(sut.getPointer("client-1"))
                .as("pointer refreshed at T=400 must survive eviction at T=700 (elapsed=300 < threshold=500)")
                .isPresent()
                .hasValueSatisfying(state -> assertThat(state.lastUpdatedAt())
                        .as("lastUpdatedAt must be the refresh timestamp T=400, not the original T=0")
                        .isEqualTo(400L));
    }
}
