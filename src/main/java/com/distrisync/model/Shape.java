package com.distrisync.model;

import java.util.UUID;

/**
 * Root of the Shape hierarchy. Sealed so the compiler exhaustively knows every
 * concrete variant — critical for pattern-matching switch expressions used by
 * the codec and rendering pipeline.
 */
public sealed interface Shape permits Line, Circle, TextNode {

    /** Globally-unique stable identity for CRDT merge / dedup. */
    UUID objectId();

    /**
     * Logical Lamport timestamp used for causal ordering and last-writer-wins
     * conflict resolution across distributed nodes.
     */
    long timestamp();

    /** CSS-compatible color string, e.g. {@code "#FF5733"} or {@code "rgba(255,87,51,0.8)"}. */
    String color();
}
