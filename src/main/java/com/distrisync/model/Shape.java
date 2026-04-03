package com.distrisync.model;

import java.util.UUID;

/**
 * Root of the Shape hierarchy. Sealed so the compiler exhaustively knows every
 * concrete variant — critical for pattern-matching switch expressions used by
 * the codec and rendering pipeline.
 */
public sealed interface Shape permits Line, Circle, TextNode, EraserPath {

    /** Globally-unique stable identity for CRDT merge / dedup. */
    UUID objectId();

    /**
     * Logical Lamport timestamp used for causal ordering and last-writer-wins
     * conflict resolution across distributed nodes.
     */
    long timestamp();

    /** CSS-compatible color string, e.g. {@code "#FF5733"} or {@code "rgba(255,87,51,0.8)"}. */
    String color();

    /**
     * Human-readable display name of the user who created this shape.
     * Empty string if attribution is unavailable (legacy shapes, server-internal
     * objects, or test fixtures that pre-date the attribution upgrade).
     */
    String authorName();

    /**
     * Stable session-scoped identifier of the client that created this shape.
     * Matches the {@code clientId} embedded in the {@code HANDSHAKE} frame so
     * the server can correlate shapes with sessions.  Empty string for
     * attribution-free legacy shapes.
     */
    String clientId();
}
