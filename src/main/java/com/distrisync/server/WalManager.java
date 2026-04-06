package com.distrisync.server;

import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.PartialMessageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable Write-Ahead Log (WAL) engine for room-level canvas state.
 *
 * <h2>Storage layout</h2>
 * <pre>
 *   distrisync-data/
 *     {roomId}.wal    ← one APPEND-mode file per room
 * </pre>
 * Room identifiers are sanitised before use as filenames: every character
 * outside {@code [a-zA-Z0-9_\-]} is replaced by an underscore.
 *
 * <h2>Frame format</h2>
 * Each WAL record is a verbatim DistriSync binary frame encoded by
 * {@link MessageCodec}: {@code [type:1][length:4BE][payload:N]}.  Using the
 * existing codec means recovery simply calls {@link MessageCodec#decode} in a
 * loop — no separate WAL record header is needed.
 *
 * <h2>Write path</h2>
 * {@link #append} writes the encoded frame to an {@link FileChannel} opened in
 * {@link StandardOpenOption#APPEND} mode.  Writes are <em>not</em> followed by
 * {@link FileChannel#force(boolean)} on every call; the OS page-cache provides
 * throughput-friendly buffering and will flush to storage within seconds.
 * Operators who need stricter durability guarantees can uncomment the
 * {@code force(false)} line in {@link #append}.
 *
 * <h2>Recovery path</h2>
 * {@link #recover} opens the WAL file in read-only mode, reads it into a
 * heap {@link ByteBuffer}, and decodes frames sequentially.  A truncated or
 * corrupt tail frame (common after a hard crash mid-write) is silently
 * tolerated: recovery stops at the first undecodable offset and returns the
 * clean prefix.
 *
 * <h2>Thread safety</h2>
 * {@link ConcurrentHashMap} guards the channel registry.  {@code FileChannel}
 * writes from a single-threaded NIO event loop are inherently serialised, so no
 * additional locking around individual {@code write} calls is required.
 */
public final class WalManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WalManager.class);

    /** Default on-disk directory relative to the working directory. */
    static final String DEFAULT_DATA_DIR = "distrisync-data";

    private final Path dataDir;

    /**
     * Live append channels, one per room.  Entries are created lazily on the
     * first {@link #append} call for a given room.
     */
    private final ConcurrentHashMap<String, FileChannel> channels = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates a {@code WalManager} using the default data directory
     * ({@value #DEFAULT_DATA_DIR} relative to the JVM working directory).
     *
     * @throws IOException if the directory cannot be created
     */
    public WalManager() throws IOException {
        this(Path.of(DEFAULT_DATA_DIR));
    }

    /**
     * Creates a {@code WalManager} rooted at {@code dataDir}.
     * The directory (and any missing parents) are created if they do not exist.
     *
     * @param dataDir the base directory for WAL files; must not be {@code null}
     * @throws IOException if the directory cannot be created
     */
    WalManager(Path dataDir) throws IOException {
        if (dataDir == null) throw new IllegalArgumentException("dataDir must not be null");
        this.dataDir = dataDir;
        Files.createDirectories(dataDir);
        log.info("WalManager initialised  dataDir='{}'", dataDir.toAbsolutePath());
    }

    // -------------------------------------------------------------------------
    // Append path
    // -------------------------------------------------------------------------

    /**
     * Serialises {@code message} as a binary frame and appends it atomically to
     * the WAL file for {@code roomId}.
     *
     * <p>The channel is opened lazily on the first call for each room and remains
     * open until {@link #close()} is called.  Writes are OS-buffered (no forced
     * {@code fsync}) for throughput; the OS will flush the page cache to stable
     * storage within seconds of the write.
     *
     * @param roomId  the room whose WAL should receive the record
     * @param message the state-mutating message to persist
     * @throws IOException if the write fails
     */
    public void append(String roomId, Message message) throws IOException {
        if (roomId  == null || roomId.isBlank()) throw new IllegalArgumentException("roomId must not be blank");
        if (message == null)                     throw new IllegalArgumentException("message must not be null");

        FileChannel channel = channels.computeIfAbsent(roomId, this::openAppendChannel);
        ByteBuffer  frame   = MessageCodec.encode(message);

        // Write the full frame; loop handles the (rare) partial-write case.
        while (frame.hasRemaining()) {
            channel.write(frame);
        }

        // Uncomment for strict durability (at the cost of throughput):
        // channel.force(false);

        log.debug("WAL append  roomId='{}' type={} payloadBytes={}",
                roomId, message.type(), message.payload().length());
    }

    // -------------------------------------------------------------------------
    // Recovery path
    // -------------------------------------------------------------------------

    /**
     * Reads the WAL file for {@code roomId} and returns all decodable
     * {@link Message}s in the order they were appended.
     *
     * <p>If no WAL file exists the method returns an empty list (first-time
     * startup or a room that was never persisted).  A truncated tail frame
     * — the typical artefact of a mid-write crash — is silently tolerated:
     * recovery stops at the corrupt offset and returns the clean prefix.
     *
     * <p><strong>Caller responsibility:</strong> the returned messages must be
     * replayed into the target {@link CanvasStateManager} <em>before</em> the
     * room is made visible to connecting clients.
     *
     * @param roomId the room whose WAL should be read
     * @return an ordered, immutable-view list of persisted messages; never {@code null}
     * @throws IOException if the file exists but cannot be read
     */
    public List<Message> recover(String roomId) throws IOException {
        if (roomId == null || roomId.isBlank()) throw new IllegalArgumentException("roomId must not be blank");

        Path walPath = walPath(roomId);
        if (!Files.exists(walPath)) {
            log.debug("No WAL found for roomId='{}' — fresh room", roomId);
            return List.of();
        }

        List<Message> messages = new ArrayList<>();

        try (FileChannel channel = FileChannel.open(walPath, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize == 0) {
                log.debug("WAL is empty for roomId='{}' — nothing to replay", roomId);
                return messages;
            }

            // Read entire file into a heap buffer for sequential frame decoding.
            // For rooms with very large WALs this could be chunked, but a single
            // allocation keeps the recovery path simple and fast for typical use.
            if (fileSize > Integer.MAX_VALUE) {
                throw new IOException(
                        "WAL file too large for single-pass recovery: " + fileSize + " bytes  roomId='" + roomId + "'");
            }
            ByteBuffer buf = ByteBuffer.allocate((int) fileSize);
            while (buf.hasRemaining()) {
                int n = channel.read(buf);
                if (n == -1) break;
            }
            buf.flip();

            int frameCount = 0;
            while (buf.remaining() >= MessageCodec.HEADER_BYTES) {
                int startOffset = buf.position();
                try {
                    Message msg = MessageCodec.decode(buf);
                    messages.add(msg);
                    frameCount++;
                } catch (PartialMessageException e) {
                    // Truncated tail — normal after a hard crash mid-write.
                    log.warn("WAL recovery: truncated frame at offset={} roomId='{}' — stopping replay  ({})",
                            startOffset, roomId, e.getMessage());
                    break;
                } catch (IllegalArgumentException e) {
                    // Unknown type discriminator or negative length — corrupt record.
                    log.error("WAL recovery: corrupt frame at offset={} roomId='{}' — stopping replay  ({})",
                            startOffset, roomId, e.getMessage());
                    break;
                }
            }

            log.info("WAL recovery complete  roomId='{}' frames={} file='{}'",
                    roomId, frameCount, walPath.getFileName());
        }

        return messages;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Opens (or creates) the WAL file for {@code roomId} in APPEND mode.
     * Called by {@link ConcurrentHashMap#computeIfAbsent} — must not throw a
     * checked exception, so {@link IOException} is wrapped as
     * {@link UncheckedIOException}.
     */
    private FileChannel openAppendChannel(String roomId) {
        try {
            Path path = walPath(roomId);
            FileChannel ch = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            log.debug("Opened WAL append channel  roomId='{}' path='{}'", roomId, path);
            return ch;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to open WAL append channel for roomId='" + roomId + "'", e);
        }
    }

    /**
     * Maps a room identifier to its WAL file path, sanitising unsafe filename
     * characters in the process.
     */
    private Path walPath(String roomId) {
        String safe = roomId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return dataDir.resolve(safe + ".wal");
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Closes all open WAL {@link FileChannel}s and clears the channel registry.
     * After this call, any subsequent {@link #append} will attempt to re-open
     * the file.  Intended to be called once during server shutdown.
     */
    @Override
    public void close() {
        int closed = 0;
        for (var entry : channels.entrySet()) {
            try {
                entry.getValue().close();
                closed++;
            } catch (IOException e) {
                log.warn("Error closing WAL channel  roomId='{}': {}", entry.getKey(), e.getMessage());
            }
        }
        channels.clear();
        log.info("WalManager closed — {} channel(s) released", closed);
    }
}
