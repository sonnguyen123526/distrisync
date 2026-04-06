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
 * A single-threaded NIO event loop driven by a {@link Selector}.  All client
 * connections share the same selector; room-level isolation is enforced
 * entirely in software via the {@link RoomManager} routing layer.
 *
 * <h2>Multi-tenant connection lifecycle</h2>
 * <ol>
 *   <li><b>Accept</b> – a new {@link SocketChannel} is registered for
 *       {@code OP_READ} with a fresh {@link ClientSession} as the attachment.
 *       <em>No snapshot is sent yet</em>; the server waits for a HANDSHAKE
 *       to learn which room the client wants to join.</li>
 *   <li><b>HANDSHAKE</b> – the first frame from a connected client must be a
 *       {@code HANDSHAKE} carrying {@code authorName}, {@code clientId}, and
 *       the new {@code roomId}.  The server registers the key in the
 *       matching {@link RoomContext} (creating one if necessary) and sends
 *       that room's {@code SNAPSHOT} to the new client only.</li>
 *   <li><b>Read / mutation</b> – subsequent frames are dispatched to the
 *       room identified by {@code session.roomId}.  Mutations go through
 *       the room's own {@link CanvasStateManager} and are broadcast only to
 *       other clients in the <em>same room</em>.</li>
 *   <li><b>Write</b> – {@code OP_WRITE} is armed only when a previous write
 *       was partial (TCP send-buffer full).</li>
 *   <li><b>Disconnect</b> – the key is removed from its room's active-key
 *       set before the channel is closed.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * {@code NioServer} itself is single-threaded.  {@link CanvasStateManager}
 * and {@link RoomManager} use {@link java.util.concurrent.ConcurrentHashMap}
 * and may safely be called from other threads without coordination.
 */
public final class NioServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(NioServer.class);

    private final int port;
    private final RoomManager roomManager;

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
     * @param port        TCP port to bind; must be in the range [0, 65535]
     * @param roomManager the multi-tenant routing registry
     */
    public NioServer(int port, RoomManager roomManager) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port: " + port);
        if (roomManager == null)       throw new IllegalArgumentException("roomManager must not be null");
        this.port        = port;
        this.roomManager = roomManager;
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

            this.selector = selector;

            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            int actualPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
            boundPortFuture.complete(actualPort);

            log.info("NioServer listening — port={} minConcurrentClients=4", actualPort);

            while (!stopped && !Thread.currentThread().isInterrupted()) {
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
            if (key.isValid() && key.isWritable()) {
                handleWrite(key);
            }
        }
    }

    // =========================================================================
    // Accept
    // =========================================================================

    /**
     * Registers the new channel for reads and attaches a fresh
     * {@link ClientSession}.  <em>No snapshot is sent here</em>; the client
     * must send a {@code HANDSHAKE} first so the server knows which room to
     * join it to.
     */
    private void handleAccept(ServerSocketChannel serverChannel, Selector selector) throws IOException {
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null) {
            return;
        }

        clientChannel.configureBlocking(false);
        clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, 64 * 1024);
        clientChannel.setOption(StandardSocketOptions.SO_RCVBUF, 64 * 1024);

        ClientSession session = new ClientSession();
        clientChannel.register(selector, SelectionKey.OP_READ, session);

        log.info("Client connected  session={} remote={} — awaiting HANDSHAKE",
                session.sessionId, clientChannel.getRemoteAddress());
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

        session.readBuffer.flip();
        try {
            while (session.readBuffer.hasRemaining()) {
                Message msg;
                try {
                    msg = MessageCodec.decode(session.readBuffer);
                } catch (PartialMessageException e) {
                    log.debug("Partial frame session={} bytesNeeded={}",
                            session.sessionId, e.getBytesNeeded());
                    break;
                }
                processMessage(msg, key, selector);
                if (!key.isValid()) {
                    return;
                }
            }
        } finally {
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
                try {
                    JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
                    if (p.has("authorName") && !p.get("authorName").isJsonNull()) {
                        session.authorName = p.get("authorName").getAsString();
                    }
                    if (p.has("clientId") && !p.get("clientId").isJsonNull()) {
                        session.clientId = p.get("clientId").getAsString();
                    }
                    if (p.has("roomId") && !p.get("roomId").isJsonNull()) {
                        String rid = p.get("roomId").getAsString().strip();
                        session.roomId = rid.isBlank() ? "Global" : rid;
                    } else {
                        session.roomId = "Global";
                    }
                } catch (Exception ignored) {
                    session.roomId = "Global";
                }

                // Register this key with the room and send the room's snapshot.
                RoomContext room = roomManager.getOrCreateRoom(session.roomId);
                room.addKey(senderKey);

                log.info("HANDSHAKE session={} authorName='{}' clientId='{}' roomId='{}'",
                        session.sessionId, session.authorName, session.clientId, session.roomId);

                sendSnapshot(session, senderKey, room);
            }

            case MUTATION -> {
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;

                Shape shape;
                try {
                    shape = ShapeCodec.decodeMutation(msg.payload());
                } catch (Exception e) {
                    log.warn("Malformed MUTATION payload from session={}: {}", session.sessionId, e.getMessage());
                    closeKey(senderKey);
                    return;
                }

                boolean applied = room.stateManager.applyMutation(shape);

                if (applied) {
                    log.info("MUTATION accepted  type={} id={} ts={} author='{}' room='{}' from={}",
                            shape.getClass().getSimpleName(), shape.objectId(),
                            shape.timestamp(), shape.authorName(), session.roomId, session.sessionId);

                    // Persist to WAL before broadcasting so the record survives a crash
                    // between the apply and the broadcast.
                    roomManager.appendToWal(session.roomId, msg);

                    ByteBuffer frame = MessageCodec.encode(msg);
                    broadcastToRoom(session.roomId, frame, senderKey);
                } else {
                    log.debug("MUTATION rejected (stale)  id={} from={}", shape.objectId(), session.sessionId);
                }
            }

            case SHAPE_START, SHAPE_UPDATE, SHAPE_COMMIT -> {
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                log.debug("{} relayed  room='{}' from session={}", msg.type(), session.roomId, session.sessionId);
                ByteBuffer frame = MessageCodec.encode(msg);
                broadcastToRoom(session.roomId, frame, senderKey);
            }

            case TEXT_UPDATE -> {
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                log.debug("TEXT_UPDATE relayed  room='{}' from session={}", session.roomId, session.sessionId);
                ByteBuffer frame = MessageCodec.encode(msg);
                broadcastToRoom(session.roomId, frame, senderKey);
            }

            case CLEAR_USER_SHAPES -> {
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;

                String targetClientId;
                try {
                    targetClientId = MessageCodec.decodeClearUserShapes(msg);
                } catch (Exception e) {
                    log.warn("Malformed CLEAR_USER_SHAPES payload from session={}: {}", session.sessionId, e.getMessage());
                    return;
                }
                room.stateManager.clearUserShapes(targetClientId);
                log.info("CLEAR_USER_SHAPES  room='{}' from session={} targetClientId='{}'",
                        session.roomId, session.sessionId, targetClientId);

                // Persist to WAL so the per-user purge survives a restart.
                roomManager.appendToWal(session.roomId, msg);

                ByteBuffer frame = MessageCodec.encodeClearUserShapes(targetClientId);
                broadcastToRoom(session.roomId, frame, senderKey);
            }

            case UNDO_REQUEST -> {
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;

                UUID shapeId;
                try {
                    JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
                    shapeId = UUID.fromString(p.get("shapeId").getAsString());
                } catch (Exception e) {
                    log.warn("Malformed UNDO_REQUEST payload from session={}: {}", session.sessionId, e.getMessage());
                    return;
                }

                boolean deleted = room.stateManager.deleteShape(shapeId);
                if (deleted) {
                    log.info("UNDO_REQUEST accepted  shapeId={} room='{}' author='{}' session={}",
                            shapeId, session.roomId, session.authorName, session.sessionId);
                    record ShapeDeletePayload(String shapeId) {}
                    var deletePayload = new ShapeDeletePayload(shapeId.toString());
                    Message deleteMsg = new Message(
                            MessageType.SHAPE_DELETE, MessageCodec.gson().toJson(deletePayload));

                    // Persist the SHAPE_DELETE outcome (not the UNDO_REQUEST trigger) so
                    // recovery can apply a clean deleteShape() without re-evaluating intent.
                    roomManager.appendToWal(session.roomId, deleteMsg);

                    ByteBuffer frame = MessageCodec.encode(deleteMsg);
                    broadcastToRoom(session.roomId, frame, senderKey);
                } else {
                    log.debug("UNDO_REQUEST no-op (shape not found)  shapeId={} session={}",
                            shapeId, session.sessionId);
                }
            }

            default -> log.warn("Unexpected message type={} from session={} — ignoring",
                    msg.type(), session.sessionId);
        }
    }

    /**
     * Resolves the {@link RoomContext} for the given session.  Logs a warning
     * and returns {@code null} if the session has not yet completed its
     * HANDSHAKE (i.e. {@code roomId} is still blank).
     */
    private RoomContext resolveRoom(ClientSession session, SelectionKey key) {
        if (session.roomId.isBlank()) {
            log.warn("Message received before HANDSHAKE from session={} — ignoring", session.sessionId);
            return null;
        }
        RoomContext room = roomManager.getRoom(session.roomId);
        if (room == null) {
            log.warn("Unknown roomId='{}' for session={} — ignoring", session.roomId, session.sessionId);
            return null;
        }
        return room;
    }

    // =========================================================================
    // Snapshot delivery
    // =========================================================================

    /**
     * Encodes the room's current canvas state as a {@code SNAPSHOT} frame and
     * enqueues it for delivery to the newly joined session.
     */
    private void sendSnapshot(ClientSession session, SelectionKey key, RoomContext room) {
        List<Shape> shapes = room.stateManager.snapshot();
        String payload = ShapeCodec.encodeSnapshot(shapes);
        Message snapshotMsg = new Message(MessageType.SNAPSHOT, payload);
        ByteBuffer frame = MessageCodec.encode(snapshotMsg);

        log.info("Sending SNAPSHOT  room='{}' shapes={} bytes={} to={}",
                room.roomId, shapes.size(), frame.remaining(), session.sessionId);

        session.enqueue(frame);
        flushWriteQueue(session, key);
    }

    // =========================================================================
    // Room-scoped broadcast
    // =========================================================================

    /**
     * Delivers {@code frame} to every active client in {@code roomId}
     * <em>except</em> the sender.
     *
     * <p>Only keys registered in that specific {@link RoomContext} are
     * considered; clients in other rooms never see this frame.  Keys that
     * fail during the write are collected and closed after the iteration
     * to avoid a {@link java.util.ConcurrentModificationException}.
     *
     * @param roomId    the target room identifier
     * @param frame     the encoded binary frame to deliver (read-mode)
     * @param senderKey the originating client's key; excluded from delivery
     */
    private void broadcastToRoom(String roomId, ByteBuffer frame, SelectionKey senderKey) {
        RoomContext room = roomManager.getRoom(roomId);
        if (room == null) {
            log.warn("broadcastToRoom called for unknown roomId='{}'", roomId);
            return;
        }

        List<SelectionKey> toClose = new ArrayList<>();
        int recipientCount = 0;

        for (SelectionKey key : room.getActiveKeys()) {
            if (!key.isValid() || key == senderKey) {
                continue;
            }

            ClientSession session = (ClientSession) key.attachment();
            session.enqueue(frame);

            if (!flushWriteQueue(session, key)) {
                toClose.add(key);
            } else {
                recipientCount++;
            }
        }

        log.debug("Room broadcast  roomId='{}' recipients={}", roomId, recipientCount);
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
                    key.interestOpsOr(SelectionKey.OP_WRITE);
                    log.debug("Write queue stalled (send-buffer full) session={}", session.sessionId);
                    return true;
                }

                session.writeQueue.poll();
            }

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

        if (attachment instanceof ClientSession s) {
            // Remove from the room's active-key set before cancelling the key.
            if (!s.roomId.isBlank()) {
                roomManager.removeClientFromRoom(s.roomId, key);
            }
            log.info("Closing channel  session={} room='{}'", s.sessionId, s.roomId);
        } else {
            log.info("Closing server channel");
        }

        key.cancel();

        try {
            key.channel().close();
        } catch (IOException e) {
            log.warn("Error closing channel: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /** The TCP port configured at construction time. */
    public int getPort() {
        return port;
    }

    /** The {@link RoomManager} backing this server instance. */
    public RoomManager getRoomManager() {
        return roomManager;
    }

    /**
     * Returns a {@link CompletableFuture} that completes with the actual TCP
     * port the server bound to.
     */
    public CompletableFuture<Integer> getBoundPortFuture() {
        return boundPortFuture;
    }

    /**
     * Signals the event loop to exit gracefully.  Safe to call from any thread.
     */
    public void stop() {
        stopped = true;
        Selector sel = this.selector;
        if (sel != null && sel.isOpen()) {
            sel.wakeup();
        }
    }
}
