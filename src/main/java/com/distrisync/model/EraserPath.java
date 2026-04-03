package com.distrisync.model;

import java.util.UUID;

/**
 * An eraser stroke defined as a polyline of 2-D canvas points.
 *
 * <p>When rendered, the path is drawn as a thick white stroke with a square
 * line cap (replicating Microsoft Paint's block eraser).  Because
 * {@link com.distrisync.client.WhiteboardApp#redrawBaseCanvas} always fills
 * the canvas white before painting shapes in Lamport-timestamp order, a white
 * stroke at timestamp {@code T} pixel-perfectly overwrites every shape with
 * {@code timestamp < T} — this is functionally identical to a
 * {@code destination-out} / ERASE composite operation on a white-background
 * canvas.
 *
 * <p>Coordinates are stored as parallel {@code double[]} arrays so Gson can
 * round-trip them as compact JSON arrays without custom type adapters:
 * <pre>
 * { "_type": "EraserPath", "xs": [10.0, 15.0, …], "ys": [20.0, 25.0, …], … }
 * </pre>
 *
 * @param objectId    stable CRDT identity
 * @param timestamp   Lamport clock value at creation
 * @param color       always {@code "#FFFFFF"} — the canvas background colour
 * @param xs          X coordinates of the path points, index-aligned with {@code ys}
 * @param ys          Y coordinates of the path points, index-aligned with {@code xs}
 * @param strokeWidth eraser brush size in logical pixels (pre-scaled 3× by the client)
 * @param authorName  display name of the user who performed this erase
 * @param clientId    session-scoped client identifier of the creator
 */
public record EraserPath(
        UUID     objectId,
        long     timestamp,
        String   color,
        double[] xs,
        double[] ys,
        double   strokeWidth,
        String   authorName,
        String   clientId
) implements Shape {

    public EraserPath {
        if (objectId == null)
            throw new IllegalArgumentException("objectId must not be null");
        if (xs == null || ys == null)
            throw new IllegalArgumentException("xs and ys must not be null");
        if (xs.length != ys.length)
            throw new IllegalArgumentException("xs and ys must have the same length");
        if (strokeWidth <= 0)
            throw new IllegalArgumentException("strokeWidth must be positive");
        if (color == null || color.isBlank()) color = "#FFFFFF";
        if (authorName == null) authorName = "";
        if (clientId   == null) clientId   = "";
    }

    /**
     * Convenience factory — creates an {@code EraserPath} from the accumulated
     * freehand point list.  The coordinate arrays are defensively copied so the
     * caller may safely clear its accumulator after this call.
     *
     * @param xs          X coordinates of each sampled point
     * @param ys          Y coordinates of each sampled point (same length as {@code xs})
     * @param strokeWidth eraser brush diameter in logical pixels
     * @param authorName  display name of the creator; may be empty
     * @param clientId    session-scoped identifier of the creator; may be empty
     * @return a new, fully attributed {@code EraserPath}
     */
    public static EraserPath create(double[] xs, double[] ys, double strokeWidth,
                                    String authorName, String clientId) {
        return new EraserPath(
                UUID.randomUUID(),
                System.currentTimeMillis(),
                "#FFFFFF",
                xs.clone(),
                ys.clone(),
                strokeWidth,
                authorName != null ? authorName : "",
                clientId   != null ? clientId   : "");
    }
}
