package com.distrisync.server;

import com.distrisync.model.Circle;
import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link WalManager}.
 *
 * <h2>Strategy</h2>
 * Every test constructs a {@link WalManager} against a JUnit-managed
 * {@link TempDir} so no real disk state escapes between runs.  The
 * package-private {@code WalManager(Path)} constructor is used throughout,
 * which is accessible because this class lives in the same package.
 *
 * <h2>What is covered</h2>
 * <ul>
 *   <li>Happy-path round-trip: append N messages, close, reopen, recover
 *       exactly N messages in chronological order with lossless field equality.</li>
 *   <li>Idempotent empty-state: missing file and zero-byte file both produce
 *       an empty recovery list without throwing.</li>
 *   <li>Physical file creation: the WAL file is written to disk on the
 *       first append.</li>
 *   <li>Room isolation: two rooms write to independent WAL files; recovery
 *       of one room never sees records from another.</li>
 *   <li>Crash resilience: a truncated tail frame (last write interrupted
 *       mid-byte before the crash) is silently discarded; all prior clean
 *       frames are still returned.</li>
 *   <li>Durable accumulation across reopens: a second {@link WalManager}
 *       bound to the same directory appends to existing files rather than
 *       truncating them.</li>
 *   <li>Filename sanitisation: room IDs containing path-unsafe characters
 *       are mapped to a safe, predictable filename.</li>
 *   <li>Contract violations: {@code null} / blank arguments are rejected
 *       immediately with {@link IllegalArgumentException}.</li>
 *   <li>Double-close safety: calling {@link WalManager#close()} twice does
 *       not throw.</li>
 *   <li>Compaction correctness: after {@link WalManager#compactWal} replaces a
 *       bloated WAL (100 noise frames) with the minimal surviving-shape set
 *       (1 {@code MUTATION} frame), the on-disk file is drastically smaller,
 *       the temporary {@code .wal.tmp} file is cleaned up, and a fresh
 *       {@link RoomContext} that replays the compacted WAL recovers exactly
 *       the one surviving shape with lossless field equality.</li>
 * </ul>
 */
class WalManagerTest {

    private static final String BOARD = MessageCodec.DEFAULT_INITIAL_BOARD_ID;

    /**
     * Fresh temporary directory injected by JUnit 5 before each test and
     * deleted automatically after.  Because this is an instance field (not
     * {@code static}), each test receives its own isolated directory.
     */
    @TempDir
    Path tempDir;

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Builds a SHAPE_COMMIT {@link Message} whose payload encodes a sequence number. */
    private static Message shapeCommit(int seq) {
        return new Message(MessageType.SHAPE_COMMIT,
                "{\"seq\":" + seq + ",\"room\":\"TestRoom\"}");
    }

    // =========================================================================
    // testWalAppendAndReplay
    // =========================================================================

    /**
     * Core round-trip invariant for the WAL engine.
     *
     * <h2>Scenario</h2>
     * Three {@code SHAPE_COMMIT} messages are appended to the WAL for room
     * {@code "TestRoom"} in sequence-number order.  The {@link WalManager} is
     * then closed to release all file handles.  A brand-new instance bound to
     * the same directory reads the WAL back via {@link WalManager#recover}.
     *
     * <h2>Invariants asserted</h2>
     * <ol>
     *   <li><b>Cardinality</b> — exactly three messages are recovered; no
     *       records are silently dropped or duplicated.</li>
     *   <li><b>Type fidelity</b> — every recovered message carries
     *       {@link MessageType#SHAPE_COMMIT} as its type discriminator.</li>
     *   <li><b>Payload fidelity</b> — each payload string round-trips without
     *       modification (byte-for-byte UTF-8 equality).</li>
     *   <li><b>Chronological order</b> — records are returned in the exact
     *       order they were appended; no reordering by the storage layer.</li>
     * </ol>
     */
    @Test
    void testWalAppendAndReplay() throws IOException {
        // ── Given: three SHAPE_COMMIT messages with distinguishable payloads ──
        Message msg1 = shapeCommit(1);
        Message msg2 = shapeCommit(2);
        Message msg3 = shapeCommit(3);

        // ── When: append all three, then close and reopen the WalManager ──────
        try (WalManager writer = new WalManager(tempDir)) {
            writer.append("TestRoom", BOARD, msg1);
            writer.append("TestRoom", BOARD, msg2);
            writer.append("TestRoom", BOARD, msg3);
        }

        // A brand-new WalManager instance opens the same directory for recovery.
        List<Message> recovered;
        try (WalManager reader = new WalManager(tempDir)) {
            recovered = reader.recover("TestRoom", BOARD);
        }

        // ── Then: 1. Cardinality ──────────────────────────────────────────────
        assertThat(recovered)
                .as("WAL must recover exactly the three messages that were appended — " +
                    "no records silently dropped or duplicated")
                .hasSize(3);

        // ── Then: 2. Type fidelity ────────────────────────────────────────────
        assertThat(recovered)
                .as("every recovered message must carry MessageType.SHAPE_COMMIT — " +
                    "the type discriminator byte must survive the binary round-trip")
                .extracting(Message::type)
                .containsExactly(
                        MessageType.SHAPE_COMMIT,
                        MessageType.SHAPE_COMMIT,
                        MessageType.SHAPE_COMMIT);

        // ── Then: 3. Payload fidelity — byte-exact UTF-8 round-trip ──────────
        assertThat(recovered.get(0).payload())
                .as("first recovered payload must equal the payload supplied to the first append call")
                .isEqualTo(msg1.payload());

        assertThat(recovered.get(1).payload())
                .as("second recovered payload must equal the payload supplied to the second append call")
                .isEqualTo(msg2.payload());

        assertThat(recovered.get(2).payload())
                .as("third recovered payload must equal the payload supplied to the third append call")
                .isEqualTo(msg3.payload());

        // ── Then: 4. Chronological order ─────────────────────────────────────
        //    The seq values extracted from the payloads must be 1, 2, 3 in that order.
        assertThat(recovered)
                .as("records must be returned in append (chronological) order — " +
                    "the WAL is an append-only log with no reordering")
                .extracting(Message::payload)
                .containsExactly(msg1.payload(), msg2.payload(), msg3.payload());
    }

    // =========================================================================
    // testNoWalFile_RecoverReturnsEmptyList
    // =========================================================================

    /**
     * A room whose WAL file has never been created is treated as a fresh room
     * with no history.
     *
     * <p>{@link WalManager#recover} must return an empty, non-null list rather
     * than throwing an {@link java.io.FileNotFoundException} or
     * {@link NullPointerException}.  This is the expected path for all rooms
     * on a first-ever server start.
     */
    @Test
    void testNoWalFile_RecoverReturnsEmptyList() throws IOException {
        try (WalManager wal = new WalManager(tempDir)) {
            List<Message> result = wal.recover("NeverWrittenRoom", BOARD);

            assertThat(result)
                    .as("recover() for a room with no WAL file must return an empty list, not null")
                    .isNotNull()
                    .isEmpty();
        }
    }

    // =========================================================================
    // testEmptyWalFile_RecoverReturnsEmptyList
    // =========================================================================

    /**
     * A zero-byte WAL file (e.g. created by a previous run that crashed before
     * writing any records) must produce an empty recovery list without throwing.
     *
     * <p>This guards against the edge case where {@link Files#createFile} or an
     * OS-level file-creation races with the first {@code append} and leaves
     * a zero-length inode on disk.
     */
    @Test
    void testEmptyWalFile_RecoverReturnsEmptyList() throws IOException {
        // Pre-create an empty WAL file, simulating a crash before the first write.
        Files.createFile(tempDir.resolve("EmptyRoom_Board-1.wal"));

        try (WalManager wal = new WalManager(tempDir)) {
            List<Message> result = wal.recover("EmptyRoom", BOARD);

            assertThat(result)
                    .as("recover() on a zero-byte WAL file must return an empty list, not throw")
                    .isNotNull()
                    .isEmpty();
        }
    }

    // =========================================================================
    // testWalFileCreatedOnFirstAppend
    // =========================================================================

    /**
     * The WAL file for a room must be created on disk on the first
     * {@link WalManager#append} call and must not exist before that call.
     *
     * <p>This validates the lazy-open strategy: the manager should not pre-create
     * empty files for rooms that never receive any writes.
     */
    @Test
    void testWalFileCreatedOnFirstAppend() throws IOException {
        Path expectedFile = tempDir.resolve("FileCreationRoom_Board-1.wal");

        try (WalManager wal = new WalManager(tempDir)) {
            assertThat(expectedFile)
                    .as("WAL file must NOT exist before the first append — " +
                        "WalManager must use lazy file creation")
                    .doesNotExist();

            wal.append("FileCreationRoom", BOARD, shapeCommit(1));

            assertThat(expectedFile)
                    .as("WAL file must exist on disk after the first append call")
                    .exists()
                    .isRegularFile();
        }
    }

    // =========================================================================
    // testMultipleRooms_IndependentWalFiles
    // =========================================================================

    /**
     * Records appended to room {@code "Alpha"} must not appear in a recovery of
     * room {@code "Beta"}, and vice-versa.
     *
     * <h2>Invariants asserted</h2>
     * <ol>
     *   <li>Two separate WAL files are created on disk — one per room.</li>
     *   <li>Recovering room {@code "Alpha"} returns only its own messages.</li>
     *   <li>Recovering room {@code "Beta"} returns only its own messages.</li>
     * </ol>
     */
    @Test
    void testMultipleRooms_IndependentWalFiles() throws IOException {
        Message alphaMsg = new Message(MessageType.SHAPE_COMMIT, "{\"room\":\"Alpha\"}");
        Message betaMsg1 = new Message(MessageType.MUTATION,
                "{\"_type\":\"Circle\",\"objectId\":\"00000000-0000-0000-0000-000000000001\"," +
                "\"timestamp\":1000,\"color\":\"#FF0000\",\"x\":10.0,\"y\":10.0," +
                "\"radius\":5.0,\"filled\":false,\"strokeWidth\":1.0," +
                "\"authorName\":\"Bob\",\"clientId\":\"beta-client\"}");
        Message betaMsg2 = new Message(MessageType.SHAPE_COMMIT, "{\"room\":\"Beta\",\"seq\":2}");

        try (WalManager wal = new WalManager(tempDir)) {
            wal.append("Alpha", BOARD, alphaMsg);
            wal.append("Beta", BOARD, betaMsg1);
            wal.append("Beta", BOARD, betaMsg2);
        }

        // ── Physical file isolation ───────────────────────────────────────────
        assertThat(tempDir.resolve("Alpha_Board-1.wal"))
                .as("a dedicated WAL file must exist for room 'Alpha'")
                .exists();
        assertThat(tempDir.resolve("Beta_Board-1.wal"))
                .as("a dedicated WAL file must exist for room 'Beta'")
                .exists();

        // ── Content isolation ─────────────────────────────────────────────────
        try (WalManager reader = new WalManager(tempDir)) {
            List<Message> alphaRecords = reader.recover("Alpha", BOARD);
            List<Message> betaRecords  = reader.recover("Beta", BOARD);

            assertThat(alphaRecords)
                    .as("room 'Alpha' must recover exactly 1 record")
                    .hasSize(1);
            assertThat(alphaRecords.get(0).payload())
                    .as("room 'Alpha' must recover only its own message, not any Beta record")
                    .isEqualTo(alphaMsg.payload());

            assertThat(betaRecords)
                    .as("room 'Beta' must recover exactly 2 records")
                    .hasSize(2);
            assertThat(betaRecords)
                    .as("room 'Beta' must NOT contain the Alpha message")
                    .extracting(Message::payload)
                    .doesNotContain(alphaMsg.payload());
        }
    }

    // =========================================================================
    // testTruncatedTailFrame_CleanPrefixReturned
    // =========================================================================

    /**
     * Resilience against a crash that interrupted a write mid-frame.
     *
     * <p>The typical crash pattern: two complete frames are flushed to the OS
     * page cache, the server is hard-killed, and the OS then writes a partial
     * third frame to stable storage before the power fails.  The corrupted
     * tail must be silently tolerated by recovery; the two clean frames must
     * be returned intact.
     *
     * <h2>How the corruption is injected</h2>
     * After closing the {@link WalManager}, 5 bytes are appended to the WAL
     * file using {@link Files#write}.  The bytes form a structurally valid
     * frame header ({@code [type:1][length:4BE]}) that declares a 1 000-byte
     * payload — but the payload bytes are absent, simulating a truncation
     * after the OS wrote the header but before it wrote the body.
     *
     * <h2>Invariants asserted</h2>
     * <ol>
     *   <li>{@link WalManager#recover} does not throw.</li>
     *   <li>Exactly the two clean prefix frames are returned.</li>
     *   <li>Their types and payloads match what was originally appended.</li>
     * </ol>
     */
    @Test
    void testTruncatedTailFrame_CleanPrefixReturned() throws IOException {
        Message first  = shapeCommit(1);
        Message second = shapeCommit(2);

        // ── Write two valid frames and close ──────────────────────────────────
        try (WalManager wal = new WalManager(tempDir)) {
            wal.append("CrashRoom", BOARD, first);
            wal.append("CrashRoom", BOARD, second);
        }

        // ── Inject a truncated frame: header only (5 bytes), no payload ───────
        // type byte = 0x07 (SHAPE_COMMIT), length = 1000 (0x00 0x00 0x03 0xE8)
        // The declared payload of 1 000 bytes is absent → PartialMessageException
        // on decode.
        Path walFile = tempDir.resolve("CrashRoom_Board-1.wal");
        byte[] corruptTail = {
            (byte) 0x07,                      // type: SHAPE_COMMIT
            (byte) 0x00, (byte) 0x00,
            (byte) 0x03, (byte) 0xE8          // payload length = 1 000 (big-endian)
        };
        Files.write(walFile, corruptTail, StandardOpenOption.APPEND);

        // ── Recover and verify only the clean prefix survives ─────────────────
        List<Message> recovered;
        try (WalManager reader = new WalManager(tempDir)) {
            assertThatCode(() -> reader.recover("CrashRoom", BOARD))
                    .as("recover() must not throw when encountering a truncated tail frame — " +
                        "crash-tolerance is a core WAL contract")
                    .doesNotThrowAnyException();

            recovered = reader.recover("CrashRoom", BOARD);
        }

        assertThat(recovered)
                .as("only the two complete frames written before the crash must be recovered — " +
                    "the truncated tail frame must be silently discarded")
                .hasSize(2);

        assertThat(recovered.get(0).type())
                .as("first recovered message type must match the original")
                .isEqualTo(MessageType.SHAPE_COMMIT);
        assertThat(recovered.get(0).payload())
                .as("first recovered payload must be byte-exact")
                .isEqualTo(first.payload());

        assertThat(recovered.get(1).payload())
                .as("second recovered payload must be byte-exact")
                .isEqualTo(second.payload());
    }

    // =========================================================================
    // testAppendAcrossReopens_RecordsAccumulate
    // =========================================================================

    /**
     * Validates that the underlying {@link java.nio.channels.FileChannel} is
     * opened in {@link StandardOpenOption#APPEND} mode, not
     * {@link StandardOpenOption#TRUNCATE_EXISTING}.
     *
     * <p>Two batches of two messages each are appended in separate
     * {@link WalManager} lifetimes (two distinct instances, each closed before
     * the next is created).  A final recovery must return all four records,
     * proving the second open did not overwrite the first batch.
     */
    @Test
    void testAppendAcrossReopens_RecordsAccumulate() throws IOException {
        Message batch1a = shapeCommit(1);
        Message batch1b = shapeCommit(2);
        Message batch2a = shapeCommit(3);
        Message batch2b = shapeCommit(4);

        // ── First WalManager lifetime ─────────────────────────────────────────
        try (WalManager wal1 = new WalManager(tempDir)) {
            wal1.append("AccumRoom", BOARD, batch1a);
            wal1.append("AccumRoom", BOARD, batch1b);
        }

        // ── Second WalManager lifetime — must NOT truncate existing file ───────
        try (WalManager wal2 = new WalManager(tempDir)) {
            wal2.append("AccumRoom", BOARD, batch2a);
            wal2.append("AccumRoom", BOARD, batch2b);
        }

        // ── Recovery must see all four records ────────────────────────────────
        try (WalManager reader = new WalManager(tempDir)) {
            List<Message> recovered = reader.recover("AccumRoom", BOARD);

            assertThat(recovered)
                    .as("reopening WalManager must APPEND to the existing WAL, not truncate it — " +
                        "all four records across both lifetimes must be recovered")
                    .hasSize(4);

            assertThat(recovered)
                    .extracting(Message::payload)
                    .as("records must appear in strict append order across both WalManager lifetimes")
                    .containsExactly(
                            batch1a.payload(),
                            batch1b.payload(),
                            batch2a.payload(),
                            batch2b.payload());
        }
    }

    // =========================================================================
    // testRoomIdSanitization_UnsafeCharsReplacedWithUnderscore
    // =========================================================================

    /**
     * Room IDs may contain characters that are unsafe in filenames on common
     * operating systems (e.g. {@code /}, {@code \}, {@code :}, {@code ?},
     * whitespace).  The {@link WalManager} must map such characters to
     * underscores so that the WAL file can be created without an
     * {@link IOException}.
     *
     * <p>The room ID {@code "Room/Sub:Name!"} must produce a WAL file at
     * {@code Room_Sub_Name_.wal}.  Recovery using the original (unsanitised)
     * room ID must still work — the sanitisation is transparent to callers.
     */
    @Test
    void testRoomIdSanitization_UnsafeCharsReplacedWithUnderscore() throws IOException {
        String unsafeRoomId = "Room/Sub:Name!";
        Message msg = shapeCommit(99);

        try (WalManager wal = new WalManager(tempDir)) {
            assertThatCode(() -> wal.append(unsafeRoomId, BOARD, msg))
                    .as("append() must succeed even when the roomId contains filesystem-unsafe characters")
                    .doesNotThrowAnyException();
        }

        // ── Physical file must use the sanitised name ─────────────────────────
        Path expectedFile = tempDir.resolve("Room_Sub_Name__Board-1.wal");
        assertThat(expectedFile)
                .as("unsafe characters must be replaced with underscores in the WAL filename — " +
                    "expected file: Room_Sub_Name__Board-1.wal")
                .exists()
                .isRegularFile();

        // ── Recovery via the original room ID must still work ─────────────────
        try (WalManager reader = new WalManager(tempDir)) {
            List<Message> recovered = reader.recover(unsafeRoomId, BOARD);

            assertThat(recovered)
                    .as("recover() called with the original unsanitised roomId must find the " +
                        "sanitised file and return the appended record")
                    .hasSize(1);
            assertThat(recovered.get(0).payload())
                    .as("payload must survive the sanitise→write→sanitise→read round-trip")
                    .isEqualTo(msg.payload());
        }
    }

    // =========================================================================
    // testAppend_NullOrBlankRoomId_ThrowsIllegalArgumentException
    // =========================================================================

    /**
     * A {@code null} or blank {@code roomId} is a programming error that the
     * WAL engine must reject immediately rather than silently creating a
     * nameless or ambiguous WAL file.
     */
    @Test
    void testAppend_NullOrBlankRoomId_ThrowsIllegalArgumentException() throws IOException {
        try (WalManager wal = new WalManager(tempDir)) {
            assertThatIllegalArgumentException()
                    .as("append(null, msg) must throw IllegalArgumentException")
                    .isThrownBy(() -> wal.append(null, BOARD, shapeCommit(1)));

            assertThatIllegalArgumentException()
                    .as("append(\"\", msg) must throw IllegalArgumentException")
                    .isThrownBy(() -> wal.append("", BOARD, shapeCommit(1)));

            assertThatIllegalArgumentException()
                    .as("append(\"   \", msg) must throw IllegalArgumentException — whitespace-only is blank")
                    .isThrownBy(() -> wal.append("   ", BOARD, shapeCommit(1)));

            assertThatIllegalArgumentException()
                    .as("append with null boardId must throw IllegalArgumentException")
                    .isThrownBy(() -> wal.append("SomeRoom", null, shapeCommit(1)));

            assertThatIllegalArgumentException()
                    .as("append with blank boardId must throw IllegalArgumentException")
                    .isThrownBy(() -> wal.append("SomeRoom", "", shapeCommit(1)));
        }
    }

    // =========================================================================
    // testAppend_NullMessage_ThrowsIllegalArgumentException
    // =========================================================================

    /**
     * A {@code null} {@link Message} must be rejected by {@link WalManager#append}
     * before any I/O is attempted.
     */
    @Test
    void testAppend_NullMessage_ThrowsIllegalArgumentException() throws IOException {
        try (WalManager wal = new WalManager(tempDir)) {
            assertThatIllegalArgumentException()
                    .as("append(roomId, null) must throw IllegalArgumentException")
                    .isThrownBy(() -> wal.append("SomeRoom", BOARD, null));
        }
    }

    // =========================================================================
    // testRecover_NullOrBlankRoomId_ThrowsIllegalArgumentException
    // =========================================================================

    /**
     * {@link WalManager#recover} must reject a {@code null} or blank
     * {@code roomId} with an {@link IllegalArgumentException} for the same
     * reasons as {@link WalManager#append}.
     */
    @Test
    void testRecover_NullOrBlankRoomId_ThrowsIllegalArgumentException() throws IOException {
        try (WalManager wal = new WalManager(tempDir)) {
            assertThatIllegalArgumentException()
                    .as("recover(null) must throw IllegalArgumentException")
                    .isThrownBy(() -> wal.recover(null, BOARD));

            assertThatIllegalArgumentException()
                    .as("recover(\"\") must throw IllegalArgumentException")
                    .isThrownBy(() -> wal.recover("", BOARD));

            assertThatIllegalArgumentException()
                    .as("recover with null boardId must throw IllegalArgumentException")
                    .isThrownBy(() -> wal.recover("SomeRoom", null));

            assertThatIllegalArgumentException()
                    .as("recover with blank boardId must throw IllegalArgumentException")
                    .isThrownBy(() -> wal.recover("SomeRoom", ""));
        }
    }

    // =========================================================================
    // testClose_CalledTwice_DoesNotThrow
    // =========================================================================

    /**
     * {@link WalManager#close()} must be idempotent.
     *
     * <p>The JVM shutdown hook and {@code try-with-resources} patterns may
     * both invoke {@code close()} on the same instance.  A double-close must
     * silently succeed rather than propagating an
     * {@link java.nio.channels.ClosedChannelException} or
     * {@link NullPointerException}.
     */
    @Test
    void testClose_CalledTwice_DoesNotThrow() throws IOException {
        WalManager wal = new WalManager(tempDir);
        wal.append("CloseRoom", BOARD, shapeCommit(1));

        assertThatCode(() -> {
            wal.close();
            wal.close(); // second close must be a safe no-op
        })
                .as("close() called twice on the same WalManager must not throw — " +
                    "double-close must be idempotent")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // testWalDataDirCreatedIfAbsent
    // =========================================================================

    /**
     * The data directory must be created automatically by the
     * {@link WalManager} constructor, even when multiple levels of parents are
     * absent.  This means operators do not need to pre-create the directory
     * before starting the server.
     */
    @Test
    void testWalDataDirCreatedIfAbsent() throws IOException {
        // A two-level-deep directory that does not yet exist.
        Path nested = tempDir.resolve("level1").resolve("level2");

        assertThat(nested)
                .as("pre-condition: the nested directory must not exist before constructing WalManager")
                .doesNotExist();

        try (WalManager wal = new WalManager(nested)) {
            assertThat(nested)
                    .as("WalManager constructor must create the data directory (and any missing parents) " +
                        "automatically — operators must not need to pre-create it")
                    .isDirectory();
        }
    }

    // =========================================================================
    // testWalCompaction_ReducesFileSizeAndRetainsState
    // =========================================================================

    /**
     * End-to-end correctness and size-reduction proof for
     * {@link WalManager#compactWal}.
     *
     * <h2>Scenario</h2>
     * <ol>
     *   <li><b>Bloat phase</b> — 100 {@code SHAPE_COMMIT} frames are appended to the
     *       WAL, simulating a long-lived room that has accumulated noise entries
     *       that are no longer part of the authoritative canvas state.  After this
     *       phase the WAL file must exceed 2 KB.</li>
     *   <li><b>Compaction phase</b> — a {@link CanvasStateManager} with exactly
     *       <em>one</em> surviving {@link Circle} shape (the last-writer-wins
     *       in-memory truth) is constructed.  {@link WalManager#compactWal} is
     *       invoked with the snapshot, which serialises the single shape as one
     *       {@code MUTATION} frame, writes it to a temp file, and atomically
     *       replaces the active WAL via
     *       {@link java.nio.file.Files#move} + {@code ATOMIC_MOVE}.</li>
     *   <li><b>Recovery phase</b> — a brand-new {@link WalManager} instance bound
     *       to the same directory is handed to a fresh {@link RoomContext}
     *       constructor, which replays the compacted WAL into an empty
     *       {@link CanvasStateManager}.  The recovered state must contain exactly
     *       the one surviving shape with lossless field equality.</li>
     * </ol>
     *
     * <h2>Invariants asserted</h2>
     * <ol>
     *   <li><b>Pre-compaction size</b> — WAL exceeds 2 KB after 100 appended frames.
     *       This proves the bloat phase actually created a meaningful file.</li>
     *   <li><b>Size reduction ratio</b> — compacted file is at least 5× smaller
     *       than the bloated file; in practice the ratio is ~14× (100 × 33-byte
     *       frames → ~3 300 B, down to 1 × ~230-byte frame).</li>
     *   <li><b>Absolute upper bound</b> — compacted file fits within 1 KiB,
     *       confirming it is a single {@code MUTATION} frame, not the full old log.</li>
     *   <li><b>Temp-file cleanup</b> — {@code {roomId}.wal.tmp} must not exist
     *       after successful compaction; it was atomically renamed to {@code .wal}.</li>
     *   <li><b>Shape count</b> — the compacted WAL replays into exactly 1 shape
     *       in the recovered {@link CanvasStateManager}.</li>
     *   <li><b>Shape identity</b> — the recovered shape carries the same
     *       {@link Shape#objectId()} as the shape passed to {@code compactWal}.</li>
     *   <li><b>Lamport timestamp fidelity</b> — the recovered shape retains the
     *       exact {@link Shape#timestamp()} value, proving the MUTATION frame
     *       encoded the full shape record without truncation.</li>
     * </ol>
     *
     * <h2>Why {@code MUTATION} frames, not {@code SHAPE_COMMIT}</h2>
     * The WAL recovery path in {@link RoomContext} only replays {@code MUTATION},
     * {@code SHAPE_DELETE}, and {@code CLEAR_USER_SHAPES} frames.
     * {@code SHAPE_COMMIT} frames would be silently skipped, leaving the canvas
     * empty after a post-compaction restart.  This test implicitly verifies that
     * {@link WalManager#compactWal} writes the correct frame type.
     */
    @Test
    void testWalCompaction_ReducesFileSizeAndRetainsState() throws IOException {
        final String ROOM = "CompactionRoom";

        // The single shape that should survive after compaction.
        UUID   survivingId    = UUID.fromString("cafecafe-cafe-cafe-cafe-cafecafecafe");
        Circle survivingShape = new Circle(
                survivingId, 9_000L, "#AA0000",
                100.0, 200.0, 50.0, false, 2.0, "Alice", "client-1");

        // ── Phase 1 & 2: Bloat → Compact — both within one WalManager lifetime ─
        try (WalManager wal = new WalManager(tempDir)) {

            // Bloat: append 100 SHAPE_COMMIT noise frames.
            // Each frame is ~33 bytes (5-byte header + 28-byte payload), so
            // the bloated file will be approximately 3 300 bytes.
            for (int i = 1; i <= 100; i++) {
                wal.append(ROOM, BOARD, shapeCommit(i));
            }

            long bloatedSize = wal.walFileSize(ROOM, BOARD);

            // ── 1. Pre-compaction size guard ──────────────────────────────────
            assertThat(bloatedSize)
                    .as("pre-compaction WAL must exceed 2 KB after 100 appended SHAPE_COMMIT frames — " +
                        "this guards against a vacuous compaction that starts from an empty file")
                    .isGreaterThan(2_000L);

            // Build the authoritative in-memory canvas: exactly one surviving shape.
            CanvasStateManager canvas = new CanvasStateManager();
            canvas.applyMutation(survivingShape);
            List<Shape> snapshot = canvas.snapshot();

            assertThat(snapshot)
                    .as("pre-condition: the canvas must contain exactly 1 shape before compaction")
                    .hasSize(1);

            // Compact: replace the 100-frame WAL with a single MUTATION frame.
            wal.compactWal(ROOM, BOARD, snapshot);

            long compactedSize = wal.walFileSize(ROOM, BOARD);

            // ── 2. Size-reduction ratio: at least 5× ─────────────────────────
            assertThat(compactedSize)
                    .as("compacted WAL must be at least 5× smaller than the bloated WAL " +
                        "(expected ~14× reduction: 100 × 33-byte frames → 1 × ~230-byte frame)")
                    .isLessThan(bloatedSize / 5L);

            // ── 3. Absolute size upper bound: under 1 KiB ─────────────────────
            assertThat(compactedSize)
                    .as("compacted WAL for a single shape must fit comfortably within 1 KiB — " +
                        "if it doesn't, compactWal wrote the old frames instead of the snapshot")
                    .isLessThan(1_024L);

            // ── 4. Temp file must have been cleaned up ────────────────────────
            Path tmpFile = tempDir.resolve("CompactionRoom_Board-1.wal.tmp");
            assertThat(tmpFile)
                    .as(".wal.tmp must not exist after successful compaction — " +
                        "Files.move(ATOMIC_MOVE) must have renamed it to .wal")
                    .doesNotExist();

            // ── 5. Active .wal file must be a regular file ────────────────────
            Path walFile = tempDir.resolve("CompactionRoom_Board-1.wal");
            assertThat(walFile)
                    .as("compacted .wal file must exist as a regular file after compaction")
                    .exists()
                    .isRegularFile();
        }

        // ── Phase 3: Verify recovery — fresh WalManager + fresh RoomContext ───
        //
        // RoomContext lazy-opens boards via getBoard(); replay reads the compacted WAL.
        try (WalManager freshWal = new WalManager(tempDir)) {
            RoomContext recovered = new RoomContext(ROOM, freshWal);

            // ── 6. Shape count ─────────────────────────────────────────────────
            assertThat(recovered.getBoard(BOARD).size())
                    .as("recovered CanvasStateManager must contain exactly 1 shape — " +
                        "compaction must have written exactly one MUTATION frame (the surviving shape), " +
                        "not the 100 stale SHAPE_COMMIT frames it replaced")
                    .isEqualTo(1);

            List<Shape> recoveredShapes = recovered.getBoard(BOARD).snapshot();

            // ── 7. Shape identity ──────────────────────────────────────────────
            assertThat(recoveredShapes.get(0).objectId())
                    .as("recovered shape must carry the same objectId as the surviving shape " +
                        "passed to compactWal — the serialise→move→recover round-trip must be lossless")
                    .isEqualTo(survivingId);

            // ── 8. Lamport timestamp fidelity ──────────────────────────────────
            assertThat(recoveredShapes.get(0).timestamp())
                    .as("recovered shape must retain the exact Lamport timestamp (9000) from the original — " +
                        "ShapeCodec.encodeMutation must not truncate or reset the timestamp field")
                    .isEqualTo(9_000L);
        }
    }
}
