package com.distrisync.client;

import com.distrisync.model.Circle;
import com.distrisync.model.EraserPath;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import com.distrisync.model.TextNode;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageCodec.TextUpdatePayload;
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
import java.net.StandardSocketOptions;
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
 * client.addLobbyListener(myLobbyListener);
 * client.connect();                        // handshake → lobby; LOBBY_STATE pushed by server
 * client.sendJoinRoom("MyRoom");          // async; SNAPSHOT follows when joined
 * client.sendMutation(someShape);         // enqueued; async dispatch
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
    private final String authorName;
    private final String clientId;

    /**
     * Room id used for {@code JOIN_ROOM} on reconnect. Empty while the client
     * is in the lobby (after handshake / {@code LEAVE_ROOM}).
     */
    private volatile String activeRoomId = "";

    /** Thread-safe listener registry; copy-on-write for lock-free iteration. */
    private final CopyOnWriteArrayList<CanvasUpdateListener> listeners =
            new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<LobbyUpdateListener> lobbyListeners =
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
     * Creates an anonymous {@code NetworkClient} targeting the given server endpoint.
     * Attribution defaults to empty author name and a random {@code clientId}.
     * Call {@link #connect()} for the lobby, then {@link #sendJoinRoom(String)}.
     *
     * @param host server host name or IP; must not be blank
     * @param port server TCP port; must be in [1, 65535]
     */
    public NetworkClient(String host, int port) {
        this(host, port, "", java.util.UUID.randomUUID().toString());
    }

    /**
     * Creates an attributed {@code NetworkClient} targeting the given server endpoint.
     * The {@code authorName} and {@code clientId} are embedded in the {@code HANDSHAKE}
     * frame and attached to every shape the client mutates.  After {@link #connect()}
     * the client stays in the lobby until {@link #sendJoinRoom(String)}.
     *
     * @param host       server host name or IP; must not be blank
     * @param port       server TCP port; must be in [1, 65535]
     * @param authorName human-readable display name; may be empty but not {@code null}
     * @param clientId   stable session identifier; may be empty but not {@code null}
     */
    public NetworkClient(String host, int port, String authorName, String clientId) {
        if (host == null || host.isBlank())
            throw new IllegalArgumentException("host must not be blank");
        if (port < 1 || port > 65_535)
            throw new IllegalArgumentException("Invalid port: " + port);
        this.host       = host;
        this.port       = port;
        this.authorName = authorName != null ? authorName : "";
        this.clientId   = clientId   != null ? clientId   : "";
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Establishes the initial TCP connection using the reconnect backoff
     * strategy, sends the {@code HANDSHAKE} frame, and starts the read and
     * write daemon threads. The server places the client in the lobby and may
     * push {@code LOBBY_STATE}; call {@link #sendJoinRoom(String)} to enter a room
     * and receive {@code SNAPSHOT}.
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
        try {
            channel = connectWithBackoff();
            sendHandshake();
            startWriteThread();
            startReadThread();
        } catch (IOException e) {
            running.set(false);
            SocketChannel ch = channel;
            channel = null;
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException ignored) { /* best-effort */ }
            }
            throw e;
        }
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

        enqueueFrame(MessageCodec.encode(
                new Message(MessageType.MUTATION, ClientShapeCodec.encodeMutation(shape))));
    }

    /**
     * Enqueues a {@code SHAPE_START} frame signalling that this client began
     * drawing a new shape.  Silently no-ops when not connected.
     */
    public void sendShapeStart(UUID shapeId, String tool, String color,
                               double strokeWidth, double x, double y) {
        if (!running.get()) return;
        record Payload(String shapeId, String tool, String color,
                       double strokeWidth, double x, double y, String authorName) {}
        enqueueFrame(MessageCodec.encodeObject(MessageType.SHAPE_START,
                new Payload(shapeId.toString(), tool, color, strokeWidth, x, y, authorName)));
    }

    /**
     * Enqueues a {@code SHAPE_UPDATE} frame with the latest cursor tip
     * coordinates for a shape that is currently being drawn.
     * Silently no-ops when not connected.
     */
    public void sendShapeUpdate(UUID shapeId, double x, double y) {
        if (!running.get()) return;
        record Payload(String shapeId, double x, double y) {}
        enqueueFrame(MessageCodec.encodeObject(MessageType.SHAPE_UPDATE,
                new Payload(shapeId.toString(), x, y)));
    }

    /**
     * Enqueues a {@code SHAPE_COMMIT} frame signalling that the shape is
     * finished.  Receivers should clear the transient preview; the final
     * shape(s) will arrive as subsequent {@code MUTATION} frames.
     * Silently no-ops when not connected.
     */
    public void sendShapeCommit(UUID shapeId) {
        if (!running.get()) return;
        record Payload(String shapeId) {}
        enqueueFrame(MessageCodec.encodeObject(MessageType.SHAPE_COMMIT,
                new Payload(shapeId.toString())));
    }

    /**
     * Enqueues a {@code TEXT_UPDATE} frame carrying the current (uncommitted)
     * content of a text node that is being actively edited.
     *
     * <p>The server relays the frame to all other connected clients immediately
     * without persisting it — receivers should update a transient "ghost" overlay
     * rather than committing the text to their authoritative canvas store.  The
     * final committed {@link com.distrisync.model.TextNode} arrives later as a
     * normal {@code MUTATION} frame.
     *
     * <p>Silently no-ops when not connected.
     *
     * @param objectId    stable UUID of the text node being edited; must not be {@code null}
     * @param x           X anchor coordinate of the text node on the canvas
     * @param y           Y anchor coordinate of the text node on the canvas
     * @param currentText the in-progress (uncommitted) text; must not be {@code null}
     */
    public void sendTextUpdate(UUID objectId, double x, double y, String currentText) {
        if (objectId    == null) throw new IllegalArgumentException("objectId must not be null");
        if (currentText == null) throw new IllegalArgumentException("currentText must not be null");
        if (!running.get()) return;
        enqueueFrame(MessageCodec.encodeTextUpdate(objectId, clientId, authorName, x, y, currentText));
        log.debug("TEXT_UPDATE enqueued objectId={}", objectId);
    }

    /**
     * Sends a {@code CLEAR_USER_SHAPES} request to the server carrying this
     * client's own {@code clientId} as the payload.  The server will remove all
     * shapes owned by this client from its authoritative state and relay the
     * frame to every other connected peer so they can mirror the scoped clear.
     * The calling client is responsible for removing its own shapes from the
     * local canvas immediately, without waiting for an echo.
     *
     * <p>Silently no-ops when not connected.
     */
    public void sendClearUserShapes() {
        if (!running.get()) return;
        enqueueFrame(MessageCodec.encodeClearUserShapes(clientId));
        log.debug("CLEAR_USER_SHAPES enqueued clientId={}", clientId);
    }

    /**
     * Sends an {@code UNDO_REQUEST} to the server asking it to delete the shape
     * identified by {@code shapeId}.  If the shape exists in the authoritative
     * state the server removes it and broadcasts a {@code SHAPE_DELETE} frame to
     * all other clients.  The calling client should remove the shape from its
     * local store immediately, without waiting for an echo.
     *
     * <p>Silently no-ops when not connected.
     *
     * @param shapeId the {@link UUID} of the shape to delete; must not be {@code null}
     */
    public void sendUndoRequest(UUID shapeId) {
        if (shapeId == null) throw new IllegalArgumentException("shapeId must not be null");
        if (!running.get()) return;
        record Payload(String shapeId) {}
        enqueueFrame(MessageCodec.encodeObject(MessageType.UNDO_REQUEST,
                new Payload(shapeId.toString())));
        log.debug("UNDO_REQUEST enqueued shapeId={}", shapeId);
    }

    /** Returns the author name this client advertised in its {@code HANDSHAKE}. */
    public String getAuthorName() {
        return authorName;
    }

    /** Returns the client-ID this client advertised in its {@code HANDSHAKE}. */
    public String getClientId() {
        return clientId;
    }

    /**
     * Last room passed to {@link #sendJoinRoom(String)}; empty in the lobby
     * (including immediately after {@link #sendLeaveRoom()}).
     */
    public String getActiveRoomId() {
        return activeRoomId;
    }

    /** {@code true} while TCP I/O threads are running after a successful {@link #connect()}. */
    public boolean isRunning() {
        return running.get();
    }

    /** Adds {@code frame} to the write queue and unparks the write thread. */
    private void enqueueFrame(ByteBuffer frame) {
        writeQueue.offer(frame);
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
     * Registers a listener for {@code LOBBY_STATE} frames. Duplicate registrations
     * are ignored.
     */
    public void addLobbyListener(LobbyUpdateListener listener) {
        if (listener != null) lobbyListeners.addIfAbsent(listener);
    }

    public void removeLobbyListener(LobbyUpdateListener listener) {
        if (listener != null) lobbyListeners.remove(listener);
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
                // Disable Nagle's algorithm before the connect syscall so the OS
                // never buffers small frames (cursor ticks, live-text events).
                sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
                // 64 KiB kernel socket buffers absorb large snapshots without
                // blocking the write loop mid-frame.
                sc.setOption(StandardSocketOptions.SO_SNDBUF, 64 * 1024);
                sc.setOption(StandardSocketOptions.SO_RCVBUF, 64 * 1024);
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
     * The payload carries {@code authorName} and {@code clientId} so the server
     * can associate subsequent mutations with the originating user.
     * This always completes before the I/O threads are started (or restarted
     * after a reconnect), so no locking is required here.
     */
    private void sendHandshake() throws IOException {
        ByteBuffer handshake = MessageCodec.encodeHandshake(authorName, clientId);
        writeBlocking(channel, handshake);
        log.debug("HANDSHAKE sent to {}:{} authorName='{}' clientId='{}'",
                host, port, authorName, clientId);
    }

    /**
     * Enters a canvas room (async). The server responds with {@code SNAPSHOT}.
     * Updates {@link #getActiveRoomId()} for reconnect semantics.
     *
     * @param roomId non-blank room identifier
     */
    public void sendJoinRoom(String roomId) {
        if (!running.get()) return;
        String rid = roomId != null ? roomId.strip() : "";
        if (rid.isBlank()) {
            log.debug("sendJoinRoom ignored — blank roomId");
            return;
        }
        activeRoomId = rid;
        enqueueFrame(MessageCodec.encodeJoinRoom(rid));
        log.debug("JOIN_ROOM enqueued roomId='{}'", rid);
    }

    /**
     * Blocking {@code JOIN_ROOM} for the reconnect path only (write thread not used).
     */
    private void sendJoinRoomBlocking(String roomId) throws IOException {
        ByteBuffer frame = MessageCodec.encodeJoinRoom(roomId);
        writeBlocking(channel, frame);
        log.debug("JOIN_ROOM sent (blocking) to {}:{} roomId='{}'", host, port, roomId);
    }

    /**
     * Requests to leave the current canvas room and return to the lobby.
     * No-op when not connected.
     */
    public void sendLeaveRoom() {
        if (!running.get()) return;
        activeRoomId = "";
        enqueueFrame(MessageCodec.encodeLeaveRoom());
        log.debug("LEAVE_ROOM enqueued");
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
        String rid = activeRoomId;
        if (rid != null && !rid.isBlank()) {
            sendJoinRoomBlocking(rid);
            log.info("Reconnected to {}:{} — HANDSHAKE + JOIN_ROOM roomId='{}'", host, port, rid);
        } else {
            log.info("Reconnected to {}:{} — HANDSHAKE (lobby)", host, port);
        }
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
            case SHAPE_START -> {
                try {
                    JsonObject p      = MessageCodec.gson().fromJson(msg.payload(), JsonObject.class);
                    UUID   shapeId    = UUID.fromString(p.get("shapeId").getAsString());
                    String tool       = p.get("tool").getAsString();
                    String color      = p.get("color").getAsString();
                    double strokeW    = p.get("strokeWidth").getAsDouble();
                    double x          = p.get("x").getAsDouble();
                    double y          = p.get("y").getAsDouble();
                    String author     = p.has("authorName") && !p.get("authorName").isJsonNull()
                                        ? p.get("authorName").getAsString() : "";
                    for (CanvasUpdateListener listener : listeners) {
                        listener.onShapeStart(shapeId, tool, color, strokeW, x, y, author);
                    }
                } catch (Exception e) {
                    log.debug("Malformed SHAPE_START payload ignored: {}", e.getMessage());
                }
            }
            case SHAPE_UPDATE -> {
                try {
                    JsonObject p   = MessageCodec.gson().fromJson(msg.payload(), JsonObject.class);
                    UUID   shapeId = UUID.fromString(p.get("shapeId").getAsString());
                    double x       = p.get("x").getAsDouble();
                    double y       = p.get("y").getAsDouble();
                    for (CanvasUpdateListener listener : listeners) {
                        listener.onShapeUpdate(shapeId, x, y);
                    }
                } catch (Exception e) {
                    log.debug("Malformed SHAPE_UPDATE payload ignored: {}", e.getMessage());
                }
            }
            case SHAPE_COMMIT -> {
                try {
                    JsonObject p   = MessageCodec.gson().fromJson(msg.payload(), JsonObject.class);
                    UUID   shapeId = UUID.fromString(p.get("shapeId").getAsString());
                    for (CanvasUpdateListener listener : listeners) {
                        listener.onShapeCommit(shapeId);
                    }
                } catch (Exception e) {
                    log.debug("Malformed SHAPE_COMMIT payload ignored: {}", e.getMessage());
                }
            }
            case CLEAR_USER_SHAPES -> {
                String targetClientId;
                try {
                    targetClientId = MessageCodec.decodeClearUserShapes(msg);
                } catch (Exception e) {
                    log.debug("Malformed CLEAR_USER_SHAPES payload ignored: {}", e.getMessage());
                    break;
                }
                log.debug("CLEAR_USER_SHAPES received targetClientId={}", targetClientId);
                for (CanvasUpdateListener listener : listeners) {
                    listener.onUserShapesCleared(targetClientId);
                }
            }
            case SHAPE_DELETE -> {
                try {
                    JsonObject p   = MessageCodec.gson().fromJson(msg.payload(), JsonObject.class);
                    UUID   shapeId = UUID.fromString(p.get("shapeId").getAsString());
                    log.debug("SHAPE_DELETE received shapeId={}", shapeId);
                    for (CanvasUpdateListener listener : listeners) {
                        listener.onShapeDeleted(shapeId);
                    }
                } catch (Exception e) {
                    log.debug("Malformed SHAPE_DELETE payload ignored: {}", e.getMessage());
                }
            }
            case TEXT_UPDATE -> {
                try {
                    TextUpdatePayload p = MessageCodec.decodeTextUpdate(msg);
                    UUID objectId = UUID.fromString(p.objectId());
                    log.debug("TEXT_UPDATE received objectId={} clientId={}", objectId, p.clientId());
                    for (CanvasUpdateListener listener : listeners) {
                        listener.onTextUpdate(objectId, p.clientId(), p.authorName(),
                                              p.x(), p.y(), p.currentText());
                    }
                } catch (Exception e) {
                    log.debug("Malformed TEXT_UPDATE payload ignored: {}", e.getMessage());
                }
            }
            case LOBBY_STATE -> {
                try {
                    var entries = MessageCodec.decodeLobbyState(msg);
                    log.debug("LOBBY_STATE received  rooms={}", entries.size());
                    List<RoomInfo> rooms = RoomInfo.copyOf(entries);
                    for (LobbyUpdateListener listener : lobbyListeners) {
                        listener.onLobbyUpdated(rooms);
                    }
                } catch (Exception e) {
                    log.debug("Malformed LOBBY_STATE ignored: {}", e.getMessage());
                }
            }
            case JOIN_ROOM, LEAVE_ROOM ->
                    log.trace("Ignoring echoed client-bound message type: {}", msg.type());
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
                case "Line"       -> GSON.fromJson(envelope, Line.class);
                case "Circle"     -> GSON.fromJson(envelope, Circle.class);
                case "TextNode"   -> GSON.fromJson(envelope, TextNode.class);
                case "EraserPath" -> GSON.fromJson(envelope, EraserPath.class);
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
