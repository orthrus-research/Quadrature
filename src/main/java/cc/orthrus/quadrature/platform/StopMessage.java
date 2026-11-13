package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.core.platform.GateFailure;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;

import java.util.ArrayList;
import java.util.List;

/** Lays out gate failures the same way on Forge's loading error screen and on the stop screen. */
final class StopMessage {

    static final String TITLE = "Quadrature cannot run here";
    private static final int WHITE = 0xFFFFFF;
    private static final int GRAY = 0xAAAAAA;
    private static final int YELLOW = 0xFFFF55;

    private StopMessage() {
    }

    /** Draws the title and each failure, wrapped to the screen; returns the y below the last line. */
    static int draw(Gui gui, FontRenderer font, int width, List<GateFailure> failures) {
        int wrap = Math.min(width - 40, 420);
        int y = 30;
        gui.drawCenteredString(font, TITLE, width / 2, y, WHITE);
        y += 20;
        for (GateFailure failure : failures) {
            y = drawWrapped(gui, font, failure.found(), width, wrap, y, WHITE);
            y = drawWrapped(gui, font, failure.required(), width, wrap, y, GRAY);
            y = drawWrapped(gui, font, failure.remedy(), width, wrap, y, YELLOW);
            y += 8;
        }
        return y;
    }

    private static int drawWrapped(Gui gui, FontRenderer font, String text, int width, int wrap, int y, int color) {
        for (String line : font.listFormattedStringToWidth(text, wrap)) {
            gui.drawCenteredString(font, line, width / 2, y, color);
            y += font.FONT_HEIGHT + 2;
        }
        return y;
    }

    static List<String> plainLines(List<GateFailure> failures) {
        List<String> lines = new ArrayList<>();
        for (GateFailure failure : failures) {
            lines.add(failure.found() + " " + failure.required() + " " + failure.remedy());
        }
        return lines;
    }
}
