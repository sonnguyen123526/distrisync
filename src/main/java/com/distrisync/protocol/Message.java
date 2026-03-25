package com.distrisync.protocol;

import java.util.Objects;

/**
 * Immutable value type representing a fully decoded DistriSync protocol message.
 *
 * <p>The {@code payload} is always a UTF-8 JSON string. Higher-level code is
 * responsible for further deserializing it into a domain object (e.g. a
 * {@link com.distrisync.model.Shape} subtype or a handshake DTO).
 *
 * @param type    the message category
 * @param payload raw JSON string; never {@code null}, may be {@code "{}"}
 *                for message types that carry no body
 */
public record Message(MessageType type, String payload) {

    public Message {
        Objects.requireNonNull(type,    "type must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
    }

    /** Convenience constructor for zero-body messages (e.g. a bare HANDSHAKE ACK). */
    public static Message empty(MessageType type) {
        return new Message(type, "{}");
    }
}
