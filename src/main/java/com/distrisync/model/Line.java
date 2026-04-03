package com.distrisync.model;

import java.util.UUID;

/**
 * A straight line segment defined by two endpoints in 2-D canvas space.
 * All coordinates are in logical (device-independent) pixels.
 *
 * @param objectId    stable CRDT identity
 * @param timestamp   Lamport clock value at creation / last mutation
 * @param color       stroke color
 * @param x1          start-point X
 * @param y1          start-point Y
 * @param x2          end-point X
 * @param y2          end-point Y
 * @param strokeWidth line thickness in logical pixels
 * @param authorName  display name of the user who drew this shape
 * @param clientId    session-scoped client identifier of the creator
 */
public record Line(
        UUID   objectId,
        long   timestamp,
        String color,
        double x1,
        double y1,
        double x2,
        double y2,
        double strokeWidth,
        String authorName,
        String clientId
) implements Shape {

    public Line {
        if (objectId == null)  throw new IllegalArgumentException("objectId must not be null");
        if (color == null || color.isBlank()) throw new IllegalArgumentException("color must not be blank");
        if (strokeWidth <= 0) throw new IllegalArgumentException("strokeWidth must be positive");
        if (authorName == null) authorName = "";
        if (clientId   == null) clientId   = "";
    }

    /**
     * Convenience factory — auto-generates a random objectId and stamps current time.
     * Attribution fields default to empty strings (anonymous / legacy).
     */
    public static Line create(String color, double x1, double y1, double x2, double y2, double strokeWidth) {
        return new Line(UUID.randomUUID(), System.currentTimeMillis(), color, x1, y1, x2, y2, strokeWidth, "", "");
    }

    /**
     * Attributed convenience factory — records who drew the shape.
     */
    public static Line create(String color, double x1, double y1, double x2, double y2,
                              double strokeWidth, String authorName, String clientId) {
        return new Line(UUID.randomUUID(), System.currentTimeMillis(), color,
                        x1, y1, x2, y2, strokeWidth, authorName, clientId);
    }
}
