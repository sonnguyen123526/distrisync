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
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
 *       {@code OP_READ} with a fresh {@link ClientSession}.  The client sends
 *       {@code HANDSHAKE} (lobby + {@code LOBBY_STATE}), then {@code JOIN_ROOM}
 *       for a canvas {@code SNAPSHOT}.</li>
 *   <li><b>HANDSHAKE</b> – the first frame must be a {@code HANDSHAKE} with
 *       {@code authorName} and {@code clientId}.  The client is placed in the
 *       global discovery lobby; the server pushes {@code LOBBY_STATE} to all
 *       lobby clients.</li>
 *   <li><b>JOIN_ROOM</b> – client leaves the lobby and enters a canvas room;
 *       the server sends that room's {@code SNAPSHOT}.</li>
 *   <li><b>LEAVE_ROOM</b> – client returns to the lobby and receives a fresh
 *       {@code LOBBY_STATE}.</li>
 *   <li><b>Read / mutation</b> – subsequent frames are dispatched to the
 *       room ({@code session.roomId}) and board ({@code session.currentBoardId}).
 *       Durable canvas ops use {@link RoomContext#getBoard}; relayed frames are
 *       broadcast only to peers in the same room <em>and</em> board.</li>
 *   <li><b>Write</b> – {@code OP_WRITE} is armed only when a previous write
 *       was partial (TCP send-buffer full).</li>
 *   <li><b>Disconnect</b> – the key is removed from the lobby or its canvas
 *       room before the channel is closed.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * {@code NioServer} itself is single-threaded.  {@link CanvasStateManager}
 * and {@link RoomManager} use {@link java.util.concurrent.ConcurrentHashMap}
 * and may safely be called from other threads without coordination.
 */
public final class NioServer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(NioServer.class);

    /** Selector attachment marking the shared UDP {@link DatagramChannel} key (not a {@link ClientSession}). */
    private static final Object UDP_CHANNEL_ATTACHMENT = new Object();

    /** Fixed prefix size for UDP token / {@link ClientSession#clientId} relay header. */
    private static final int UDP_IDENTITY_BYTES = 36;

    private final int port;
    private final RoomManager roomManager;

    /**
     * Maps {@link ClientSession#udpToken} to the owning session for UDP registration and audio relay.
     * {@link ConcurrentHashMap} provides lock-free reads on the selector thread during UDP fan-out.
     */
    private final ConcurrentHashMap<String, ClientSession> udpTokenToSession = new ConcurrentHashMap<>();

    /**
     * Direct buffer for UDP receive / in-place relay header rewrite — avoids JVM heap copies on the
     * I/O path (selector thread only).
     */
    private final ByteBuffer udpBuffer = ByteBuffer.allocateDirect(1024);

    /** Scratch for the 36-byte UDP token prefix; avoids per-datagram {@code byte[]} allocation. */
    private final byte[] udpTokenScratch = new byte[UDP_IDENTITY_BYTES];

    /**
     * Reused on the selector thread to write the 36-byte client-id prefix without {@code String#getBytes}.
     */
    private final CharsetEncoder udpClientIdEncoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);

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
     * Cross-thread lobby broadcasts: {@link RoomManager} may call the fanout from
     * the storage lifecycle thread; frames are drained on the selector thread.
     */
    private final ConcurrentLinkedQueue<ByteBuffer> pendingLobbyFrames = new ConcurrentLinkedQueue<>();

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
             DatagramChannel datagramChannel = DatagramChannel.open();
             Selector selector = Selector.open()) {

            this.selector = selector;

            final Selector selectorRef = selector;
            roomManager.setLobbyFanout(frame -> {
                pendingLobbyFrames.offer(frame);
                selectorRef.wakeup();
            });

            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            int actualPort = ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();

            datagramChannel.configureBlocking(false);
            datagramChannel.bind(new InetSocketAddress(actualPort));
            datagramChannel.register(selector, SelectionKey.OP_READ, UDP_CHANNEL_ATTACHMENT);

            boundPortFuture.complete(actualPort);

            log.info("NioServer listening — tcpUdpPort={} (TCP + UDP) minConcurrentClients=4", actualPort);

            while (!stopped && !Thread.currentThread().isInterrupted()) {
                selector.select();
                drainPendingLobbyBroadcasts();

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
            return;
        }

        if (key.attachment() == UDP_CHANNEL_ATTACHMENT) {
            if (key.isReadable()) {
                handleUdpRead(key);
            }
            return;
        }

        if (key.isReadable()) {
            handleRead(key, selector);
        }
        if (key.isValid() && key.isWritable()) {
            handleWrite(key);
        }
    }

    // =========================================================================
    // Accept
    // =========================================================================

    /**
     * Registers the new channel for reads and attaches a fresh
     * {@link ClientSession}.  The client must send {@code HANDSHAKE} then
     * {@code JOIN_ROOM} before receiving a canvas {@code SNAPSHOT}.
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
                if (session.handshakeComplete) {
                    log.warn("Duplicate HANDSHAKE session={} — ignoring", session.sessionId);
                    break;
                }
                MessageCodec.HandshakePayload hp = MessageCodec.decodeHandshake(msg);
                session.authorName = hp.authorName();
                session.clientId   = hp.clientId();
                session.roomId     = "";
                session.currentBoardId = "";
                session.handshakeComplete = true;

                roomManager.registerHandshakeToLobby(senderKey);

                log.info("HANDSHAKE session={} authorName='{}' clientId='{}' → lobby",
                        session.sessionId, session.authorName, session.clientId);
            }

            case JOIN_ROOM -> {
                if (!session.handshakeComplete) {
                    log.warn("JOIN_ROOM before HANDSHAKE session={}", session.sessionId);
                    break;
                }
                MessageCodec.JoinRoomPayload jp;
                try {
                    jp = MessageCodec.decodeJoinRoom(msg);
                } catch (Exception e) {
                    log.warn("Malformed JOIN_ROOM session={}: {}", session.sessionId, e.getMessage());
                    break;
                }
                String rid = jp.roomId();
                if (rid.isBlank()) {
                    log.warn("Blank JOIN_ROOM session={}", session.sessionId);
                    break;
                }
                try {
                    RoomContext room = roomManager.assignClientToRoom(senderKey, rid, session.roomId);
                    revokeUdpAdmission(session);
                    String udpToken = UUID.randomUUID().toString();
                    session.udpToken = udpToken;
                    udpTokenToSession.put(udpToken, session);
                    session.roomId = rid;
                    session.currentBoardId = jp.initialBoardId();
                    sendSnapshot(session, senderKey, room);
                    sendUdpAdmission(session, senderKey, udpToken);
                    broadcastBoardList(room);
                    log.info("JOIN_ROOM session={} roomId='{}' boardId='{}'",
                            session.sessionId, rid, session.currentBoardId);
                } catch (IllegalArgumentException e) {
                    log.warn("JOIN_ROOM rejected session={}: {}", session.sessionId, e.getMessage());
                }
            }

            case LEAVE_ROOM -> {
                if (!session.handshakeComplete) {
                    break;
                }
                if (session.roomId.isBlank()) {
                    log.debug("LEAVE_ROOM no-op — already in lobby session={}", session.sessionId);
                    break;
                }
                String cur = session.roomId;
                roomManager.returnClientToLobby(senderKey, cur);
                revokeUdpAdmission(session);
                session.roomId = "";
                session.currentBoardId = "";
                log.info("LEAVE_ROOM session={} → lobby", session.sessionId);
            }

            case MUTATION -> {
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                if (session.currentBoardId.isBlank()) {
                    log.warn("MUTATION with no active board session={} — ignoring", session.sessionId);
                    return;
                }

                Shape shape;
                try {
                    shape = ShapeCodec.decodeMutation(msg.payload());
                } catch (Exception e) {
                    log.warn("Malformed MUTATION payload from session={}: {}", session.sessionId, e.getMessage());
                    closeKey(senderKey);
                    return;
                }

                CanvasStateManager board = room.getBoard(session.currentBoardId);
                boolean applied = board.applyMutation(shape);

                if (applied) {
                    log.info("MUTATION accepted  type={} id={} ts={} author='{}' room='{}' board='{}' from={}",
                            shape.getClass().getSimpleName(), shape.objectId(),
                            shape.timestamp(), shape.authorName(), session.roomId, session.currentBoardId,
                            session.sessionId);

                    // Persist to WAL before broadcasting so the record survives a crash
                    // between the apply and the broadcast.
                    roomManager.appendToWal(session.roomId, session.currentBoardId, msg);

                    ByteBuffer frame = MessageCodec.encode(msg);
                    broadcastToBoard(session.roomId, session.currentBoardId, frame, senderKey);
                } else {
                    log.debug("MUTATION rejected (stale)  id={} from={}", shape.objectId(), session.sessionId);
                }
            }

            case SHAPE_START, SHAPE_UPDATE, SHAPE_COMMIT -> {
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                if (session.currentBoardId.isBlank()) {
                    log.warn("{} with no active board session={} — ignoring", msg.type(), session.sessionId);
                    return;
                }
                log.debug("{} relayed  room='{}' board='{}' from session={}",
                        msg.type(), session.roomId, session.currentBoardId, session.sessionId);
                ByteBuffer frame = MessageCodec.encode(msg);
                broadcastToBoard(session.roomId, session.currentBoardId, frame, senderKey);
            }

            case TEXT_UPDATE -> {
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                if (session.currentBoardId.isBlank()) {
                    log.warn("TEXT_UPDATE with no active board session={} — ignoring", session.sessionId);
                    return;
                }
                log.debug("TEXT_UPDATE relayed  room='{}' board='{}' from session={}",
                        session.roomId, session.currentBoardId, session.sessionId);
                ByteBuffer frame = MessageCodec.encode(msg);
                broadcastToBoard(session.roomId, session.currentBoardId, frame, senderKey);
            }

            case CLEAR_USER_SHAPES -> {
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                if (session.currentBoardId.isBlank()) {
                    log.warn("CLEAR_USER_SHAPES with no active board session={} — ignoring", session.sessionId);
                    return;
                }

                String targetClientId;
                try {
                    targetClientId = MessageCodec.decodeClearUserShapes(msg);
                } catch (Exception e) {
                    log.warn("Malformed CLEAR_USER_SHAPES payload from session={}: {}", session.sessionId, e.getMessage());
                    return;
                }
                CanvasStateManager board = room.getBoard(session.currentBoardId);
                board.clearUserShapes(targetClientId);
                log.info("CLEAR_USER_SHAPES  room='{}' board='{}' from session={} targetClientId='{}'",
                        session.roomId, session.currentBoardId, session.sessionId, targetClientId);

                // Persist to WAL so the per-user purge survives a restart.
                roomManager.appendToWal(session.roomId, session.currentBoardId, msg);

                ByteBuffer frame = MessageCodec.encodeClearUserShapes(targetClientId);
                broadcastToBoard(session.roomId, session.currentBoardId, frame, senderKey);
            }

            case UNDO_REQUEST -> {
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                if (session.currentBoardId.isBlank()) {
                    log.warn("UNDO_REQUEST with no active board session={} — ignoring", session.sessionId);
                    return;
                }

                UUID shapeId;
                try {
                    JsonObject p = JsonParser.parseString(msg.payload()).getAsJsonObject();
                    shapeId = UUID.fromString(p.get("shapeId").getAsString());
                } catch (Exception e) {
                    log.warn("Malformed UNDO_REQUEST payload from session={}: {}", session.sessionId, e.getMessage());
                    return;
                }

                CanvasStateManager board = room.getBoard(session.currentBoardId);
                boolean deleted = board.deleteShape(shapeId);
                if (deleted) {
                    log.info("UNDO_REQUEST accepted  shapeId={} room='{}' board='{}' author='{}' session={}",
                            shapeId, session.roomId, session.currentBoardId, session.authorName, session.sessionId);
                    record ShapeDeletePayload(String shapeId) {}
                    var deletePayload = new ShapeDeletePayload(shapeId.toString());
                    Message deleteMsg = new Message(
                            MessageType.SHAPE_DELETE, MessageCodec.gson().toJson(deletePayload));

                    // Persist the SHAPE_DELETE outcome (not the UNDO_REQUEST trigger) so
                    // recovery can apply a clean deleteShape() without re-evaluating intent.
                    roomManager.appendToWal(session.roomId, session.currentBoardId, deleteMsg);

                    ByteBuffer frame = MessageCodec.encode(deleteMsg);
                    broadcastToBoard(session.roomId, session.currentBoardId, frame, senderKey);
                } else {
                    log.debug("UNDO_REQUEST no-op (shape not found)  shapeId={} session={}",
                            shapeId, session.sessionId);
                }
            }

            case SWITCH_BOARD -> {
                if (!session.handshakeComplete) {
                    log.warn("SWITCH_BOARD before HANDSHAKE session={}", session.sessionId);
                    break;
                }
                RoomContext room = resolveRoom(session, senderKey);
                if (room == null) return;
                String boardId;
                try {
                    boardId = MessageCodec.decodeSwitchBoard(msg);
                } catch (Exception e) {
                    log.warn("Malformed SWITCH_BOARD session={}: {}", session.sessionId, e.getMessage());
                    break;
                }
                String bid = boardId != null ? boardId.strip() : "";
                if (bid.isBlank()) {
                    log.warn("Blank SWITCH_BOARD session={}", session.sessionId);
                    break;
                }
                boolean boardExisted = room.getActiveBoardIds().contains(bid);
                session.currentBoardId = bid;
                sendSnapshot(session, senderKey, room);
                if (!boardExisted) {
                    broadcastBoardList(room);
                }
                log.info("SWITCH_BOARD session={} room='{}' boardId='{}'", session.sessionId, session.roomId, bid);
            }

            case LOBBY_STATE -> log.trace("Ignoring client-originated LOBBY_STATE echo session={}", session.sessionId);

            default -> log.warn("Unexpected message type={} from session={} — ignoring",
                    msg.type(), session.sessionId);
        }
    }

    /**
     * Resolves the {@link RoomContext} for the given session.  Logs a warning
     * and returns {@code null} if the client is still in the lobby or has not
     * completed {@code JOIN_ROOM} ({@code session.roomId} is blank).
     */
    private RoomContext resolveRoom(ClientSession session, SelectionKey key) {
        if (session.roomId.isBlank()) {
            log.warn("Canvas message while in lobby (send JOIN_ROOM first) session={} — ignoring",
                    session.sessionId);
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
    private void revokeUdpAdmission(ClientSession session) {
        String tok = session.udpToken;
        if (tok != null && !tok.isBlank()) {
            udpTokenToSession.remove(tok, session);
        }
        session.udpToken = "";
        session.udpEndpoint = null;
    }

    private void sendUdpAdmission(ClientSession session, SelectionKey key, String udpToken) {
        ByteBuffer frame = MessageCodec.encodeUdpAdmission(udpToken);
        session.enqueue(frame);
        if (!flushWriteQueue(session, key)) {
            log.error("UDP_ADMISSION flush failed for session={} — closing connection", session.sessionId);
            closeKey(key);
        }
    }

    private void handleUdpRead(SelectionKey key) {
        DatagramChannel dc = (DatagramChannel) key.channel();
        udpBuffer.clear();
        final SocketAddress senderAddr;
        try {
            senderAddr = dc.receive(udpBuffer);
        } catch (IOException e) {
            log.warn("UDP receive I/O error: {}", e.getMessage());
            return;
        }
        if (senderAddr == null) {
            return;
        }
        if (!(senderAddr instanceof InetSocketAddress sender)) {
            return;
        }
        udpBuffer.flip();
        int len = udpBuffer.remaining();
        if (len < UDP_IDENTITY_BYTES) {
            return;
        }

        udpBuffer.get(udpTokenScratch);
        String token = new String(udpTokenScratch, StandardCharsets.UTF_8);

        if (len == UDP_IDENTITY_BYTES) {
            ClientSession session = udpTokenToSession.get(token);
            if (session == null) {
                return;
            }
            session.udpEndpoint = sender;
            log.debug("UDP endpoint registered  session={} remote={}", session.sessionId, sender);
            return;
        }

        ClientSession speaker = udpTokenToSession.get(token);
        if (speaker == null) {
            return;
        }
        InetSocketAddress registered = speaker.udpEndpoint;
        if (registered == null || !registered.equals(sender)) {
            return;
        }
        if (speaker.roomId == null || speaker.roomId.isBlank()) {
            return;
        }
        RoomContext room = roomManager.getRoom(speaker.roomId);
        if (room == null) {
            return;
        }

        udpBuffer.position(0);
        writeClientIdPrefix36(udpBuffer, speaker.clientId);
        udpBuffer.limit(len);

        for (SelectionKey peerKey : room.getActiveKeys()) {
            if (!peerKey.isValid() || !(peerKey.attachment() instanceof ClientSession peer)) {
                continue;
            }
            if (peer == speaker) {
                continue;
            }
            InetSocketAddress dest = peer.udpEndpoint;
            if (dest == null) {
                continue;
            }
            try {
                udpBuffer.rewind();
                dc.send(udpBuffer, dest);
            } catch (IOException e) {
                log.debug("UDP relay send to {} failed: {}", dest, e.getMessage());
            }
        }
    }

    /**
     * Writes exactly {@value #UDP_IDENTITY_BYTES} bytes of UTF-8 for {@code clientId}, zero-padded.
     * Uses {@link #udpClientIdEncoder} so the hot path does not allocate a {@code CharsetEncoder} or {@code byte[]}.
     */
    private void writeClientIdPrefix36(ByteBuffer dst, String clientId) {
        int start = dst.position();
        int oldLimit = dst.limit();
        CharBuffer in = CharBuffer.wrap(clientId != null ? clientId : "");
        try {
            dst.limit(start + UDP_IDENTITY_BYTES);
            udpClientIdEncoder.reset();
            udpClientIdEncoder.encode(in, dst, true);
            CoderResult flush = udpClientIdEncoder.flush(dst);
            if (flush.isOverflow()) {
                dst.position(start + UDP_IDENTITY_BYTES);
            }
        } finally {
            dst.limit(oldLimit);
        }
        while (dst.position() < start + UDP_IDENTITY_BYTES) {
            dst.put((byte) 0);
        }
    }

    private void sendSnapshot(ClientSession session, SelectionKey key, RoomContext room) {
        List<Shape> shapes = room.getBoard(session.currentBoardId).snapshot();
        String payload = ShapeCodec.encodeSnapshot(shapes);
        Message snapshotMsg = new Message(MessageType.SNAPSHOT, payload);
        ByteBuffer frame = MessageCodec.encode(snapshotMsg);

        log.info("Sending SNAPSHOT  room='{}' shapes={} bytes={} to={}",
                room.roomId, shapes.size(), frame.remaining(), session.sessionId);

        session.enqueue(frame);
        if (!flushWriteQueue(session, key)) {
            log.error("SNAPSHOT flush failed for session={} — closing connection", session.sessionId);
            closeKey(key);
        }
    }

    // =========================================================================
    // Lobby broadcast
    // =========================================================================

    private void drainPendingLobbyBroadcasts() {
        ByteBuffer frame;
        while ((frame = pendingLobbyFrames.poll()) != null) {
            deliverLobbyStateFrame(frame);
        }
    }

    /**
     * Enqueues one {@code LOBBY_STATE} frame to every client currently in the lobby.
     * Must run on the selector thread.
     */
    private void deliverLobbyStateFrame(ByteBuffer frame) {
        List<SelectionKey> keys = roomManager.snapshotLobbyKeys();
        List<SelectionKey> toClose = new ArrayList<>();

        for (SelectionKey key : keys) {
            if (!key.isValid()) {
                continue;
            }
            if (!(key.attachment() instanceof ClientSession session)) {
                continue;
            }
            session.enqueue(frame);
            if (!flushWriteQueue(session, key)) {
                toClose.add(key);
            }
        }

        log.debug("LOBBY_STATE fan-out  lobbyClients={}", keys.size());
        toClose.forEach(this::closeKey);
    }

    // =========================================================================
    // Room + board scoped broadcast
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
     * @param boardId   only clients whose {@link ClientSession#currentBoardId} matches
     * @param frame     the encoded binary frame to deliver (read-mode)
     * @param senderKey the originating client's key; excluded from delivery
     */
    private void broadcastToBoard(String roomId, String boardId, ByteBuffer frame, SelectionKey senderKey) {
        var activeKeys = roomManager.getActiveClientKeys(roomId);
        if (activeKeys.isEmpty()) {
            if (roomManager.getRoom(roomId) == null) {
                log.warn("broadcastToBoard called for unknown roomId='{}'", roomId);
            }
            return;
        }

        List<SelectionKey> toClose = new ArrayList<>();
        int recipientCount = 0;

        for (SelectionKey key : activeKeys) {
            if (!key.isValid() || key == senderKey) {
                continue;
            }

            ClientSession session = (ClientSession) key.attachment();
            if (!boardId.equals(session.currentBoardId)) {
                continue;
            }

            session.enqueue(frame);

            if (!flushWriteQueue(session, key)) {
                toClose.add(key);
            } else {
                recipientCount++;
            }
        }

        log.debug("Board broadcast  roomId='{}' boardId='{}' recipients={}", roomId, boardId, recipientCount);
        toClose.forEach(this::closeKey);
    }

    /**
     * Broadcasts {@link MessageType#BOARD_LIST_UPDATE} to every TCP client currently in
     * {@code room} (all boards). Uses a deterministic lexicographic ordering of ids on the wire.
     */
    private void broadcastBoardList(RoomContext room) {
        List<String> ids = new ArrayList<>(room.getActiveBoardIds());
        Collections.sort(ids);
        ByteBuffer frame = MessageCodec.encodeBoardListUpdate(ids);

        List<SelectionKey> toClose = new ArrayList<>();
        int recipientCount = 0;

        for (SelectionKey key : room.getActiveKeys()) {
            if (!key.isValid()) {
                continue;
            }
            if (!(key.attachment() instanceof ClientSession peer)) {
                continue;
            }
            peer.enqueue(frame);
            if (!flushWriteQueue(peer, key)) {
                toClose.add(key);
            } else {
                recipientCount++;
            }
        }

        log.debug("BOARD_LIST_UPDATE fan-out  roomId='{}' boardCount={} recipients={}",
                room.roomId, ids.size(), recipientCount);
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
            revokeUdpAdmission(s);
            if (roomManager.isInLobby(key)) {
                roomManager.removeFromLobby(key);
            } else if (!s.roomId.isBlank()) {
                roomManager.removeClientFromRoom(s.roomId, key);
            }
            log.info("Closing channel  session={} room='{}'", s.sessionId,
                    s.roomId.isBlank() ? "(lobby)" : s.roomId);
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
     * The live UDP token → session registry (used by {@link #handleUdpRead}).
     * Package-private for tests in {@code com.distrisync.server}.
     */
    ConcurrentHashMap<String, ClientSession> udpTokenRegistryForTests() {
        return udpTokenToSession;
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
