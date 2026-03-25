package com.distrisync.server;

import com.distrisync.model.Circle;
import com.distrisync.model.Shape;
import com.distrisync.model.TextNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class CanvasStateManagerTest {

    // =========================================================================
    // testConcurrentInsertions
    // =========================================================================

    /**
     * Spawns 100 virtual threads that all race to insert their own unique Shape
     * simultaneously.
     *
     * <p>Because every {@link Circle#create} call generates a fresh
     * {@link UUID#randomUUID()}, no two shapes share an objectId — there are no
     * LWW merge decisions to make.  The test proves that the
     * {@link java.util.concurrent.ConcurrentHashMap} backing the store does not
     * drop any insertion under high concurrency, which would happen if the
     * implementation used an unsynchronised {@link java.util.HashMap}.
     *
     * <p>A {@link CountDownLatch startGate} ensures all 100 virtual threads reach
     * {@code applyMutation} before any of them proceeds, maximising contention on
     * the map's internal bucket locks.
     */
    @Test
    void testConcurrentInsertions() throws InterruptedException {
        final int THREAD_COUNT = 100;

        CanvasStateManager manager = new CanvasStateManager();

        // startGate: holds all threads at the starting line until we drop the flag.
        CountDownLatch startGate = new CountDownLatch(1);
        // doneLatch: counts down once per completed thread so we know when all are done.
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

        // Thread-safe list to collect each applyMutation return value.
        List<Boolean> results = Collections.synchronizedList(new ArrayList<>(THREAD_COUNT));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < THREAD_COUNT; i++) {
                executor.submit(() -> {
                    try {
                        // Park here until all threads are ready, then race together.
                        startGate.await();

                        // Each call to Circle.create() internally calls UUID.randomUUID(),
                        // so every shape inserted by every thread is unique.
                        boolean applied = manager.applyMutation(
                                Circle.create("#3C99DC", 0.0, 0.0, 5.0));
                        results.add(applied);

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Release all 100 virtual threads simultaneously.
            startGate.countDown();

            boolean allCompleted = doneLatch.await(10, TimeUnit.SECONDS);
            assertThat(allCompleted)
                    .as("all 100 virtual threads must finish within the 10-second guard timeout")
                    .isTrue();
        }

        // Every unique shape must have been stored; no data dropped.
        assertThat(manager.size())
                .as("final map size must equal the number of inserted shapes")
                .isEqualTo(THREAD_COUNT);

        // Every applyMutation call must have returned true — unique UUIDs mean no
        // LWW rejection can occur.
        assertThat(results)
                .as("every insertion of a unique shape must be accepted by the state manager")
                .hasSize(THREAD_COUNT)
                .allMatch(Boolean.TRUE::equals);
    }

    // =========================================================================
    // testTimestampConflictResolution
    // =========================================================================

    /**
     * Validates the full last-writer-wins (LWW) decision matrix for a single
     * shared objectId across four successive mutations:
     *
     * <ol>
     *   <li><b>Initial insertion</b> (ts=1000) — must be accepted; establishes the
     *       "canonical" entry.</li>
     *   <li><b>Stale update</b> (ts=900, same id) — must be rejected; a lower
     *       Lamport timestamp means the event happened causally earlier.</li>
     *   <li><b>Equal-timestamp re-delivery</b> (ts=1000, same id) — must be
     *       rejected; ties are resolved in favour of the existing entry to make
     *       re-delivery idempotent.</li>
     *   <li><b>Superseding update</b> (ts=2000, same id) — must be accepted; a
     *       strictly newer timestamp wins and replaces the stored entry.</li>
     * </ol>
     */
    @Test
    void testTimestampConflictResolution() {
        CanvasStateManager manager = new CanvasStateManager();

        // Stable shared identity — simulates the same logical canvas object being
        // mutated (or spuriously re-sent) by multiple distributed nodes.
        UUID sharedId = UUID.randomUUID();

        // -----------------------------------------------------------------
        // 1. Initial insertion at ts=1000
        // -----------------------------------------------------------------
        TextNode winner = new TextNode(
                sharedId, 1000L, "#000000", 0.0, 0.0,
                "Winner", "Arial", 14, false, false);

        boolean firstApplied = manager.applyMutation(winner);

        assertThat(firstApplied)
                .as("first insertion must be accepted (no prior entry for this id)")
                .isTrue();

        // -----------------------------------------------------------------
        // 2. Stale update — ts=900 is causally older than the stored ts=1000
        // -----------------------------------------------------------------
        TextNode stale = new TextNode(
                sharedId, 900L, "#FF0000", 100.0, 100.0,
                "Stale", "Arial", 14, false, false);

        boolean staleApplied = manager.applyMutation(stale);

        assertThat(staleApplied)
                .as("mutation with older timestamp (900 < 1000) must be rejected")
                .isFalse();

        // Confirm the store still holds the ts=1000 winner, not the stale version.
        List<Shape> snapshotAfterStale = manager.snapshot();
        assertThat(snapshotAfterStale).hasSize(1);

        TextNode retainedAfterStale = (TextNode) snapshotAfterStale.get(0);
        assertThat(retainedAfterStale.timestamp())
                .as("stored timestamp must remain 1000 after stale rejection")
                .isEqualTo(1000L);
        assertThat(retainedAfterStale.content())
                .as("stored content must remain 'Winner' — stale entry must not overwrite")
                .isEqualTo("Winner");

        // -----------------------------------------------------------------
        // 3. Equal-timestamp re-delivery — idempotent no-op
        // -----------------------------------------------------------------
        TextNode duplicate = new TextNode(
                sharedId, 1000L, "#0000FF", 200.0, 200.0,
                "Duplicate", "Arial", 14, false, false);

        boolean duplicateApplied = manager.applyMutation(duplicate);

        assertThat(duplicateApplied)
                .as("equal timestamp (1000 == 1000) must be rejected — ties favour existing entry")
                .isFalse();

        TextNode retainedAfterTie = (TextNode) manager.snapshot().get(0);
        assertThat(retainedAfterTie.content())
                .as("content must still be 'Winner' after equal-timestamp no-op")
                .isEqualTo("Winner");

        // -----------------------------------------------------------------
        // 4. Superseding update — ts=2000 is strictly newer, must win
        // -----------------------------------------------------------------
        TextNode superseder = new TextNode(
                sharedId, 2000L, "#00FF00", 50.0, 50.0,
                "Superseder", "Arial", 16, true, false);

        boolean supersederApplied = manager.applyMutation(superseder);

        assertThat(supersederApplied)
                .as("mutation with newer timestamp (2000 > 1000) must be accepted")
                .isTrue();

        TextNode finalState = (TextNode) manager.snapshot().get(0);
        assertThat(finalState.timestamp())
                .as("stored timestamp must be updated to 2000")
                .isEqualTo(2000L);
        assertThat(finalState.content())
                .as("stored content must be updated to 'Superseder'")
                .isEqualTo("Superseder");
        assertThat(finalState.fontSize())
                .as("all superseder fields must be persisted, not just the timestamp")
                .isEqualTo(16);
    }

    // =========================================================================
    // testStateSnapshot
    // =========================================================================

    /**
     * Verifies the three contractual properties of
     * {@link CanvasStateManager#snapshot()}:
     *
     * <ol>
     *   <li><b>Completeness</b> — the returned list contains exactly the shapes
     *       present in the store at the instant of the call.</li>
     *   <li><b>Immutability</b> — structural mutations on the returned list throw
     *       {@link UnsupportedOperationException}; external code cannot corrupt
     *       the snapshot.</li>
     *   <li><b>Copy semantics</b> — mutations applied to the store <em>after</em>
     *       the snapshot is taken are not visible in the already-returned list;
     *       a subsequent call to {@code snapshot()} reflects the new state.</li>
     * </ol>
     */
    @Test
    void testStateSnapshot() {
        CanvasStateManager manager = new CanvasStateManager();

        // Insert exactly 5 distinct shapes.
        for (int i = 1; i <= 5; i++) {
            manager.applyMutation(
                    Circle.create("#FF5733", i * 10.0, i * 10.0, i * 2.0));
        }

        List<Shape> snapshot = manager.snapshot();

        // -----------------------------------------------------------------
        // 1. Completeness: all 5 inserted shapes must be present
        // -----------------------------------------------------------------
        assertThat(snapshot)
                .as("snapshot must contain exactly the 5 shapes inserted before the call")
                .hasSize(5);

        // -----------------------------------------------------------------
        // 2. Immutability: structural mutation attempts must throw
        // -----------------------------------------------------------------
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("add() on the snapshot must be prohibited")
                .isThrownBy(() -> snapshot.add(Circle.create("#000000", 0.0, 0.0, 1.0)));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("remove(int) on the snapshot must be prohibited")
                .isThrownBy(() -> snapshot.remove(0));

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("clear() on the snapshot must be prohibited")
                .isThrownBy(snapshot::clear);

        // -----------------------------------------------------------------
        // 3. Copy semantics: mutations after snapshot() must not affect the
        //    already-returned list; a fresh snapshot() call must reflect them
        // -----------------------------------------------------------------
        manager.applyMutation(Circle.create("#123456", 999.0, 999.0, 99.0)); // 6th shape

        assertThat(snapshot)
                .as("snapshot taken before the 6th insertion must still show exactly 5 shapes")
                .hasSize(5);

        assertThat(manager.size())
                .as("live store must now contain 6 shapes")
                .isEqualTo(6);

        assertThat(manager.snapshot())
                .as("a fresh snapshot() call must include the 6th shape")
                .hasSize(6);
    }
}
