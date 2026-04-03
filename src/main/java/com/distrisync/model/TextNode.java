package com.distrisync.model;

import java.util.UUID;

/**
 * A positioned text annotation on the whiteboard.
 *
 * <p>The top-left corner of the text bounding box is at ({@code x}, {@code y}).
 * Font rendering is delegated to the client renderer; the server only stores
 * the logical properties.
 *
 * @param objectId   stable CRDT identity
 * @param timestamp  Lamport clock value at creation / last mutation
 * @param color      text foreground color
 * @param x          bounding-box top-left X in logical pixels
 * @param y          bounding-box top-left Y in logical pixels
 * @param content    UTF-8 text to display; may contain newlines
 * @param fontFamily font family name, e.g. {@code "Arial"} or {@code "monospace"}
 * @param fontSize   font size in logical pixels; must be positive
 * @param bold       whether the text is rendered bold
 * @param italic     whether the text is rendered italic
 * @param authorName display name of the user who placed this annotation
 * @param clientId   session-scoped client identifier of the creator
 */
public record TextNode(
        UUID   objectId,
        long   timestamp,
        String color,
        double x,
        double y,
        String content,
        String fontFamily,
        int    fontSize,
        boolean bold,
        boolean italic,
        String authorName,
        String clientId
) implements Shape {

    public TextNode {
        if (objectId == null)   throw new IllegalArgumentException("objectId must not be null");
        if (color == null || color.isBlank()) throw new IllegalArgumentException("color must not be blank");
        if (content == null)    throw new IllegalArgumentException("content must not be null");
        if (fontFamily == null || fontFamily.isBlank()) throw new IllegalArgumentException("fontFamily must not be blank");
        if (fontSize <= 0)      throw new IllegalArgumentException("fontSize must be positive");
        if (authorName == null) authorName = "";
        if (clientId   == null) clientId   = "";
    }

    /**
     * Convenience factory with sensible defaults.
     * Attribution defaults to empty strings (anonymous / legacy).
     */
    public static TextNode create(String color, double x, double y, String content) {
        return new TextNode(
                UUID.randomUUID(), System.currentTimeMillis(),
                color, x, y, content, "Arial", 14, false, false, "", ""
        );
    }

    /** Attributed convenience factory. */
    public static TextNode create(String color, double x, double y, String content,
                                  String authorName, String clientId) {
        return new TextNode(
                UUID.randomUUID(), System.currentTimeMillis(),
                color, x, y, content, "Arial", 14, false, false, authorName, clientId
        );
    }
}
