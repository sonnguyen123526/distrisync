package com.distrisync.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * One-byte discriminator that occupies the first byte of every DistriSync
 * binary frame.
 *
 * <pre>
 * Wire value  Meaning
 * ----------  -------
 * 0x01        HANDSHAKE    – initial client→server greeting (authorName, clientId)
 * 0x02        SNAPSHOT     – full board state sent by server on join
 * 0x03        MUTATION     – incremental shape add / update
 * 0x04        UDP_POINTER  – ephemeral cursor-position broadcast (fire-and-forget)
 * 0x05        SHAPE_START  – peer begins drawing a new shape (tool, color, origin)
 * 0x06        SHAPE_UPDATE – incremental coordinate update for an in-progress shape
 * 0x07        SHAPE_COMMIT – peer finished drawing; peers should flush their transient view
 * 0x08        CLEAR_USER_SHAPES – erase all shapes owned by the requesting clientId; server broadcasts to all peers
 * 0x09        UNDO_REQUEST  – client requests deletion of one shape by UUID (payload: shapeId)
 * 0x0A        SHAPE_DELETE  – server confirms deletion; broadcast to all peers (payload: shapeId)
 * 0x0B        TEXT_UPDATE   – ephemeral live-typing event; relayed to all peers without persistence
 *                             payload: { objectId, clientId, x, y, currentText }
 * </pre>
 */
public enum MessageType {

    HANDSHAKE   ((byte) 0x01),
    SNAPSHOT    ((byte) 0x02),
    MUTATION    ((byte) 0x03),
    UDP_POINTER ((byte) 0x04),
    SHAPE_START ((byte) 0x05),
    SHAPE_UPDATE((byte) 0x06),
    SHAPE_COMMIT((byte) 0x07),
    CLEAR_USER_SHAPES((byte) 0x08),
    UNDO_REQUEST((byte) 0x09),
    SHAPE_DELETE((byte) 0x0A),
    TEXT_UPDATE ((byte) 0x0B);

    private final byte wireCode;

    private static final Map<Byte, MessageType> BY_CODE;

    static {
        BY_CODE = new HashMap<>();
        for (MessageType t : values()) {
            BY_CODE.put(t.wireCode, t);
        }
    }

    MessageType(byte wireCode) {
        this.wireCode = wireCode;
    }

    /** The single byte written to (or read from) the wire. */
    public byte wireCode() {
        return wireCode;
    }

    /**
     * Reverse-lookup by wire byte.
     *
     * @throws IllegalArgumentException for unknown codes, so the codec can
     *         surface a clean error rather than a silent {@code null}.
     */
    public static MessageType fromWireCode(byte code) {
        MessageType type = BY_CODE.get(code);
        if (type == null) {
            throw new IllegalArgumentException(
                    String.format("Unknown MessageType wire code: 0x%02X", code));
        }
        return type;
    }
}
