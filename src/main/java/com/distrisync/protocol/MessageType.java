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
 * 0x01        HANDSHAKE   – initial client→server greeting (session id, capabilities)
 * 0x02        SNAPSHOT    – full board state sent by server on join
 * 0x03        MUTATION    – incremental shape add / update / delete
 * 0x04        UDP_POINTER – ephemeral cursor-position broadcast (fire-and-forget)
 * </pre>
 */
public enum MessageType {

    HANDSHAKE  ((byte) 0x01),
    SNAPSHOT   ((byte) 0x02),
    MUTATION   ((byte) 0x03),
    UDP_POINTER((byte) 0x04);

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
