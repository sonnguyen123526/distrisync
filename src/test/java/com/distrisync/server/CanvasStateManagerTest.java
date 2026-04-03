package com.distrisync.server;

import com.distrisync.model.Circle;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import com.distrisync.model.TextNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
                "Winner", "Arial", 14, false, false, "Alice", "client-alice");

        boolean firstApplied = manager.applyMutation(winner);

        assertThat(firstApplied)
                .as("first insertion must be accepted (no prior entry for this id)")
                .isTrue();

        // -----------------------------------------------------------------
        // 2. Stale update — ts=900 is causally older than the stored ts=1000
        // -----------------------------------------------------------------
        TextNode stale = new TextNode(
                sharedId, 900L, "#FF0000", 100.0, 100.0,
                "Stale", "Arial", 14, false, false, "Bob", "client-bob");

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
                "Duplicate", "Arial", 14, false, false, "Alice", "client-alice");

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
                "Superseder", "Arial", 16, true, false, "Charlie", "client-charlie");

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

    // =========================================================================
    // testUndoShapeRemoval
    // =========================================================================

    /**
     * Verifies the full behavioural contract of {@link CanvasStateManager#deleteShape}:
     *
     * <ol>
     *   <li><b>Targeted removal</b> — deleting by a specific {@link UUID} removes
     *       exactly that shape; the other two remain intact.</li>
     *   <li><b>Return value semantics</b> — {@code true} when the shape existed,
     *       {@code false} on a second call for the same id (idempotency).</li>
     *   <li><b>Identity preservation</b> — the surviving shapes' {@code objectId}s
     *       and field values are not modified by the deletion.</li>
     *   <li><b>Snapshot integrity</b> — after deletion, {@code snapshot()} returns
     *       exactly the remaining shapes; the deleted id is absent.</li>
     * </ol>
     *
     * <p>Three shapes with <em>pinned</em> UUIDs are used so assertion failures
     * produce deterministic, human-readable output identifying which shape was
     * wrongly retained or removed.
     */
    @Test
    void testUndoShapeRemoval() {
        CanvasStateManager manager = new CanvasStateManager();

        // Three shapes with well-known, deterministic UUIDs — pinning the ids
        // makes assertion failure messages immediately actionable.
        UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID id2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID id3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

        Shape shape1 = new Line(id1, 1000L, "#FF0000", 0, 0, 10, 10, 2.0, "Alice", "c1");
        Shape shape2 = new Circle(id2, 2000L, "#00FF00", 50, 50, 25, false, 1.5, "Bob", "c2");
        Shape shape3 = new TextNode(id3, 3000L, "#0000FF", 100, 100,
                                    "Hello", "Arial", 14, false, false, "Charlie", "c3");

        manager.applyMutation(shape1);
        manager.applyMutation(shape2);
        manager.applyMutation(shape3);

        assertThat(manager.size())
                .as("pre-condition: all 3 shapes must be stored before delete")
                .isEqualTo(3);

        // --- act: delete the middle shape (shape2, the Circle) ----------------
        boolean firstDelete = manager.deleteShape(id2);

        // --- assert: return value semantics -----------------------------------
        assertThat(firstDelete)
                .as("deleteShape must return true when the shape existed")
                .isTrue();

        // --- assert: map size -------------------------------------------------
        assertThat(manager.size())
                .as("size must drop to 2 after one deletion")
                .isEqualTo(2);

        // --- assert: snapshot integrity ---------------------------------------
        List<Shape> remaining = manager.snapshot();
        assertThat(remaining)
                .as("snapshot must contain exactly 2 shapes after deletion")
                .hasSize(2);

        Set<UUID> remainingIds = remaining.stream()
                .map(Shape::objectId)
                .collect(Collectors.toSet());

        assertThat(remainingIds)
                .as("shape1 (Line) must still be present after deleting shape2")
                .contains(id1);
        assertThat(remainingIds)
                .as("shape3 (TextNode) must still be present after deleting shape2")
                .contains(id3);
        assertThat(remainingIds)
                .as("deleted shape2 (Circle) must NOT appear in the snapshot")
                .doesNotContain(id2);

        // --- assert: idempotency — second delete of the same id returns false --
        boolean secondDelete = manager.deleteShape(id2);
        assertThat(secondDelete)
                .as("deleteShape must return false when the shape no longer exists (idempotent)")
                .isFalse();

        assertThat(manager.size())
                .as("size must remain 2 after the idempotent second delete")
                .isEqualTo(2);

        // --- assert: surviving shapes are field-value identical to originals --
        Shape retained1 = remaining.stream()
                .filter(s -> s.objectId().equals(id1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("shape1 missing from snapshot"));
        assertThat(retained1)
                .as("shape1's field values must be unchanged after an unrelated deletion")
                .isEqualTo(shape1);

        Shape retained3 = remaining.stream()
                .filter(s -> s.objectId().equals(id3))
                .findFirst()
                .orElseThrow(() -> new AssertionError("shape3 missing from snapshot"));
        assertThat(retained3)
                .as("shape3's field values must be unchanged after an unrelated deletion")
                .isEqualTo(shape3);
    }

    // =========================================================================
    // testClearUserShapes
    // =========================================================================

    /**
     * Verifies the full behavioural contract of
     * {@link CanvasStateManager#clearUserShapes}:
     *
     * <ol>
     *   <li><b>Scoped removal</b> — only shapes whose {@link Shape#clientId()}
     *       matches the supplied argument are removed; shapes owned by other
     *       clients are left untouched.</li>
     *   <li><b>Idempotency</b> — a second call for the same clientId when no
     *       shapes remain is a safe no-op.</li>
     *   <li><b>Unknown clientId</b> — calling with a clientId that owns no
     *       shapes is a safe no-op.</li>
     *   <li><b>Operational continuity</b> — the store accepts new mutations
     *       for that clientId after the clear.</li>
     * </ol>
     */
    @Test
    void testClearUserShapes() {
        CanvasStateManager manager = new CanvasStateManager();

        UUID id1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID id2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID id3 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        Shape alice1 = new Line(id1, 1000L, "#FF0000", 0, 0, 10, 10, 2.0, "Alice", "client-alice");
        Shape alice2 = new Circle(id2, 2000L, "#00FF00", 50, 50, 25, false, 1.5, "Alice", "client-alice");
        Shape bob    = new TextNode(id3, 3000L, "#0000FF", 100, 100,
                                   "Hello", "Arial", 14, false, false, "Bob", "client-bob");

        manager.applyMutation(alice1);
        manager.applyMutation(alice2);
        manager.applyMutation(bob);

        assertThat(manager.size())
                .as("pre-condition: all 3 shapes must be stored before clearUserShapes")
                .isEqualTo(3);

        // --- act: clear only Alice's shapes -----------------------------------
        manager.clearUserShapes("client-alice");

        // --- assert: scoped removal -------------------------------------------
        assertThat(manager.size())
                .as("size must be 1 — only Bob's shape survives")
                .isEqualTo(1);

        List<Shape> remaining = manager.snapshot();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).objectId())
                .as("Bob's shape must be the sole survivor")
                .isEqualTo(id3);
        assertThat(remaining.get(0).clientId())
                .as("surviving shape's clientId must be client-bob")
                .isEqualTo("client-bob");

        // --- assert: idempotency — second clear for the same user is a no-op -
        manager.clearUserShapes("client-alice");
        assertThat(manager.size())
                .as("size must remain 1 after idempotent second clearUserShapes")
                .isEqualTo(1);

        // --- assert: unknown clientId is a safe no-op -------------------------
        manager.clearUserShapes("client-unknown");
        assertThat(manager.size())
                .as("size must remain 1 after clearUserShapes for an unknown clientId")
                .isEqualTo(1);

        // --- assert: null argument throws -------------------------------------
        assertThatIllegalArgumentException()
                .isThrownBy(() -> manager.clearUserShapes(null))
                .withMessageContaining("targetClientId");

        // --- assert: store is still operational after a scoped clear ----------
        Shape aliceNew = new Circle(UUID.randomUUID(), 9000L, "#FF00FF",
                                    5, 5, 3, false, 1.0, "Alice", "client-alice");
        boolean accepted = manager.applyMutation(aliceNew);
        assertThat(accepted)
                .as("applyMutation must succeed for Alice after her shapes were cleared")
                .isTrue();
        assertThat(manager.size())
                .as("size must be 2 after Alice inserts a new shape post-clear")
                .isEqualTo(2);
    }

    // =========================================================================
    // testScopedClear_RemovesOnlyTargetUser
    // =========================================================================

    /**
     * Focuses specifically on the partition-correctness of
     * {@link CanvasStateManager#clearUserShapes}: given shapes distributed
     * across two client identifiers, clearing one client's shapes must leave
     * every surviving shape owned exclusively by the other client.
     *
     * <p>Setup: 3 shapes for {@code "UserA"} + 2 shapes for {@code "UserB"}.
     *
     * <p>The iteration-level assertion — checking {@link Shape#clientId()} for
     * every remaining entry — ensures the predicate logic in
     * {@link java.util.concurrent.ConcurrentHashMap#values()
     * .removeIf(Predicate)} does not accidentally over-delete or under-delete.
     */
    @Test
    void testScopedClear_RemovesOnlyTargetUser() {
        CanvasStateManager manager = new CanvasStateManager();

        // Insert 3 shapes for UserA with distinct, deterministic timestamps.
        for (int i = 1; i <= 3; i++) {
            manager.applyMutation(new Circle(
                    UUID.randomUUID(), i * 1_000L,
                    "#FF0000", i * 10.0, i * 10.0, 5.0,
                    false, 1.0,
                    "UserA", "UserA"));
        }

        // Insert 2 shapes for UserB.
        for (int i = 1; i <= 2; i++) {
            manager.applyMutation(new Line(
                    UUID.randomUUID(), (i + 3) * 1_000L,
                    "#0000FF", 0, 0, 20, 20, 1.5,
                    "UserB", "UserB"));
        }

        assertThat(manager.size())
                .as("pre-condition: 5 shapes total (3 UserA + 2 UserB) must be stored")
                .isEqualTo(5);

        // --- act --------------------------------------------------------------
        manager.clearUserShapes("UserA");

        // --- assert: exact surviving count ------------------------------------
        assertThat(manager.size())
                .as("size must be exactly 2 after clearing all 3 UserA shapes")
                .isEqualTo(2);

        // --- assert: every remaining shape belongs to UserB ------------------
        List<Shape> remaining = manager.snapshot();

        assertThat(remaining)
                .as("snapshot must contain exactly 2 shapes post-clear")
                .hasSize(2);

        // AssertJ extracting() gives a precise per-element failure message
        // that names the actual clientId found when the assertion fails.
        assertThat(remaining)
                .extracting(Shape::clientId)
                .as("every surviving shape must be owned by 'UserB' — no UserA shapes must linger")
                .containsOnly("UserB");
    }

    // =========================================================================
    // testClearAll
    // =========================================================================

    /**
     * Verifies the full behavioural contract of {@link CanvasStateManager#clearAll}:
     *
     * <ol>
     *   <li><b>Total erasure</b> — {@code size()} returns 0 immediately after the
     *       call.</li>
     *   <li><b>Empty snapshot</b> — {@code snapshot()} returns an empty, immutable
     *       list (not {@code null}) after clearing.</li>
     *   <li><b>Stale-id rejection</b> — {@code deleteShape} returns {@code false}
     *       for any id that existed before the clear, confirming the store is
     *       truly empty.</li>
     *   <li><b>Operational continuity</b> — {@code applyMutation} correctly accepts
     *       new shapes after clearing, so the store is not left in a broken state.</li>
     *   <li><b>Idempotency</b> — a second {@code clearAll()} on an already-empty
     *       store succeeds without throwing.</li>
     * </ol>
     */
    @Test
    void testClearAll() {
        CanvasStateManager manager = new CanvasStateManager();

        // Insert 5 shapes and capture their ids for post-clear probing.
        List<UUID> insertedIds = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Circle c = Circle.create("#AABBCC", i * 5.0, i * 5.0, i * 3.0);
            manager.applyMutation(c);
            insertedIds.add(c.objectId());
        }

        assertThat(manager.size())
                .as("pre-condition: 5 shapes must be stored before clearAll")
                .isEqualTo(5);

        // --- act --------------------------------------------------------------
        manager.clearAll();

        // --- assert: total erasure --------------------------------------------
        assertThat(manager.size())
                .as("size() must be 0 immediately after clearAll()")
                .isZero();

        // --- assert: snapshot returns empty (not null) list -------------------
        List<Shape> postClearSnapshot = manager.snapshot();

        assertThat(postClearSnapshot)
                .as("snapshot() must return a non-null, empty list after clearAll()")
                .isNotNull()
                .isEmpty();

        // Verify the post-clear snapshot is still immutable.
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .as("snapshot() after clearAll() must still be unmodifiable")
                .isThrownBy(() -> postClearSnapshot.add(Circle.create("#000000", 0, 0, 1)));

        // --- assert: deleteShape returns false for any previously-held id -----
        for (UUID staleId : insertedIds) {
            assertThat(manager.deleteShape(staleId))
                    .as("deleteShape(%s) must return false — shape was erased by clearAll", staleId)
                    .isFalse();
        }

        // --- assert: store is still operational — new shapes are accepted -----
        Circle fresh = Circle.create("#FFFFFF", 1.0, 1.0, 1.0);
        boolean accepted = manager.applyMutation(fresh);

        assertThat(accepted)
                .as("applyMutation must succeed after clearAll — store must not be left broken")
                .isTrue();
        assertThat(manager.size())
                .as("size must be 1 after inserting exactly one new shape into the cleared store")
                .isEqualTo(1);

        // --- assert: idempotency — second clearAll on the now-populated store -
        manager.clearAll();
        assertThat(manager.size())
                .as("second clearAll() must empty the store again")
                .isZero();

        // A third clearAll on a truly empty store must not throw.
        assertThatCode(manager::clearAll)
                .as("clearAll() on an already-empty store must not throw")
                .doesNotThrowAnyException();

        assertThat(manager.size())
                .as("size must remain 0 after no-op clearAll on empty store")
                .isZero();
    }
}
