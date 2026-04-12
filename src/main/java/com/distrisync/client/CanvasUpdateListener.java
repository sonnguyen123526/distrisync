package com.distrisync.client;

import com.distrisync.model.Shape;
import java.util.List;
import java.util.UUID;

/**
 * Callback interface for receiving canvas state updates from the server.
 *
 * <p>Implementations are invoked from the {@code distrisync-read} daemon thread.
 * Any UI work must be dispatched to the appropriate UI thread (e.g.,
 * {@code Platform.runLater}).
 *
 * <p>The three live-drawing callbacks ({@link #onShapeStart}, {@link #onShapeUpdate},
 * {@link #onShapeCommit}) carry ephemeral in-progress state and are NOT persisted
 * by the server — they are relayed peer-to-peer so remote users can watch drawing
 * happen in real time.
 */
public interface CanvasUpdateListener {

    /**
     * Fired on the {@code distrisync-read} thread when the client's workspace board
     * selection or known-board list changes — for example after {@code JOIN_ROOM} /
     * {@code SWITCH_BOARD} (local priming), or when a {@code BOARD_LIST_UPDATE}
     * frame arrives from the server.  Implementations that touch JavaFX must marshal
     * to the FX thread via {@code Platform.runLater}.
     *
     * @param currentBoardId authoritative active board id; may be empty in the lobby
     * @param knownBoards    board ids the client is tracking for this session, in order
     */
    default void onWorkspaceStateChanged(String currentBoardId, List<String> knownBoards) {}

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

    // ── Live drawing callbacks (default no-ops so existing impls need not change) ──

    /**
     * A remote peer pressed the mouse and started drawing a new shape.
     *
     * @param shapeId     stable identifier for this drawing gesture
     * @param tool        tool name: {@code "LINE"}, {@code "CIRCLE"},
     *                    {@code "FREEHAND"}, or {@code "ERASER"}
     * @param color       CSS hex color string, e.g. {@code "#89b4fa"}
     * @param strokeWidth stroke width in canvas pixels
     * @param x           starting X coordinate
     * @param y           starting Y coordinate
     * @param authorName  display name of the peer who started this gesture;
     *                    may be empty but never {@code null}
     */
    default void onShapeStart(UUID shapeId, String tool, String color,
                              double strokeWidth, double x, double y, String authorName) {}

    /**
     * A remote peer dragged the mouse; coordinates reflect the current tip of
     * the gesture.  For {@code FREEHAND}/{@code ERASER} each update is a new
     * point to append; for {@code LINE}/{@code CIRCLE} it is the moving end-point.
     *
     * @param shapeId the gesture identifier (matches a prior {@link #onShapeStart})
     * @param x       current X coordinate
     * @param y       current Y coordinate
     */
    default void onShapeUpdate(UUID shapeId, double x, double y) {}

    /**
     * A remote peer released the mouse; the transient preview for this gesture
     * should be cleared.  The final committed shape(s) will arrive shortly as
     * one or more {@link #onMutationReceived} callbacks.
     *
     * @param shapeId the gesture identifier (matches a prior {@link #onShapeStart})
     */
    default void onShapeCommit(UUID shapeId) {}

    /**
     * The server has processed a {@code CLEAR_USER_SHAPES} request and has
     * removed all shapes owned by {@code clientId} from the authoritative canvas
     * state.  All locally held shapes whose {@code clientId} matches the
     * supplied value should be removed and the canvas re-rendered.
     *
     * @param clientId the session-scoped identifier of the user whose shapes
     *                 were cleared; never {@code null}
     */
    default void onUserShapesCleared(String clientId) {}

    /**
     * The server has confirmed deletion of a single shape (in response to an
     * {@code UNDO_REQUEST}).  The identified shape should be removed from the
     * local canvas store.
     *
     * @param shapeId the {@link UUID} of the shape that was deleted
     */
    default void onShapeDeleted(UUID shapeId) {}

    /**
     * A remote peer is actively typing inside a text node.  This event is
     * <em>transient</em>: the payload carries the uncommitted in-progress text
     * and must NOT be persisted to the authoritative canvas store.  Implementations
     * should render a temporary "ghost" overlay at {@code (x, y)} for the given
     * {@code objectId} and replace it on every subsequent call for the same id.
     * The final committed text will arrive via {@link #onMutationReceived} once
     * the peer confirms the edit.  The ghost overlay should be dismissed when
     * {@link #onShapeCommit} fires for the same {@code objectId}.
     *
     * @param objectId    stable UUID of the text node being edited
     * @param clientId    session-scoped identifier of the typing peer
     * @param authorName  human-readable display name of the typing peer; never {@code null}
     * @param x           X anchor coordinate of the text node on the canvas
     * @param y           Y anchor coordinate of the text node on the canvas
     * @param currentText the in-progress (uncommitted) text content; never {@code null}
     */
    default void onTextUpdate(UUID objectId, String clientId, String authorName,
                              double x, double y, String currentText) {}
}
