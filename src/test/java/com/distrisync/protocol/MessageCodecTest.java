package com.distrisync.protocol;

import com.distrisync.model.Circle;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class MessageCodecTest {

    // =========================================================================
    // Round-trip: encode → decode produces identical message
    // =========================================================================

    @Test
    void roundTrip_allMessageTypes() {
        for (MessageType type : MessageType.values()) {
            String payload  = "{\"test\":\"" + type.name() + "\"}";
            Message original = new Message(type, payload);

            ByteBuffer encoded = MessageCodec.encode(original);
            Message decoded    = MessageCodec.decode(encoded);

            assertThat(decoded.type())
                    .as("type mismatch for %s", type)
                    .isEqualTo(original.type());
            assertThat(decoded.payload())
                    .as("payload mismatch for %s", type)
                    .isEqualTo(original.payload());
        }
    }

    // =========================================================================
    // Header sizing
    // =========================================================================

    @Test
    void encodedFrame_hasCorrectTotalSize() {
        String  payload      = "{}";
        ByteBuffer frame     = MessageCodec.encode(new Message(MessageType.HANDSHAKE, payload));
        int expectedSize     = MessageCodec.HEADER_BYTES + payload.getBytes().length;

        assertThat(frame.limit()).isEqualTo(expectedSize);
    }

    // =========================================================================
    // Error: null / unknown inputs
    // =========================================================================

    @Test
    void encode_nullMessage_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MessageCodec.encode(null));
    }

    @Test
    void decode_unknownTypeByte_throwsIllegalArgument() {
        ByteBuffer bad = ByteBuffer.allocate(MessageCodec.HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        bad.put((byte) 0xFF); // no such MessageType
        bad.putInt(0);        // zero-length payload — header is otherwise valid
        bad.flip();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> MessageCodec.decode(bad));
    }

    // =========================================================================
    // encodeObject / decodePayload helpers
    // =========================================================================

    @Test
    void encodeObject_decodePayload_roundTrip() {
        record Cursor(double x, double y) {}

        Cursor     original = new Cursor(12.5, 99.0);
        ByteBuffer frame    = MessageCodec.encodeObject(MessageType.UDP_POINTER, original);
        Message    msg      = MessageCodec.decode(frame);
        Cursor     decoded  = MessageCodec.decodePayload(msg, Cursor.class);

        assertThat(msg.type()).isEqualTo(MessageType.UDP_POINTER);
        assertThat(decoded.x()).isEqualTo(original.x());
        assertThat(decoded.y()).isEqualTo(original.y());
    }

    // =========================================================================
    // NEW — testEncodeDecode_CircleMutation
    //
    // Verify that all semantically significant Circle fields survive the full
    // encode → wire → decode cycle without corruption.
    //
    // UUID is serialized by Gson as {mostSigBits, leastSigBits} (field-level
    // reflection, no custom TypeAdapter registered).  We reconstruct it from
    // those two longs so the assertion is independent of Gson's internal UUID
    // handling strategy.
    // =========================================================================

    @Test
    void testEncodeDecode_CircleMutation() {
        // --- arrange -------------------------------------------------------
        UUID   originalId        = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        long   originalTimestamp = 1_700_000_000_000L;
        double originalRadius    = 42.5;

        Circle circle = new Circle(
                originalId,
                originalTimestamp,
                "#FF0000",
                10.0, 20.0,
                originalRadius,
                false,
                1.0);

        // --- act -----------------------------------------------------------
        ByteBuffer encoded     = MessageCodec.encodeObject(MessageType.MUTATION, circle);
        Message    decodedMsg  = MessageCodec.decode(encoded);

        // --- assert: message envelope --------------------------------------
        assertThat(decodedMsg.type())
                .as("message type must be MUTATION")
                .isEqualTo(MessageType.MUTATION);

        // Parse the raw JSON payload for field-level assertions.
        JsonObject json = MessageCodec.gson()
                .fromJson(decodedMsg.payload(), JsonObject.class);

        // --- assert: timestamp (plain long in JSON) ------------------------
        assertThat(json.get("timestamp").getAsLong())
                .as("timestamp must survive encode/decode unchanged")
                .isEqualTo(originalTimestamp);

        // --- assert: radius (double in JSON) --------------------------------
        assertThat(json.get("radius").getAsDouble())
                .as("radius must survive encode/decode unchanged")
                .isEqualTo(originalRadius);

        // --- assert: UUID (Gson 2.10+ serialises UUID as a plain string) -------
        // e.g. "123e4567-e89b-12d3-a456-426614174000"
        UUID reconstructed = UUID.fromString(json.get("objectId").getAsString());

        assertThat(reconstructed)
                .as("UUID must survive encode/decode unchanged")
                .isEqualTo(originalId);
    }

    // =========================================================================
    // NEW — testBufferUnderflow_IncompleteHeader
    //
    // A buffer with only 3 bytes cannot satisfy the 5-byte header requirement.
    // The codec must:
    //   (a) throw PartialMessageException — not BufferUnderflowException or NPE
    //   (b) report how many bytes are still needed
    //   (c) leave position at 0 (no bytes were consumed, no rewind needed)
    // =========================================================================

    @Test
    void testBufferUnderflow_IncompleteHeader() {
        // 3 bytes: type byte + first 2 of the 4-byte length field
        ByteBuffer partial = ByteBuffer.wrap(new byte[]{0x03, 0x00, 0x00});

        assertThatExceptionOfType(PartialMessageException.class)
                .isThrownBy(() -> MessageCodec.decode(partial))
                .satisfies(ex -> {
                    assertThat(ex.getBytesNeeded())
                            .as("must report exactly 2 missing header bytes")
                            .isEqualTo(MessageCodec.HEADER_BYTES - 3); // = 2
                    assertThat(ex.getMessage())
                            .as("exception message should describe the shortfall")
                            .containsIgnoringCase("incomplete");
                });

        // No bytes were read — position must still be at the entry position.
        assertThat(partial.position())
                .as("buffer position must not advance on incomplete header")
                .isZero();
    }

    // =========================================================================
    // NEW — testBufferUnderflow_IncompletePayload
    //
    // A buffer whose 5-byte header is well-formed (declares 100 payload bytes)
    // but that only provides 50 payload bytes must:
    //   (a) throw PartialMessageException
    //   (b) report exactly 50 bytes still needed
    //   (c) rewind position to 0 (the codec's partial-read contract)
    // =========================================================================

    @Test
    void testBufferUnderflow_IncompletePayload() {
        final int declaredPayloadLength = 100;
        final int availablePayloadBytes = 50;
        final int missingBytes          = declaredPayloadLength - availablePayloadBytes; // 50

        // Build: 1-byte type + 4-byte length + 50 bytes of filler = 55 bytes total.
        ByteBuffer buffer = ByteBuffer.allocate(MessageCodec.HEADER_BYTES + availablePayloadBytes)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put(MessageType.MUTATION.wireCode());  // valid type discriminator
        buffer.putInt(declaredPayloadLength);          // claims 100 bytes…
        for (int i = 0; i < availablePayloadBytes; i++) {
            buffer.put((byte) 0x41);                  // …but only 50 'A' bytes follow
        }
        buffer.flip(); // position=0, limit=55

        assertThatExceptionOfType(PartialMessageException.class)
                .isThrownBy(() -> MessageCodec.decode(buffer))
                .satisfies(ex -> {
                    assertThat(ex.getBytesNeeded())
                            .as("must report exactly %d missing payload bytes", missingBytes)
                            .isEqualTo(missingBytes);
                    assertThat(ex.getMessage())
                            .as("exception message should describe the shortfall")
                            .containsIgnoringCase("incomplete");
                });

        // The codec's partial-read contract: position is reset to where it was
        // on entry so the caller can safely retry after accumulating more data.
        assertThat(buffer.position())
                .as("codec must rewind buffer to its entry position on partial payload")
                .isZero();
    }
}
