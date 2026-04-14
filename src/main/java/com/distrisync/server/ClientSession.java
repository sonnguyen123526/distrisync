package com.distrisync.server;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * Mutable per-connection state attached to a {@link java.nio.channels.SelectionKey}.
 *
 * <h2>Read buffer</h2>
 * A single 64 KiB {@link ByteBuffer} used for accumulating incoming TCP bytes.
 * The NIO event loop appends newly received bytes, then flips the buffer to
 * decode complete frames in a loop, and finally compacts it to retain any
 * partial tail for the next read.
 *
 * <h2>Write queue</h2>
 * An {@link ArrayDeque} of outbound {@link ByteBuffer}s. Each enqueued buffer
 * is a self-contained copy with its own position so that partial writes are
 * correctly resumed on the next {@code OP_WRITE} wake-up. When the queue is
 * empty the corresponding {@code OP_WRITE} interest bit is cleared from the key.
 */
final class ClientSession {

    /** Stable server-assigned identity used in logs. */
    final UUID sessionId = UUID.randomUUID();

    /**
     * Human-readable display name supplied by the client in its {@code HANDSHAKE}
     * frame.  Defaults to an empty string for sessions that have not yet completed
     * their handshake.  Written only once (on HANDSHAKE receipt); reads are safe
     * from the single-threaded NIO event loop.
     */
    volatile String authorName = "";

    /**
     * Session-scoped client identifier supplied in the {@code HANDSHAKE} frame.
     * Correlates shapes to the originating client session across the board state.
     */
    volatile String clientId = "";

    /**
     * Canvas room id after a successful {@code JOIN_ROOM}; empty while the
     * client is in the discovery lobby.
     */
    volatile String roomId = "";

    /**
     * Active workspace board within {@link #roomId}, set on {@code JOIN_ROOM}
     * (default {@value com.distrisync.protocol.MessageCodec#DEFAULT_INITIAL_BOARD_ID})
     * or {@code SWITCH_BOARD}; empty in the lobby.
     */
    volatile String currentBoardId = "";

    /**
     * Set after the first {@code HANDSHAKE} is accepted; duplicate handshakes are ignored.
     */
    volatile boolean handshakeComplete = false;

    /**
     * Opaque token for the UDP audio data plane; issued on successful {@code JOIN_ROOM}.
     */
    volatile String udpToken = "";

    /**
     * Client UDP endpoint after a 36-byte token registration datagram; {@code null} until registered.
     */
    volatile InetSocketAddress udpEndpoint = null;

    /**
     * Accumulation buffer for inbound bytes.
     * 64 KiB covers any realistic single MUTATION frame; the server never
     * receives SNAPSHOT messages (only sends them).
     */
    final ByteBuffer readBuffer = ByteBuffer.allocate(64 * 1024);

    /**
     * FIFO queue of outbound frames waiting to be drained to the socket.
     * Buffers are stored in write-ready state (position = next byte to send,
     * limit = end of data).
     */
    final Deque<ByteBuffer> writeQueue = new ArrayDeque<>();

    /**
     * Enqueues a frame for delivery to this client.
     *
     * <p>The caller must pass a buffer that is ready to read (position at start,
     * limit at end of data). A fresh copy is made so the caller's buffer is
     * not consumed and can safely be passed to multiple sessions.
     *
     * @param data the frame to enqueue; its bytes are copied into a new buffer
     */
    void enqueue(ByteBuffer data) {
        // duplicate() creates an independent view with its own position/limit so
        // that advancing the copy's position does not consume the caller's buffer.
        // This lets broadcastExcept() safely pass the same frame to N sessions.
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data.duplicate());
        copy.flip();
        writeQueue.addLast(copy);
    }

    @Override
    public String toString() {
        return "ClientSession[" + sessionId + "]";
    }
}
