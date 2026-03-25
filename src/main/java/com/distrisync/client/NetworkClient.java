package com.distrisync.client;

import com.distrisync.model.Circle;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import com.distrisync.model.TextNode;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * TCP network layer for the DistriSync collaborative whiteboard client.
 *
 * <h2>Thread model</h2>
 * <ul>
 *   <li><b>distrisync-read</b> — a blocking thread that calls
 *       {@link SocketChannel#read} in a tight loop, accumulates raw bytes,
 *       decodes complete {@link Message} frames, and dispatches them to all
 *       registered {@link CanvasUpdateListener}s.</li>
 *   <li><b>distrisync-write</b> — a dedicated thread that drains the
 *       {@link ConcurrentLinkedQueue} of encoded outgoing {@code MUTATION}
 *       frames.  It parks itself when the queue is empty and is woken by
 *       {@link LockSupport#unpark} from {@link #sendMutation}.</li>
 * </ul>
 *
 * <h2>Reconnection backoff</h2>
 * On EOF or any I/O error the read thread invokes the reconnect strategy:
 * up to {@value #MAX_RECONNECT_ATTEMPTS} attempts, each separated by
 * {@value #RECONNECT_DELAY_MS} ms.  After exhausting all attempts a
 * {@link RuntimeException} is thrown from the read thread and the client
 * transitions to a permanently-stopped state.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * NetworkClient client = new NetworkClient("localhost", 9090);
 * client.addListener(myCanvasListener);
 * client.connect();                        // blocks until first handshake sent
 * client.sendMutation(someShape);          // enqueued; async dispatch
 * client.close();                          // graceful shutdown
 * }</pre>
 */
public final class NetworkClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NetworkClient.class);

    /** Delay between successive reconnect attempts. */
    private static final int RECONNECT_DELAY_MS = 2_000;

    /** Maximum number of reconnect attempts before the client gives up. */
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    /** Read accumulation buffer capacity per channel (64 KiB). */
    private static final int READ_BUFFER_CAPACITY = 64 * 1024;

    /** Max idle park duration for the write thread (5 ms). */
    private static final long WRITE_PARK_NANOS = 5_000_000L;

    /** Extended park duration after a transient write failure (500 ms). */
    private static final long WRITE_ERROR_PARK_NANOS = 500_000_000L;

    // =========================================================================
    // State
    // =========================================================================

    private final String host;
    private final int    port;

    /** Thread-safe listener registry; copy-on-write for lock-free iteration. */
    private final CopyOnWriteArrayList<CanvasUpdateListener> listeners =
            new CopyOnWriteArrayList<>();

    /**
     * Outgoing MUTATION frames awaiting dispatch.  Produced by any thread via
     * {@link #sendMutation}; consumed exclusively by the write thread.
     */
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue =
            new ConcurrentLinkedQueue<>();

    /** Guards against double-connect and allows graceful shutdown signalling. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Reference to the write daemon thread so that producers can
     * {@link LockSupport#unpark} it when new work is enqueued.
     */
    private volatile Thread writeThread;

    /**
     * The live {@link SocketChannel}; replaced atomically on every successful
     * reconnect.  Declared {@code volatile} so the write thread always sees the
     * most recent value without acquiring a lock.
     */
    private volatile SocketChannel channel;

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Creates a {@code NetworkClient} targeting the given server endpoint.
     * Call {@link #connect()} to establish the connection.
     *
     * @param host server host name or IP; must not be blank
     * @param port server TCP port; must be in [1, 65535]
     */
    public NetworkClient(String host, int port) {
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("host must not be blank");
        if (port < 1 || port > 65_535)
            throw new IllegalArgumentException("Invalid port: " + port);
        this.host = host;
        this.port = port;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Establishes the initial TCP connection using the reconnect backoff
     * strategy, sends the {@code HANDSHAKE} frame, and starts the read and
     * write daemon threads.
     *
     * <p>This method is idempotent only for the first call; subsequent calls
     * throw {@link IllegalStateException}.
     *
     * @throws IOException          if all {@value #MAX_RECONNECT_ATTEMPTS}
     *                              connection attempts fail
     * @throws IllegalStateException if already connected
     */
    public void connect() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("NetworkClient is already connected");
        }
        channel = connectWithBackoff();
        sendHandshake();
        startWriteThread();
        startReadThread();
    }

    /**
     * Enqueues a {@code MUTATION} frame for the given shape onto the async
     * write queue.  Safe to call from any thread (e.g., the UI dispatch
     * thread).  The actual write is performed by the write daemon thread.
     *
     * @param shape the shape mutation to broadcast; must not be {@code null}
     * @throws IllegalStateException if the client has not been connected
     */
    public void sendMutation(Shape shape) {
        if (shape == null) throw new IllegalArgumentException("shape must not be null");
        if (!running.get()) throw new IllegalStateException("NetworkClient is not running");

        ByteBuffer frame = MessageCodec.encode(
                new Message(MessageType.MUTATION, ClientShapeCodec.encodeMutation(shape)));
        writeQueue.offer(frame);

        // Wake the write thread in case it is parked waiting for work.
        Thread wt = writeThread;
        if (wt != null) LockSupport.unpark(wt);
    }

    /**
     * Registers a listener to receive canvas update events.
     * Duplicate registrations are silently ignored.
     *
     * @param listener the listener to add; ignored if {@code null}
     */
    public void addListener(CanvasUpdateListener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener to remove; ignored if {@code null} or not registered
     */
    public void removeListener(CanvasUpdateListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    /**
     * Stops both I/O threads and closes the underlying channel.  Safe to call
     * from any thread.  After this returns the client instance must not be
     * reused.
     */
    @Override
    public void close() {
        running.set(false);

        SocketChannel ch = channel;
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException e) {
                log.warn("Error closing channel during shutdown", e);
            }
        }

        // Unpark the write thread so it exits its park/loop promptly.
        Thread wt = writeThread;
        if (wt != null) LockSupport.unpark(wt);

        log.info("NetworkClient closed");
    }

    // =========================================================================
    // Connection helpers
    // =========================================================================

    /**
     * Opens a blocking {@link SocketChannel} to the configured endpoint.
     * Retries on failure with a {@value #RECONNECT_DELAY_MS} ms pause between
     * attempts, up to {@value #MAX_RECONNECT_ATTEMPTS} times total.
     *
     * @return a connected, blocking-mode {@link SocketChannel}
     * @throws IOException if all attempts are exhausted
     */
    private SocketChannel connectWithBackoff() throws IOException {
        IOException lastError = null;

        for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
            log.info("Connecting to {}:{} (attempt {}/{})",
                    host, port, attempt, MAX_RECONNECT_ATTEMPTS);
            try {
                SocketChannel sc = SocketChannel.open();
                sc.configureBlocking(true);
                sc.connect(new InetSocketAddress(host, port));
                log.info("TCP connection established to {}:{}", host, port);
                return sc;
            } catch (IOException e) {
                lastError = e;
                log.warn("Attempt {}/{} failed: {}", attempt, MAX_RECONNECT_ATTEMPTS, e.getMessage());

                if (attempt < MAX_RECONNECT_ATTEMPTS) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during reconnect backoff", ie);
                    }
                }
            }
        }

        throw new IOException(
                "Failed to connect to " + host + ":" + port
                + " after " + MAX_RECONNECT_ATTEMPTS + " attempts",
                lastError);
    }

    /**
     * Sends a {@code HANDSHAKE} frame synchronously on the current channel.
     * This always completes before the I/O threads are started (or restarted
     * after a reconnect), so no locking is required here.
     */
    private void sendHandshake() throws IOException {
        ByteBuffer handshake = MessageCodec.encode(Message.empty(MessageType.HANDSHAKE));
        writeBlocking(channel, handshake);
        log.debug("HANDSHAKE sent to {}:{}", host, port);
    }

    /**
     * Writes {@code buf} entirely to {@code ch}, spinning on partial writes
     * (which can occur when the OS send-buffer is momentarily full).
     */
    private static void writeBlocking(SocketChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    // =========================================================================
    // Reconnect
    // =========================================================================

    /**
     * Closes the stale channel, opens a fresh one via the backoff strategy, and
     * re-sends the {@code HANDSHAKE} so the server issues a new {@code SNAPSHOT}.
     *
     * <p>Synchronized to prevent the read and write threads from triggering
     * concurrent reconnect storms.
     *
     * @throws IOException if all reconnect attempts are exhausted
     */
    private synchronized void reconnect() throws IOException {
        SocketChannel stale = channel;
        if (stale != null) {
            try { stale.close(); } catch (IOException ignored) {}
        }
        channel = connectWithBackoff();
        sendHandshake();
        log.info("Reconnected to {}:{} and HANDSHAKE re-sent", host, port);
    }

    // =========================================================================
    // Read thread
    // =========================================================================

    private void startReadThread() {
        Thread t = new Thread(this::readLoop, "distrisync-read");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("Read thread terminated with fatal exception — client offline", ex));
        t.start();
    }

    /**
     * Blocking read loop.  Runs on the {@code distrisync-read} daemon thread.
     *
     * <p>Each iteration appends bytes from the channel into an accumulation
     * buffer, flips it, drains all complete frames, then compacts the remaining
     * partial tail back to the write position.  On disconnect it triggers the
     * reconnect backoff; after exhausting all attempts it propagates a
     * {@link RuntimeException} through the thread's uncaught handler.
     */
    private void readLoop() {
        // Per-connection accumulation buffer; cleared only on reconnect.
        ByteBuffer accumulator = ByteBuffer.allocate(READ_BUFFER_CAPACITY);

        while (running.get()) {
            try {
                int bytesRead = channel.read(accumulator);

                if (bytesRead == -1) {
                    log.warn("Server closed the connection (EOF)");
                    handleDisconnect("EOF from server");
                    accumulator.clear();
                    continue;
                }

                if (bytesRead == 0) {
                    // Shouldn't happen on a blocking channel, but guard defensively.
                    continue;
                }

                // Flip: switch from write-mode to read-mode for decoding.
                accumulator.flip();
                drainFrames(accumulator);
                // Compact: move any partial tail back to position 0 for the next read.
                accumulator.compact();

            } catch (IOException e) {
                if (!running.get()) break; // Normal shutdown — channel was closed by close().
                log.warn("I/O error on read channel: {}", e.getMessage());
                try {
                    handleDisconnect(e.getMessage());
                    accumulator.clear();
                } catch (RuntimeException fatal) {
                    running.set(false);
                    throw fatal;
                }
            }
        }

        log.info("Read loop exited");
    }

    /**
     * Decodes all complete frames from a flipped {@code buffer}, dispatching
     * each to {@link #dispatchMessage}.  Stops when a
     * {@link PartialMessageException} signals that the remaining bytes do not
     * form a complete frame; the codec has already rewound the buffer position
     * to the start of the partial tail so the caller can safely {@code compact}.
     */
    private void drainFrames(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            try {
                Message msg = MessageCodec.decode(buffer);
                dispatchMessage(msg);
            } catch (PartialMessageException e) {
                // Buffer holds a partial frame; position was reset by the codec.
                // The outer readLoop will compact and read more bytes.
                break;
            }
        }
    }

    private void dispatchMessage(Message msg) {
        switch (msg.type()) {
            case SNAPSHOT -> {
                List<Shape> shapes = ClientShapeCodec.decodeSnapshot(msg.payload());
                log.debug("SNAPSHOT received: {} shape(s)", shapes.size());
                for (CanvasUpdateListener listener : listeners) {
                    listener.onSnapshotReceived(shapes);
                }
            }
            case MUTATION -> {
                Shape shape = ClientShapeCodec.decodeMutation(msg.payload());
                log.debug("MUTATION received: objectId={}", shape.objectId());
                for (CanvasUpdateListener listener : listeners) {
                    listener.onMutationReceived(shape);
                }
            }
            default -> log.trace("Ignoring server-bound message type: {}", msg.type());
        }
    }

    private void handleDisconnect(String reason) {
        log.warn("Disconnected ({}); starting reconnect sequence…", reason);
        try {
            reconnect();
        } catch (IOException fatal) {
            log.error("All {} reconnect attempts exhausted — client is permanently offline",
                    MAX_RECONNECT_ATTEMPTS, fatal);
            running.set(false);
            throw new RuntimeException(
                    "Permanently disconnected from " + host + ":" + port
                    + " after " + MAX_RECONNECT_ATTEMPTS + " attempts", fatal);
        }
    }

    // =========================================================================
    // Write thread
    // =========================================================================

    private void startWriteThread() {
        Thread t = new Thread(this::writeLoop, "distrisync-write");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) ->
                log.error("Write thread terminated with fatal exception", ex));
        writeThread = t;
        t.start();
    }

    /**
     * Async write loop.  Runs on the {@code distrisync-write} daemon thread.
     *
     * <p>Polls {@link #writeQueue} for outgoing frames.  When the queue is
     * empty the thread parks itself for up to {@value #WRITE_PARK_NANOS} ns and
     * is woken early by any call to {@link #sendMutation}.  On a transient
     * channel write failure the undelivered frame is re-queued and the thread
     * backs off, allowing the read thread time to complete a reconnect.
     */
    private void writeLoop() {
        while (running.get()) {
            ByteBuffer frame = writeQueue.poll();

            if (frame == null) {
                // Queue is empty — park briefly; sendMutation() will unpark us.
                LockSupport.parkNanos(WRITE_PARK_NANOS);
                continue;
            }

            try {
                writeBlocking(channel, frame);
            } catch (IOException e) {
                if (!running.get()) break;
                log.warn("Write failed (channel may have dropped): {}; re-queuing frame", e.getMessage());
                // Re-offer the unsent frame so it is retried after reconnect.
                writeQueue.offer(frame);
                // Back off to let the read thread complete its reconnect.
                LockSupport.parkNanos(WRITE_ERROR_PARK_NANOS);
            }
        }

        log.info("Write loop exited");
    }

    // =========================================================================
    // Client-side shape codec
    // =========================================================================

    /**
     * Mirrors the server-side {@code ShapeCodec} for the client package.
     *
     * <p>The server encodes every shape in a JSON envelope that carries a
     * {@code "_type"} discriminator alongside all shape fields.  This codec
     * reads and writes the same format so the wire protocol remains symmetric.
     *
     * <p>A dedicated {@link Gson} instance with a {@link UUID} type adapter is
     * used because Gson's default reflection-based UUID handling produces
     * {@code {"mostSigBits":…,"leastSigBits":…}}, which is incompatible with
     * {@link UUID#fromString}.
     */
    private static final class ClientShapeCodec {

        private static final String TYPE_FIELD = "_type";

        private static final Gson GSON = new GsonBuilder()
                .registerTypeHierarchyAdapter(UUID.class, new UuidAdapter())
                .serializeNulls()
                .disableHtmlEscaping()
                .create();

        private ClientShapeCodec() {}

        /** Serialises a shape to a JSON envelope string (for outgoing MUTATION frames). */
        static String encodeMutation(Shape shape) {
            JsonObject envelope = GSON.toJsonTree(shape).getAsJsonObject();
            envelope.addProperty(TYPE_FIELD, shape.getClass().getSimpleName());
            return GSON.toJson(envelope);
        }

        /** Deserialises all shapes from a SNAPSHOT payload. */
        static List<Shape> decodeSnapshot(String payload) {
            JsonArray array = JsonParser.parseString(payload).getAsJsonArray();
            List<Shape> shapes = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                shapes.add(fromEnvelope(element.getAsJsonObject()));
            }
            return shapes;
        }

        /** Deserialises a single shape from a MUTATION payload. */
        static Shape decodeMutation(String payload) {
            return fromEnvelope(JsonParser.parseString(payload).getAsJsonObject());
        }

        private static Shape fromEnvelope(JsonObject envelope) {
            JsonElement typeElement = envelope.get(TYPE_FIELD);
            if (typeElement == null) {
                throw new IllegalArgumentException(
                        "Shape envelope is missing the '" + TYPE_FIELD + "' discriminator field");
            }
            return switch (typeElement.getAsString()) {
                case "Line"     -> GSON.fromJson(envelope, Line.class);
                case "Circle"   -> GSON.fromJson(envelope, Circle.class);
                case "TextNode" -> GSON.fromJson(envelope, TextNode.class);
                default -> throw new IllegalArgumentException(
                        "Unknown shape type discriminator: '" + typeElement.getAsString() + "'");
            };
        }

        private static final class UuidAdapter
                implements JsonSerializer<UUID>, JsonDeserializer<UUID> {

            @Override
            public JsonElement serialize(UUID src, Type type, JsonSerializationContext ctx) {
                return new JsonPrimitive(src.toString());
            }

            @Override
            public UUID deserialize(JsonElement json, Type type, JsonDeserializationContext ctx)
                    throws JsonParseException {
                try {
                    return UUID.fromString(json.getAsString());
                } catch (IllegalArgumentException e) {
                    throw new JsonParseException("Invalid UUID string: " + json.getAsString(), e);
                }
            }
        }
    }
}
