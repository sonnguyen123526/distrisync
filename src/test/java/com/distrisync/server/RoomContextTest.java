package com.distrisync.server;

import com.distrisync.model.Circle;
import com.distrisync.model.Line;
import com.distrisync.model.Shape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for multi-board routing in {@link RoomContext}: distinct
 * {@link CanvasStateManager} instances per board and isolated mutation application.
 *
 * <p>These tests exercise the production {@link RoomContext} type directly (no Mockito
 * stand-in): {@code getBoard} is the routing primitive the NIO server relies on.
 */
class RoomContextTest {

    /**
     * Verifies that {@link RoomContext#getBoard(String)} provisions an independent
     * {@link CanvasStateManager} per board id and that applying a mutation on one board
     * leaves all other boards unchanged.
     */
    @Test
    void testBoardCreationAndIsolation() {
        RoomContext ctx = new RoomContext("StudyRoom", /* wal */ null);

        CanvasStateManager mathNotes = ctx.getBoard("Math-Notes");
        CanvasStateManager diagrams = ctx.getBoard("Diagrams");

        assertThat(mathNotes)
                .as("Math-Notes and Diagrams must each receive a dedicated CanvasStateManager")
                .isNotNull()
                .isNotSameAs(diagrams);

        assertThat(ctx.getBoard("Math-Notes"))
                .as("getBoard must return the same manager instance for a stable board id")
                .isSameAs(mathNotes);

        UUID lineId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Line line = new Line(
                lineId, 1_700_000_000_000L, "#FF0000",
                0, 0, 100, 100, 2.0, "Alice", "client-a");

        mathNotes.applyMutation(line);

        List<Shape> mathSnap = ctx.getBoard("Math-Notes").snapshot();
        List<Shape> diagramSnap = ctx.getBoard("Diagrams").snapshot();

        assertThat(mathSnap)
                .as("the mutation must exist only on the Math-Notes board")
                .hasSize(1);
        assertThat(mathSnap.get(0).objectId()).isEqualTo(lineId);

        assertThat(diagramSnap)
                .as("Diagrams must remain empty when all mutations targeted Math-Notes")
                .isEmpty();

        UUID circleId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ctx.getBoard("Diagrams").applyMutation(
                new Circle(circleId, 200L, "#0000FF", 5, 5, 3, false, 1.0, "B", "c2"));

        assertThat(ctx.getBoard("Math-Notes").snapshot()).hasSize(1);
        assertThat(ctx.getBoard("Diagrams").snapshot()).hasSize(1);
        assertThat(ctx.getBoard("Math-Notes").snapshot().get(0).objectId()).isEqualTo(lineId);
        assertThat(ctx.getBoard("Diagrams").snapshot().get(0).objectId()).isEqualTo(circleId);
    }

    @Test
    void getActiveBoardIds_reflectsProvisionedBoards() {
        RoomContext ctx = new RoomContext("R", null);
        assertThat(ctx.getActiveBoardIds()).isEmpty();

        ctx.getBoard("Alpha");
        ctx.getBoard("Beta");
        Set<String> ids = ctx.getActiveBoardIds();

        assertThat(ids).containsExactlyInAnyOrder("Alpha", "Beta");
    }

    @Test
    void getBoard_rejectsNullOrBlankBoardId() {
        RoomContext ctx = new RoomContext("R", null);

        assertThatThrownBy(() -> ctx.getBoard(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boardId");

        assertThatThrownBy(() -> ctx.getBoard(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boardId");

        assertThatThrownBy(() -> ctx.getBoard("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boardId");
    }
}
