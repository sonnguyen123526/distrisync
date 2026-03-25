package com.distrisync.client;

import com.distrisync.model.Shape;

import java.util.List;

/**
 * Callback interface for receiving canvas state updates from the server.
 *
 * <p>Implementations are invoked from the {@code distrisync-read} daemon thread.
 * Any UI work must be dispatched to the appropriate UI thread (e.g.,
 * {@code SwingUtilities.invokeLater} or {@code Platform.runLater}).
 */
public interface CanvasUpdateListener {

    /**
     * Fired once after a successful (re)connect when the server sends the full
     * board state.  The supplied list is immutable and sorted by insertion order.
     *
     * @param shapes all shapes currently on the canvas; never {@code null}
     */
    void onSnapshotReceived(List<Shape> shapes);

    /**
     * Fired for every incremental shape add, update, or delete that arrives
     * from the server as a {@code MUTATION} frame.
     *
     * @param shape the mutated shape; never {@code null}
     */
    void onMutationReceived(Shape shape);
}
