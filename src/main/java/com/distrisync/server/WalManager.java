package com.distrisync.server;

import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Append-only Write-Ahead Log engine for per-room canvas durability.
 *
 * <h2>File layout</h2>
 * Each room produces a single file {@code <sanitisedRoomId>.wal} under the
 * configured {@code dataDir}.  The binary frame format is identical to the
 * wire protocol defined by {@link MessageCodec}:
 * <pre>
 * [type : 1 byte][payloadLength : 4 bytes BE][payload : UTF-8 JSON]
 * </pre>
 *
 * <h2>Compaction</h2>
 * {@link #compactWal} rewrites the WAL to contain exactly one
 * {@code MUTATION} frame per surviving shape.  It writes to a
 * {@code .wal.tmp} side-file first and then atomically renames it over the
 * live {@code .wal}, so recovery is always consistent.
 *
 * <h2>Thread safety</h2>
 * Safe for concurrent use by multiple threads.  Each room's
 * {@link FileChannel} is opened lazily and stored in a
 * {@link ConcurrentHashMap}.
 */
final class WalManager implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(WalManager.class);

    private final Path dataDir;
    private final ConcurrentHashMap<String, FileChannel> channels = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * Constructs a {@code WalManager} rooted at {@code dataDir}.
     * The directory (and any missing parents) is created automatically.
     *
     * @param dataDir root directory for all WAL files; must not be {@code null}
     * @throws IOException if the directory cannot be created
     */
    WalManager(Path dataDir) throws IOException {
        if (dataDir == null) throw new IllegalArgumentException("dataDir must not be null");
        Files.createDirectories(dataDir);
        this.dataDir = dataDir;
        log.info("WalManager initialised  dataDir='{}'", dataDir);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Appends {@code msg} to the WAL for {@code roomId}.
     *
     * <p>The WAL file is created lazily on the first append; subsequent calls
     * open the existing file in {@link StandardOpenOption#APPEND} mode so
     * existing records are never truncated.
     *
     * @param roomId the room whose WAL should receive the record; must not be
     *               {@code null} or blank
     * @param msg    the message to persist; must not be {@code null}
     * @throws IllegalArgumentException if {@code roomId} is null/blank or
     *                                  {@code msg} is null
     * @throws IOException              on write failure
     */
    void append(String roomId, Message msg) throws IOException {
        validateRoomId(roomId);
        if (msg == null) throw new IllegalArgumentException("msg must not be null");

        String safe = sanitize(roomId);
        FileChannel ch = channels.computeIfAbsent(safe, k -> openChannel(k));

        ByteBuffer frame = MessageCodec.encode(msg);
        while (frame.hasRemaining()) {
            ch.write(frame);
        }
        log.debug("WAL append  room='{}' type={} frameBytes={}", roomId, msg.type(), frame.capacity());
    }

    /**
     * Reads all complete frames from the WAL for {@code roomId}.
     *
     * <p>A missing or empty WAL file returns an empty list rather than
     * throwing.  A truncated tail frame (e.g. from a crash mid-write) is
     * silently discarded; all earlier complete frames are returned.
     *
     * @param roomId the room whose WAL should be read; must not be {@code null}
     *               or blank
     * @return an ordered list of every complete {@link Message} frame found;
     *         never {@code null}
     * @throws IllegalArgumentException if {@code roomId} is null/blank
     * @throws IOException              on read failure
     */
    List<Message> recover(String roomId) throws IOException {
        validateRoomId(roomId);

        Path path = walPath(roomId);
        if (!Files.exists(path) || Files.size(path) == 0) {
            return new ArrayList<>();
        }

        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        List<Message> messages = new ArrayList<>();

        while (buf.hasRemaining()) {
            int frameStart = buf.position();
            try {
                messages.add(MessageCodec.decode(buf));
            } catch (PartialMessageException e) {
                log.warn("WAL truncated tail  room='{}' offset={} — discarding {} partial byte(s)",
                        roomId, frameStart, buf.remaining());
                break;
            } catch (IllegalArgumentException e) {
                log.warn("WAL corrupt frame  room='{}' offset={} cause='{}' — discarding tail",
                        roomId, frameStart, e.getMessage());
                break;
            }
        }

        log.debug("WAL recovered  room='{}' messages={}", roomId, messages.size());
        return messages;
    }

    /**
     * Compacts the WAL for {@code roomId} to the minimum set of
     * {@code MUTATION} frames needed to reconstruct {@code snapshot}.
     *
     * <p>The algorithm:
     * <ol>
     *   <li>Serialise every shape in {@code snapshot} as a single
     *       {@code MUTATION} frame and write all frames to a side-file
     *       {@code <room>.wal.tmp}.</li>
     *   <li>Atomically rename {@code .wal.tmp} over the live {@code .wal}
     *       so recovery is always consistent (no window where the WAL is
     *       absent).</li>
     *   <li>Close and evict the previously open {@link FileChannel} for this
     *       room so the next {@link #append} re-opens the now-compacted file.
     *   </li>
     * </ol>
     *
     * @param roomId   target room; must not be {@code null} or blank
     * @param snapshot current authoritative canvas state; each shape becomes
     *                 one {@code MUTATION} frame
     * @throws IOException on write or rename failure
     */
    void compactWal(String roomId, List<Shape> snapshot) throws IOException {
        validateRoomId(roomId);
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");

        String safe    = sanitize(roomId);
        Path   walFile = dataDir.resolve(safe + ".wal");
        Path   tmpFile = dataDir.resolve(safe + ".wal.tmp");

        // Write compacted frames to side-file.
        try (FileChannel tmp = FileChannel.open(tmpFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            for (Shape shape : snapshot) {
                String  payload = ShapeCodec.encodeMutation(shape);
                Message msg     = new Message(MessageType.MUTATION, payload);
                ByteBuffer frame = MessageCodec.encode(msg);
                while (frame.hasRemaining()) {
                    tmp.write(frame);
                }
            }
            tmp.force(true);
        }

        // Close the live channel so the rename can replace the file on Windows.
        FileChannel live = channels.remove(safe);
        if (live != null) {
            try { live.close(); } catch (IOException ignored) {}
        }

        // Atomically replace the active WAL.
        try {
            Files.move(tmpFile, walFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            log.warn("ATOMIC_MOVE unavailable, falling back to non-atomic replace  room='{}'", roomId);
            Files.move(tmpFile, walFile, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("WAL compacted  room='{}' shapes={}", roomId, snapshot.size());
    }

    /**
     * Returns the current on-disk size of the WAL file for {@code roomId}.
     *
     * <p>If the file does not yet exist (no appends have been made) the method
     * returns {@code 0}.  When a {@link FileChannel} is open for this room its
     * {@link FileChannel#size()} is used to avoid any OS-level caching artefacts.
     *
     * @param roomId the room; must not be {@code null} or blank
     * @return current size in bytes, or {@code 0} if the file does not exist
     * @throws IOException on I/O error
     */
    long walFileSize(String roomId) throws IOException {
        validateRoomId(roomId);

        String safe = sanitize(roomId);
        FileChannel ch = channels.get(safe);
        if (ch != null && ch.isOpen()) {
            return ch.size();
        }

        Path path = dataDir.resolve(safe + ".wal");
        return Files.exists(path) ? Files.size(path) : 0L;
    }

    /**
     * Closes all open {@link FileChannel}s.  Idempotent — a second call is a
     * safe no-op.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Map.Entry<String, FileChannel> entry : channels.entrySet()) {
            try {
                entry.getValue().close();
            } catch (IOException e) {
                log.warn("Error closing WAL channel  room='{}': {}", entry.getKey(), e.getMessage());
            }
        }
        channels.clear();
        log.info("WalManager closed  dataDir='{}'", dataDir);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Maps a {@code roomId} to a filesystem-safe name by replacing every
     * character that is not alphanumeric, a dot, a hyphen, or an underscore
     * with {@code '_'}.
     */
    private static String sanitize(String roomId) {
        return roomId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Path walPath(String roomId) {
        return dataDir.resolve(sanitize(roomId) + ".wal");
    }

    private static void validateRoomId(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw new IllegalArgumentException("roomId must not be null or blank");
        }
    }

    private FileChannel openChannel(String safeRoomId) {
        try {
            return FileChannel.open(
                    dataDir.resolve(safeRoomId + ".wal"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open WAL channel for '" + safeRoomId + "'", e);
        }
    }
}
