package com.distrisync.protocol;

import com.distrisync.model.Circle;
import com.distrisync.model.TextNode;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
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

    @Test
    void handshake_encode_decode_roundTrip() {
        ByteBuffer frame = MessageCodec.encodeHandshake("Alice", "cid-1");
        Message    msg   = MessageCodec.decode(frame);
        MessageCodec.HandshakePayload hp = MessageCodec.decodeHandshake(msg);

        assertThat(msg.type()).isEqualTo(MessageType.HANDSHAKE);
        assertThat(hp.authorName()).isEqualTo("Alice");
        assertThat(hp.clientId()).isEqualTo("cid-1");
    }

    @Test
    void handshake_legacyRoomIdInJson_isIgnored() {
        String legacyJson = "{\"authorName\":\"x\",\"clientId\":\"y\",\"roomId\":\"old-room\"}";
        Message msg = new Message(MessageType.HANDSHAKE, legacyJson);
        MessageCodec.HandshakePayload hp = MessageCodec.decodeHandshake(msg);
        assertThat(hp.authorName()).isEqualTo("x");
        assertThat(hp.clientId()).isEqualTo("y");
    }

    @Test
    void handshake_decodeHandshake_wrongType_throws() {
        Message wrong = new Message(MessageType.MUTATION, "{}");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MessageCodec.decodeHandshake(wrong));
    }

    @Test
    void lobbyState_roundTrip() {
        List<MessageCodec.LobbyRoomEntry> in = List.of(
                new MessageCodec.LobbyRoomEntry("COMP352", 3),
                new MessageCodec.LobbyRoomEntry("LobbyTest", 0));
        ByteBuffer frame = MessageCodec.encodeLobbyState(in);
        Message msg = MessageCodec.decode(frame);
        assertThat(msg.type()).isEqualTo(MessageType.LOBBY_STATE);
        List<MessageCodec.LobbyRoomEntry> out = MessageCodec.decodeLobbyState(msg);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).roomId()).isEqualTo("COMP352");
        assertThat(out.get(0).userCount()).isEqualTo(3);
        assertThat(out.get(1).userCount()).isZero();
    }

    @Test
    void joinRoom_roundTrip() {
        ByteBuffer frame = MessageCodec.encodeJoinRoom("my-room");
        Message msg = MessageCodec.decode(frame);
        assertThat(msg.type()).isEqualTo(MessageType.JOIN_ROOM);
        assertThat(MessageCodec.decodeJoinRoom(msg)).isEqualTo("my-room");
    }

    @Test
    void leaveRoom_encodesEmptyPayload() {
        ByteBuffer frame = MessageCodec.encodeLeaveRoom();
        Message msg = MessageCodec.decode(frame);
        assertThat(msg.type()).isEqualTo(MessageType.LEAVE_ROOM);
        assertThat(msg.payload()).isEmpty();
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
                1.0,
                "TestUser", "test-client-id");

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
    // NEW — testEncodeDecode_UndoRequest
    //
    // UNDO_REQUEST carries a JSON payload with two fields:
    //   "shapeId"    — the UUID (as a hyphenated string) of the shape to remove
    //   "authorName" — the display name of the requesting user
    //
    // Asserts:
    //   (a) the decoded MessageType is UNDO_REQUEST (wire byte 0x09)
    //   (b) "shapeId" round-trips as a valid UUID equal to the original
    //   (c) "authorName" survives without mutation
    //   (d) the decoded UUID reconstructed via UUID.fromString equals the original
    // =========================================================================

    @Test
    void testEncodeDecode_UndoRequest() {
        // --- arrange ----------------------------------------------------------
        UUID   targetId    = UUID.fromString("deadbeef-dead-beef-dead-beefdeadbeef");
        String authorName  = "Alice";

        // The server and client both use this record shape for the UNDO_REQUEST payload.
        record UndoPayload(String shapeId, String authorName) {}
        UndoPayload payload = new UndoPayload(targetId.toString(), authorName);

        // --- act --------------------------------------------------------------
        ByteBuffer encoded    = MessageCodec.encodeObject(MessageType.UNDO_REQUEST, payload);
        Message    decoded    = MessageCodec.decode(encoded);

        // --- assert: wire type ------------------------------------------------
        assertThat(decoded.type())
                .as("decoded type must be UNDO_REQUEST")
                .isEqualTo(MessageType.UNDO_REQUEST);

        // --- assert: payload fields via JSON ----------------------------------
        JsonObject json = MessageCodec.gson().fromJson(decoded.payload(), JsonObject.class);

        assertThat(json.has("shapeId"))
                .as("payload must contain 'shapeId' field")
                .isTrue();
        assertThat(json.has("authorName"))
                .as("payload must contain 'authorName' field")
                .isTrue();

        String rawShapeId = json.get("shapeId").getAsString();
        assertThat(rawShapeId)
                .as("shapeId string must match the original UUID's toString()")
                .isEqualTo(targetId.toString());

        // Reconstruct UUID from the decoded string — validates parse-ability.
        UUID reconstructed = UUID.fromString(rawShapeId);
        assertThat(reconstructed)
                .as("reconstructed UUID must equal the original")
                .isEqualTo(targetId);

        assertThat(json.get("authorName").getAsString())
                .as("authorName must survive the encode/decode cycle unchanged")
                .isEqualTo(authorName);
    }

    // =========================================================================
    // testEncodeDecode_TextNodeCommit
    //
    // A TextNode is encoded as a SHAPE_COMMIT payload and decoded back.
    //
    // Asserts:
    //   (a) the decoded MessageType is SHAPE_COMMIT
    //   (b) the raw JSON payload contains a "content" field equal to "Hello World"
    //   (c) decodePayload reconstructs a runtime TextNode instance
    //       (Gson 2.10+ deserialises records via the canonical constructor)
    //   (d) all value fields — content, color, clientId — survive unchanged
    // =========================================================================

    @Test
    void testEncodeDecode_TextNodeCommit() {
        // --- arrange ----------------------------------------------------------
        UUID   shapeId   = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210");
        String content   = "Hello World";
        String color     = "#CABF69";
        String clientId  = "client-text-001";

        TextNode textNode = new TextNode(
                shapeId,
                1_800_000_000_000L,
                color,
                120.0, 240.0,
                content,
                "Arial", 16, false, false,
                "TestAuthor", clientId);

        // --- act: encode TextNode as a SHAPE_COMMIT frame ---------------------
        // SHAPE_COMMIT is used to verify that MessageCodec can transport a
        // TextNode payload correctly regardless of the frame-type discriminator.
        ByteBuffer encoded   = MessageCodec.encodeObject(MessageType.SHAPE_COMMIT, textNode);
        Message    decodedMsg = MessageCodec.decode(encoded);

        // --- assert: frame type -----------------------------------------------
        assertThat(decodedMsg.type())
                .as("frame type must be SHAPE_COMMIT")
                .isEqualTo(MessageType.SHAPE_COMMIT);

        // --- assert: 'content' field in raw JSON ------------------------------
        JsonObject json = MessageCodec.gson().fromJson(decodedMsg.payload(), JsonObject.class);

        assertThat(json.has("content"))
                .as("JSON payload must carry the 'content' field of a TextNode")
                .isTrue();

        assertThat(json.get("content").getAsString())
                .as("'content' must be exactly \"Hello World\" — no truncation or encoding artefacts")
                .isEqualTo(content);

        // --- assert: deserialized instance is TextNode -----------------------
        // Gson 2.10+ deserialises Java records through their canonical constructor,
        // so decodePayload correctly produces a live TextNode with validated fields.
        TextNode reconstructed = MessageCodec.decodePayload(decodedMsg, TextNode.class);

        assertThat(reconstructed)
                .as("decodePayload must return an instance of TextNode, not a generic Object")
                .isInstanceOf(TextNode.class);

        assertThat(reconstructed.content())
                .as("TextNode.content() must survive the full encode → wire → decode cycle unchanged")
                .isEqualTo(content);

        assertThat(reconstructed.color())
                .as("TextNode.color() must survive the encode/decode cycle unchanged")
                .isEqualTo(color);

        assertThat(reconstructed.clientId())
                .as("TextNode.clientId() must survive the encode/decode cycle unchanged")
                .isEqualTo(clientId);
    }

    // =========================================================================
    // NEW — testEncodeDecode_ClearUserShapes
    //
    // CLEAR_USER_SHAPES carries a JSON-string payload containing the clientId
    // of the user requesting the scoped clear.
    //
    // Asserts:
    //   (a) the decoded MessageType is CLEAR_USER_SHAPES (wire byte 0x08)
    //   (b) the clientId round-trips unchanged through encode → decode
    //   (c) the frame is exactly HEADER_BYTES + len(JSON string) bytes
    //   (d) the buffer is fully consumed after a single decode
    // =========================================================================

    @Test
    void testEncodeDecode_ClearUserShapes() {
        // --- arrange ----------------------------------------------------------
        String clientId = "User-A-123";

        // --- act --------------------------------------------------------------
        ByteBuffer encoded = MessageCodec.encodeClearUserShapes(clientId);
        int frameSize      = encoded.remaining();
        Message decoded    = MessageCodec.decode(encoded);

        // --- assert: wire type ------------------------------------------------
        assertThat(decoded.type())
                .as("decoded type must be CLEAR_USER_SHAPES (wire byte 0x08)")
                .isEqualTo(MessageType.CLEAR_USER_SHAPES);

        // --- assert: clientId round-trips through the dedicated helper --------
        String decodedClientId = MessageCodec.decodeClearUserShapes(decoded);
        assertThat(decodedClientId)
                .as("clientId must survive the encode/decode cycle unchanged")
                .isEqualTo(clientId);

        // --- assert: frame sizing ---------------------------------------------
        // The payload is a JSON string literal, e.g. "\"user-42\""
        String jsonPayload    = MessageCodec.gson().toJson(clientId);
        int expectedFrameSize = MessageCodec.HEADER_BYTES + jsonPayload.getBytes().length;
        assertThat(frameSize)
                .as("total CLEAR_USER_SHAPES frame must be exactly %d bytes", expectedFrameSize)
                .isEqualTo(expectedFrameSize);

        // --- assert: buffer fully consumed after one decode -------------------
        assertThat(encoded.hasRemaining())
                .as("buffer must be fully consumed — no leftover bytes after decoding a complete frame")
                .isFalse();
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
