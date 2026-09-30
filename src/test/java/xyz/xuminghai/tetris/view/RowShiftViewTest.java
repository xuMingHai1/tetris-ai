package xyz.xuminghai.tetris.view;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import xyz.xuminghai.tetris.core.Cell;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowShiftViewTest {

    @Test
    void usesPublishedBeforeAndAfterPositionsAndDisplayColor() {
        Color modelColor = Color.RED;
        List<RowShiftView.ShiftCell> shifts = RowShiftView.shifts(
                List.of(new Cell(3, 2, modelColor), new Cell(1, 5, modelColor)),
                List.of(new Cell(5, 2, modelColor), new Cell(2, 5, modelColor)),
                _ -> Color.BLUE);

        assertEquals(List.of(
                new RowShiftView.ShiftCell(3, 5, 2, Color.BLUE),
                new RowShiftView.ShiftCell(1, 2, 5, Color.BLUE)), shifts);
    }

    @Test
    void rejectsUnpairedOrNonDescendingCells() {
        Cell oldCell = new Cell(3, 2, Color.RED);
        assertTrue(RowShiftView.shifts(List.of(oldCell), List.of(), Cell::getColor).isEmpty());
        assertTrue(RowShiftView.shifts(List.of(oldCell),
                List.of(new Cell(4, 3, Color.RED)), Cell::getColor).isEmpty());
        assertTrue(RowShiftView.shifts(List.of(oldCell),
                List.of(new Cell(4, 2, Color.GREEN)), Cell::getColor).isEmpty());
        assertTrue(RowShiftView.shifts(List.of(oldCell),
                List.of(new Cell(2, 2, Color.RED)), Cell::getColor).isEmpty());
    }
}
