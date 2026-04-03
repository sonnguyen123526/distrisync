package com.distrisync.server;

import com.distrisync.model.Shape;
import com.distrisync.protocol.Message;
import com.distrisync.protocol.MessageCodec;
import com.distrisync.protocol.MessageType;
import com.distrisync.protocol.PartialMessageException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Central-authority server for the DistriSync collaborative whiteboard.
 *
 * <h2>Architecture</h2>
 * A single-threaded NIO event loop driven by a {@link Selector}. The design
 * supports many concurrent clients (the requirement is ≥ 4) without spawning
 * additional threads and without any blocking I/O call inside the loop.
 *
 * <h2>Connection lifecycle</h2>
 * <ol>
 *   <li><b>Accept</b> – a new {@link SocketChannel} is registered for
 *       {@code OP_READ} with a fresh {@link ClientSession} as the attachment.
 *       A {@code SNAPSHOT} of all current canvas shapes is immediately
 *       enqueued and flushed.</li>
 *   <li><b>Read</b> – incoming bytes are appended to the session's
 *       accumulation buffer. Complete frames are decoded in a tight loop;
 *       partial tails are left in the buffer for the next read event.</li>
 *   <li><b>Mutation handling</b> – a decoded {@code MUTATION} frame is passed
 *       to {@link CanvasStateManager#applyMutation}. If the state manager
 *       accepts it (newer timestamp wins), the <em>exact same encoded frame</em>
 *       is broadcast to every other connected client.</li>
 *   <li><b>Write</b> – {@code OP_WRITE} is armed only when a previous write was
 *       partial (TCP send-buffer full). The event loop drains the write queue
 *       on the next writable wake-up and then clears the interest bit.</li>
 *   <li><b>Disconnect</b> – EOF or any I/O error cancels the key and closes
 *       the channel cleanly.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * {@code NioServer} itself is single-threaded. {@link CanvasStateManager}
 * uses a {@link java.util.concurrent.ConcurrentHashMap} and may safely be
 * called from other threads (e.g. an admin console) without coordination.
 */
public final class NioServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(NioServer.class);

    private final int port;
    private final CanvasStateManager stateManager;

    /**
     * Completes with the actual TCP port the server bound to.  Useful when
     * {@code port == 0} is passed to let the OS assign an ephemeral port.
     */
    private final CompletableFuture<Integer> boundPortFuture = new CompletableFuture<>();

    /**
     * Set to {@code true} by {@link #stop()} to break the selector loop
     * without relying on thread interruption.
     */
    private volatile boolean stopped = false;

    /**
     * The live {@link Selector}; stored so {@link #stop()} can call
     * {@link Selector#wakeup()} even while the event loop is blocked in
     * {@link Selector#select()}.
     */
    private volatile Selector selector;

    /**
     * @param port         TCP port to bind; must be in the range [1, 65535]
     * @param stateManager the canvas state authority
     */
    public NioServer(int port, CanvasStateManager stateManager) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        if (stateManager == null)      throw new IllegalArgumentException("stateManager must not be null");
        this.port         = port;
        this.stateManager = stateManager;
    }

    // =========================================================================
    // Main event loop
    // =========================================================================

    /**
     * Starts the NIO event loop.  Blocks the calling thread until interrupted
     * or a fatal {@link IOException} is encountered.
     */
    @Override
    public void run() {
        log.info("NioServer starting on port {}", port);

        try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
             Selector selector = Selector.open()) {

            // Store selector reference before entering the loop so stop() can
            // call wakeup() even if this thread is blocked inside select().
            this.selector = selector;

            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            // Resolve the ephemeral port (works for both port==0 and fixed ports).
            int actualPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            boundPortFuture.complete(actualPort);

            log.info("NioServer listening — port={} minConcurrentClients=4", actualPort);

            while (!stopped && !Thread.currentThread().isInterrupted()) {
                // Blocks until at least one channel becomes ready.
                int readyCount = selector.select();
                if (readyCount == 0) {
                    continue;
                }

                Set<SelectionKey> selected = selector.selectedKeys();
                for (SelectionKey key : selected) {
                    try {
                        dispatch(key, selector);
                    } catch (Exception e) {
                        log.error("Unexpected error dispatching key — closing channel: {}", e.getMessage(), e);
                        closeKey(key);
                    }
                }
                selected.clear();
            }

        } catch (IOException e) {
            log.error("NioServer fatal I/O error — shutting down", e);
            // Fail the future so callers blocked on getBoundPortFuture().get() surface
            // the real cause rather than hanging until their timeout fires.
            boundPortFuture.completeExceptionally(e);
        }

        log.info("NioServer stopped");
    }

    // =========================================================================
    // Dispatch
    // =========================================================================

    private void dispatch(SelectionKey key, Selector selector) throws IOException {
        if (!key.isValid()) return;

        if (key.isAcceptable()) {
            handleAccept((ServerSocketChannel) key.channel(), selector);
        } else {
            if (key.isReadable()) {
                handleRead(key, selector);
            }
            // Re-check validity: handleRead may have cancelled the key on disconnect.
            if (key.isValid() && key.isWritable()) {
                handleWrite(key);
            }
        }
    }

    // =========================================================================
    // Accept
    // =========================================================================

    private void handleAccept(ServerSocketChannel serverChannel, Selector selector) throws IOException {
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null) {
            // Spurious wake-up; the OS withdrew the connection before we accepted.
            return;
        }

        clientChannel.configureBlocking(false);
        // Disable Nagle's algorithm so small frames (cursor updates, text events)
        // are sent immediately without waiting for ACKs or buffer fill.
        clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        // Ensure kernel socket buffers are at least 64 KiB each to absorb
        // burst traffic without stalling the event loop.
        clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, 64 * 1024);
        clientChannel.setOption(StandardSocketOptions.SO_RCVBUF, 64 * 1024);

        ClientSession session = new ClientSession();
        clientChannel.register(selector, SelectionKey.OP_READ, session);

        log.info("Client connected  session={} remote={}",
                session.sessionId, clientChannel.getRemoteAddress());

        sendSnapshot(session, selector.keys().stream()
                .filter(k -> k.attachment() == session)
                .findFirst()
                .orElseThrow());
    }

    // =========================================================================
    // Read
    // =========================================================================

    private void handleRead(SelectionKey key, Selector selector) {
        ClientSession session = (ClientSession) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();

        int bytesRead;
        try {
            bytesRead = channel.read(session.readBuffer);
        } catch (IOException e) {
            log.warn("Read error session={}: {}", session.sessionId, e.getMessage());
            closeKey(key);
            return;
        }

        if (bytesRead == -1) {
            log.info("Client closed connection  session={}", session.sessionId);
            closeKey(key);
            return;
        }

        if (bytesRead == 0) {
            return;
        }

        log.debug("Read {} bytes from session={}", bytesRead, session.sessionId);

        // Flip to read-mode and drain all complete frames.
        session.readBuffer.flip();
        try {
            while (session.readBuffer.hasRemaining()) {
                Message msg;
                try {
                    msg = MessageCodec.decode(session.readBuffer);
                } catch (PartialMessageException e) {
                    // Incomplete frame — wait for more bytes.
                    log.debug("Partial frame session={} bytesNeeded={}",
                            session.sessionId, e.getBytesNeeded());
                    break;
                }
                processMessage(msg, key, selector);
                if (!key.isValid()) {
                    // processMessage may have closed this key on protocol error.
                    return;
                }
            }
        } finally {
            // Move unprocessed bytes to the front of the buffer and switch back to write-mode.
            session.readBuffer.compact();
        }
    }

    // =========================================================================
    // Message dispatch
    // =========================================================================

    private void processMessage(Message msg, SelectionKey senderKey, Selector selector) {
        ClientSession session = (ClientSession) senderKey.attachment();

        switch (msg.type()) {
            case HANDSHAKE -> {
                // Extract authorName and clientId so subsequent shape mutations can
                // be correlated with the originating user in server logs.
                try {
                    JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
                    if (p.has("authorName") && !p.get("authorName").isJsonNull()) {
                        session.authorName = p.get("authorName").getAsString();
                    }
                    if (p.has("clientId") && !p.get("clientId").isJsonNull()) {
                        session.clientId = p.get("clientId").getAsString();
                    }
                } catch (Exception ignored) {
                    // Malformed handshake payload — keep defaults
                }
                log.info("HANDSHAKE session={} authorName='{}' clientId='{}'",
                        session.sessionId, session.authorName, session.clientId);
            }

            case MUTATION -> {
                Shape shape;
                try {
                    shape = ShapeCodec.decodeMutation(msg.payload());
                } catch (Exception e) {
                    log.warn("Malformed MUTATION payload from session={}: {}", session.sessionId, e.getMessage());
                    closeKey(senderKey);
                    return;
                }

                boolean applied = stateManager.applyMutation(shape);

                if (applied) {
                    log.info("MUTATION accepted  type={} id={} ts={} author='{}' from={}",
                            shape.getClass().getSimpleName(), shape.objectId(),
                            shape.timestamp(), shape.authorName(), session.sessionId);

                    ByteBuffer frame = MessageCodec.encode(msg);
                    broadcastExcept(frame, senderKey, selector);
                } else {
                    log.debug("MUTATION rejected (stale)  id={} from={}", shape.objectId(), session.sessionId);
                }
            }

            // Ephemeral live-drawing events — relay to all other clients without
            // touching the canvas state manager (these frames are never persisted).
            case SHAPE_START, SHAPE_UPDATE, SHAPE_COMMIT -> {
                log.debug("{} relayed from session={}", msg.type(), session.sessionId);
                ByteBuffer frame = MessageCodec.encode(msg);
                broadcastExcept(frame, senderKey, selector);
            }

            // Ephemeral live-typing event — relay immediately to all other clients.
            // TEXT_UPDATE frames are transient: they carry in-progress (uncommitted)
            // text and must NOT be written to the persistent shapeMap.  The final
            // committed TextNode arrives as a normal MUTATION once the user confirms.
            case TEXT_UPDATE -> {
                log.debug("TEXT_UPDATE relayed from session={}", session.sessionId);
                ByteBuffer frame = MessageCodec.encode(msg);
                broadcastExcept(frame, senderKey, selector);
            }

            case CLEAR_USER_SHAPES -> {
                String targetClientId;
                try {
                    targetClientId = MessageCodec.decodeClearUserShapes(msg);
                } catch (Exception e) {
                    log.warn("Malformed CLEAR_USER_SHAPES payload from session={}: {}", session.sessionId, e.getMessage());
                    return;
                }
                stateManager.clearUserShapes(targetClientId);
                log.info("CLEAR_USER_SHAPES from session={} targetClientId='{}'",
                        session.sessionId, targetClientId);
                // Broadcast the exact same scoped-clear frame so all peers remove
                // only the shapes belonging to that clientId from their local view.
                ByteBuffer frame = MessageCodec.encodeClearUserShapes(targetClientId);
                broadcastExcept(frame, senderKey, selector);
            }

            case UNDO_REQUEST -> {
                // Payload: { "shapeId": "<uuid-string>" }
                UUID shapeId;
                try {
                    JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
                    shapeId = UUID.fromString(p.get("shapeId").getAsString());
                } catch (Exception e) {
                    log.warn("Malformed UNDO_REQUEST payload from session={}: {}", session.sessionId, e.getMessage());
                    return;
                }

                boolean deleted = stateManager.deleteShape(shapeId);
                if (deleted) {
                    log.info("UNDO_REQUEST accepted  shapeId={} author='{}' session={}",
                            shapeId, session.authorName, session.sessionId);
                    // Notify all OTHER clients to remove the shape from their canvas.
                    record ShapeDeletePayload(String shapeId) {}
                    ByteBuffer frame = MessageCodec.encodeObject(
                            MessageType.SHAPE_DELETE, new ShapeDeletePayload(shapeId.toString()));
                    broadcastExcept(frame, senderKey, selector);
                } else {
                    log.debug("UNDO_REQUEST no-op (shape not found)  shapeId={} session={}",
                            shapeId, session.sessionId);
                }
            }

            default -> log.warn("Unexpected message type={} from session={} — ignoring",
                    msg.type(), session.sessionId);
        }
    }

    // =========================================================================
    // Snapshot delivery
    // =========================================================================

    /**
     * Encodes the current canvas state as a {@code SNAPSHOT} frame and enqueues
     * it for delivery to the newly connected session.
     */
    private void sendSnapshot(ClientSession session, SelectionKey key) {
        List<Shape> shapes = stateManager.snapshot();
        String payload = ShapeCodec.encodeSnapshot(shapes);
        Message snapshotMsg = new Message(MessageType.SNAPSHOT, payload);
        ByteBuffer frame = MessageCodec.encode(snapshotMsg);

        log.info("Sending SNAPSHOT  shapes={} bytes={} to={}",
                shapes.size(), frame.remaining(), session.sessionId);

        session.enqueue(frame);
        flushWriteQueue(session, key);
    }

    // =========================================================================
    // Broadcast
    // =========================================================================

    /**
     * Delivers a frame to all registered client channels <em>except</em> the
     * sender's key.
     *
     * <p>To avoid a {@link java.util.ConcurrentModificationException} if a failed
     * write triggers a key cancellation, keys that error during broadcast are
     * collected and closed <em>after</em> the iteration completes.
     */
    private void broadcastExcept(ByteBuffer frame, SelectionKey senderKey, Selector selector) {
        List<SelectionKey> toClose = new ArrayList<>();
        int recipientCount = 0;

        for (SelectionKey key : selector.keys()) {
            if (!(key.channel() instanceof SocketChannel)
                    || !key.isValid()
                    || key == senderKey) {
                continue;
            }

            ClientSession session = (ClientSession) key.attachment();
            // enqueue() makes an independent copy — safe for multiple recipients.
            session.enqueue(frame);

            if (!flushWriteQueue(session, key)) {
                toClose.add(key);
            } else {
                recipientCount++;
            }
        }

        log.debug("Broadcast sent to {} client(s)", recipientCount);
        toClose.forEach(this::closeKey);
    }

    // =========================================================================
    // Write / drain
    // =========================================================================

    private void handleWrite(SelectionKey key) {
        ClientSession session = (ClientSession) key.attachment();
        if (!flushWriteQueue(session, key)) {
            closeKey(key);
        }
    }

    /**
     * Attempts to drain the session's write queue into the socket's send buffer.
     *
     * <ul>
     *   <li>If the queue is fully drained, {@code OP_WRITE} is cleared.</li>
     *   <li>If the send buffer becomes full mid-write, {@code OP_WRITE} is armed
     *       so the event loop resumes on the next writable wake-up.</li>
     * </ul>
     *
     * @return {@code true} on success; {@code false} if an {@link IOException}
     *         occurred (caller should close the key)
     */
    private boolean flushWriteQueue(ClientSession session, SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();

        try {
            while (!session.writeQueue.isEmpty()) {
                ByteBuffer buf = session.writeQueue.peek();
                channel.write(buf);

                if (buf.hasRemaining()) {
                    // TCP send-buffer is full — arm OP_WRITE and come back later.
                    key.interestOpsOr(SelectionKey.OP_WRITE);
                    log.debug("Write queue stalled (send-buffer full) session={}", session.sessionId);
                    return true;
                }

                session.writeQueue.poll();
            }

            // Queue drained — remove OP_WRITE so we don't spin.
            key.interestOpsAnd(~SelectionKey.OP_WRITE);
            return true;

        } catch (IOException e) {
            log.warn("Write error session={}: {}", session.sessionId, e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Session teardown
    // =========================================================================

    private void closeKey(SelectionKey key) {
        Object attachment = key.attachment();
        String sessionDesc = attachment instanceof ClientSession s
                ? "session=" + s.sessionId
                : "server-channel";

        log.info("Closing channel  {}", sessionDesc);
        key.cancel();

        try {
            key.channel().close();
        } catch (IOException e) {
            log.warn("Error closing channel {}: {}", sessionDesc, e.getMessage());
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * The TCP port configured at construction time.  When {@code 0} was
     * supplied the OS assigns an ephemeral port at bind-time; use
     * {@link #getBoundPortFuture()} to obtain the actual port.
     */
    public int getPort() {
        return port;
    }

    /** The {@link CanvasStateManager} backing this server instance. */
    public CanvasStateManager getStateManager() {
        return stateManager;
    }

    /**
     * Returns a {@link CompletableFuture} that completes with the actual TCP
     * port the server bound to.  Callers should apply a reasonable timeout:
     * <pre>{@code
     * int port = server.getBoundPortFuture().get(3, TimeUnit.SECONDS);
     * }</pre>
     * The future completes exceptionally if the server fails to bind.
     */
    public CompletableFuture<Integer> getBoundPortFuture() {
        return boundPortFuture;
    }

    /**
     * Signals the event loop to exit gracefully.  Calling this from any
     * thread is safe; it sets a flag and wakes the blocked {@link Selector}
     * so the loop exits on its next iteration without relying on
     * {@link Thread#interrupt()}.
     */
    public void stop() {
        stopped = true;
        Selector sel = this.selector;
        if (sel != null && sel.isOpen()) {
            sel.wakeup();
        }
    }
}
