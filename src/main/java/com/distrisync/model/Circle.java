package com.distrisync.model;

import java.util.UUID;

/**
 * A circle (or ellipse) shape anchored at its center.
 *
 * @param objectId   stable CRDT identity
 * @param timestamp  Lamport clock value at creation / last mutation
 * @param color      stroke color
 * @param x          center X
 * @param y          center Y
 * @param radius     radius in logical pixels; must be positive
 * @param filled     {@code true} if the interior is filled with {@code color}
 * @param strokeWidth outline thickness; ignored when {@code filled} is {@code true} and {@code strokeWidth == 0}
 */
public record Circle(
        UUID   objectId,
        long   timestamp,
        String color,
        double x,
        double y,
        double radius,
        boolean filled,
        double strokeWidth
) implements Shape {

    public Circle {
        if (objectId == null)  throw new IllegalArgumentException("objectId must not be null");
        if (color == null || color.isBlank()) throw new IllegalArgumentException("color must not be blank");
        if (radius <= 0) throw new IllegalArgumentException("radius must be positive");
        if (strokeWidth < 0) throw new IllegalArgumentException("strokeWidth must be non-negative");
    }

    /** Convenience factory — hollow circle with default 1 px stroke. */
    public static Circle create(String color, double x, double y, double radius) {
        return new Circle(UUID.randomUUID(), System.currentTimeMillis(), color, x, y, radius, false, 1.0);
    }

    /** Convenience factory for a filled circle. */
    public static Circle createFilled(String color, double x, double y, double radius) {
        return new Circle(UUID.randomUUID(), System.currentTimeMillis(), color, x, y, radius, true, 0.0);
    }
}
