package com.distrisync.protocol;

/**
 * Thrown by {@link MessageCodec#decode(java.nio.ByteBuffer)} when the
 * supplied buffer does not contain a complete frame.
 *
 * <p>The caller must accumulate more bytes (e.g. from a TCP read loop) and
 * retry. The buffer's position is reset to its state before {@code decode}
 * was called, so it is safe to retry without repopulating the buffer.
 */
public final class PartialMessageException extends RuntimeException {

    /** How many additional bytes are needed, or {@code -1} if unknown. */
    private final int bytesNeeded;

    public PartialMessageException(String message) {
        super(message);
        this.bytesNeeded = -1;
    }

    public PartialMessageException(String message, int bytesNeeded) {
        super(message);
        this.bytesNeeded = bytesNeeded;
    }

    /**
     * @return the number of additional bytes required to complete the frame,
     *         or {@code -1} when only the header is missing (payload length unknown).
     */
    public int getBytesNeeded() {
        return bytesNeeded;
    }
}
