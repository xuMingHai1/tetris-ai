package xyz.xuminghai.tetris.view;

import javafx.scene.paint.Color;
import xyz.xuminghai.tetris.core.TetrominoType;

/** UI V1 colors; gameplay retains the original per-cell model colors. */
final class BlockPalette {

    private BlockPalette() { }

    static Color color(TetrominoType type) {
        return switch (type) {
            case I -> Color.web("#86a2c4");
            case T -> Color.web("#a59bc2");
            case L -> Color.web("#c4a18b");
            case J -> Color.web("#8c9fbd");
            case O -> Color.web("#c7b680");
            case S -> Color.web("#8dadac");
            case Z -> Color.web("#be9ca9");
        };
    }
}
