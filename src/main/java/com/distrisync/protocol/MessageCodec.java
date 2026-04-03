package com.distrisync.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stateless utility that encodes/decodes DistriSync binary frames.
 *
 * <h2>Frame layout</h2>
 * <pre>
 * ┌───────────────┬──────────────────────────┬────────────────────────────┐
 * │  Byte 0       │  Bytes 1-4               │  Bytes 5 … (5 + length-1)  │
 * │  MessageType  │  PayloadLength (int32 BE) │  UTF-8 JSON payload        │
 * │  (1 byte)     │  (4 bytes, big-endian)    │  (variable)                │
 * └───────────────┴──────────────────────────┴────────────────────────────┘
 * </pre>
 *
 * <ul>
 *   <li>Total header is always {@value #HEADER_BYTES} bytes.</li>
 *   <li>PayloadLength is a signed 32-bit big-endian integer; a negative value
 *       is treated as a protocol error.</li>
 *   <li>All {@code decode} overloads are <em>non-destructive on partial input</em>:
 *       if the buffer does not hold a full frame the position is rewound to its
 *       original value before throwing {@link PartialMessageException}.</li>
 * </ul>
 *
 * <p>This class is thread-safe; the shared {@link Gson} instance is immutable
 * after construction.
 */
public final class MessageCodec {

    /** Fixed header size: 1 byte type + 4 bytes length. */
    public static final int HEADER_BYTES = 5;

    /**
     * Maximum accepted payload size (16 MiB). Protects against malformed frames
     * that declare an absurdly large length before we attempt an allocation.
     */
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private MessageCodec() { /* utility class */ }

    // -------------------------------------------------------------------------
    // Encode
    // -------------------------------------------------------------------------

    /**
     * Serializes a {@link Message} into a newly allocated, ready-to-read
     * {@link ByteBuffer} (position=0, limit=totalFrameSize).
     *
     * @param message the message to encode; must not be {@code null}
     * @return a flipped {@code ByteBuffer} ready for channel writes
     */
    public static ByteBuffer encode(Message message) {
        if (message == null) throw new IllegalArgumentException("message must not be null");

        byte[] payloadBytes = message.payload().getBytes(StandardCharsets.UTF_8);
        int payloadLength   = payloadBytes.length;

        if (payloadLength > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "Payload exceeds maximum allowed size (" + payloadLength + " > " + MAX_PAYLOAD_BYTES + ")");
        }

        // Allocate header + payload in one shot — avoids a copy later.
        ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + payloadLength)
                .order(ByteOrder.BIG_ENDIAN);

        frame.put(message.type().wireCode()); // byte 0 : type discriminator
        frame.putInt(payloadLength);          // bytes 1-4 : payload length
        frame.put(payloadBytes);              // bytes 5+ : JSON body

        frame.flip(); // prepare for reading
        return frame;
    }

    /**
     * Convenience overload: serializes {@code object} to JSON via Gson, then
     * wraps it in a frame of the given {@code type}.
     *
     * @param type   the frame type discriminator
     * @param object the domain object to serialize; must be Gson-serializable
     * @param <T>    the domain object type
     * @return a flipped {@code ByteBuffer} ready for channel writes
     */
    public static <T> ByteBuffer encodeObject(MessageType type, T object) {
        if (type   == null) throw new IllegalArgumentException("type must not be null");
        if (object == null) throw new IllegalArgumentException("object must not be null");

        String json = GSON.toJson(object);
        return encode(new Message(type, json));
    }

    // -------------------------------------------------------------------------
    // Decode
    // -------------------------------------------------------------------------

    /**
     * Attempts to decode one complete frame from {@code buffer}.
     *
     * <p>The buffer's {@link ByteOrder} is temporarily treated as big-endian
     * during the read regardless of its configured order.
     *
     * <p><strong>Partial-read contract:</strong> if the buffer does not contain
     * a full frame the buffer's position is reset to where it was on entry and
     * a {@link PartialMessageException} is thrown. The caller must not discard
     * already-buffered bytes; instead it should append newly received bytes and
     * retry.
     *
     * @param buffer the incoming data buffer; position should be set to the
     *               start of the next frame to read
     * @return a fully decoded {@link Message}
     * @throws PartialMessageException  if {@code buffer} contains fewer bytes
     *                                  than required for a complete frame
     * @throws IllegalArgumentException if the frame contains an unknown type
     *                                  discriminator or an invalid payload length
     */
    public static Message decode(ByteBuffer buffer) {
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");

        // Snapshot position so we can rewind on partial read.
        int startPosition = buffer.position();

        try {
            // --- Guard: need at least the header --------------------------------
            if (buffer.remaining() < HEADER_BYTES) {
                throw new PartialMessageException(
                        "Incomplete header: need " + HEADER_BYTES
                        + " bytes, have " + buffer.remaining(),
                        HEADER_BYTES - buffer.remaining());
            }

            // --- Read header (big-endian) ---------------------------------------
            byte typeByte     = buffer.order(ByteOrder.BIG_ENDIAN).get();
            int  payloadLength = buffer.order(ByteOrder.BIG_ENDIAN).getInt();

            // --- Validate header fields ----------------------------------------
            if (payloadLength < 0) {
                throw new IllegalArgumentException(
                        "Frame declares negative payload length: " + payloadLength);
            }
            if (payloadLength > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException(
                        "Frame payload length " + payloadLength
                        + " exceeds maximum " + MAX_PAYLOAD_BYTES);
            }

            // --- Guard: need the full payload -----------------------------------
            if (buffer.remaining() < payloadLength) {
                int missing = payloadLength - buffer.remaining();
                // Rewind before throwing so caller can retry after buffering more data.
                buffer.position(startPosition);
                throw new PartialMessageException(
                        "Incomplete payload: need " + payloadLength
                        + " bytes, have " + buffer.remaining()
                        + " (missing " + missing + ")",
                        missing);
            }

            // --- Read payload ---------------------------------------------------
            byte[] payloadBytes = new byte[payloadLength];
            buffer.get(payloadBytes);
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);

            // --- Resolve type (after all reads so partial-read rewind stays valid)
            MessageType type = MessageType.fromWireCode(typeByte);

            return new Message(type, payload);

        } catch (BufferUnderflowException e) {
            // Should not happen given the guards above, but handled defensively.
            buffer.position(startPosition);
            throw new PartialMessageException(
                    "Unexpected buffer underflow while decoding frame: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Payload helpers
    // -------------------------------------------------------------------------

    /**
     * Deserializes the JSON payload of a {@link Message} into a domain object.
     *
     * @param message the decoded message
     * @param clazz   the target type
     * @param <T>     the target type parameter
     * @return the deserialized domain object
     */
    public static <T> T decodePayload(Message message, Class<T> clazz) {
        if (message == null) throw new IllegalArgumentException("message must not be null");
        if (clazz   == null) throw new IllegalArgumentException("clazz must not be null");
        return GSON.fromJson(message.payload(), clazz);
    }

    // -------------------------------------------------------------------------
    // CLEAR_USER_SHAPES helpers
    // -------------------------------------------------------------------------

    /**
     * Encodes a {@code CLEAR_USER_SHAPES} frame whose payload is the JSON
     * representation of {@code clientId} (a JSON string literal, e.g.
     * {@code "\"user-42\""}).
     *
     * @param clientId the session-scoped identifier of the user requesting the
     *                 scoped clear; must not be {@code null}
     * @return a flipped {@code ByteBuffer} ready for channel writes
     */
    public static ByteBuffer encodeClearUserShapes(String clientId) {
        if (clientId == null) throw new IllegalArgumentException("clientId must not be null");
        return encode(new Message(MessageType.CLEAR_USER_SHAPES, GSON.toJson(clientId)));
    }

    /**
     * Extracts the {@code clientId} from the payload of a
     * {@code CLEAR_USER_SHAPES} message produced by
     * {@link #encodeClearUserShapes}.
     *
     * @param msg a decoded {@code CLEAR_USER_SHAPES} message; must not be
     *            {@code null}
     * @return the clientId string embedded in the payload
     */
    public static String decodeClearUserShapes(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        return GSON.fromJson(msg.payload(), String.class);
    }

    // -------------------------------------------------------------------------
    // TEXT_UPDATE helpers
    // -------------------------------------------------------------------------

    /**
     * Immutable value object that represents the payload of a
     * {@link MessageType#TEXT_UPDATE} frame.
     *
     * <p>All fields are intentionally package-accessible via the record accessor
     * methods; no defensive copies are needed because {@code String} and
     * {@code double} are already immutable / value types.
     *
     * @param objectId    stable UUID that identifies the text node being edited
     * @param clientId    session-scoped identifier of the typing client
     * @param authorName  human-readable display name of the typing peer; never {@code null}
     * @param x           X anchor coordinate of the text node on the canvas
     * @param y           Y anchor coordinate of the text node on the canvas
     * @param currentText the in-progress (uncommitted) text content
     */
    public record TextUpdatePayload(
            String objectId,
            String clientId,
            String authorName,
            double x,
            double y,
            String currentText) {}

    /**
     * Encodes a {@code TEXT_UPDATE} frame from its constituent fields.
     *
     * @param objectId    the UUID of the text node being edited
     * @param clientId    session-scoped identifier of the originating client
     * @param authorName  human-readable display name of the originating client; may be empty
     * @param x           X anchor coordinate on the canvas
     * @param y           Y anchor coordinate on the canvas
     * @param currentText the transient (uncommitted) text content; must not be {@code null}
     * @return a flipped {@code ByteBuffer} ready for channel writes
     */
    public static ByteBuffer encodeTextUpdate(UUID objectId, String clientId, String authorName,
                                              double x, double y, String currentText) {
        if (objectId    == null) throw new IllegalArgumentException("objectId must not be null");
        if (clientId    == null) throw new IllegalArgumentException("clientId must not be null");
        if (currentText == null) throw new IllegalArgumentException("currentText must not be null");
        TextUpdatePayload payload = new TextUpdatePayload(
                objectId.toString(), clientId,
                authorName != null ? authorName : "",
                x, y, currentText);
        return encodeObject(MessageType.TEXT_UPDATE, payload);
    }

    /**
     * Decodes the payload of a {@link MessageType#TEXT_UPDATE} message.
     *
     * @param msg a decoded message whose {@link Message#type()} is
     *            {@link MessageType#TEXT_UPDATE}; must not be {@code null}
     * @return the deserialized {@link TextUpdatePayload}
     * @throws IllegalArgumentException if {@code msg} is {@code null} or the
     *         payload is malformed
     */
    public static TextUpdatePayload decodeTextUpdate(Message msg) {
        if (msg == null) throw new IllegalArgumentException("msg must not be null");
        return GSON.fromJson(msg.payload(), TextUpdatePayload.class);
    }

    /**
     * Exposes the shared {@link Gson} instance for callers that need custom
     * serialization (e.g. registering type adapters for {@code UUID} or
     * {@code sealed} hierarchies).
     */
    public static Gson gson() {
        return GSON;
    }
}
