package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.core.platform.GateFailure;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiErrorScreen;
import net.minecraftforge.fml.client.CustomModLoadingErrorDisplayException;

import java.util.List;

/**
 * Stops loading on Forge's own loading error screen, with the gate's failures: another renderer installed, or
 * Cubature missing where own terrain is to draw.
 */
public final class LoadingStopException extends CustomModLoadingErrorDisplayException {

    private static final long serialVersionUID = 1L;

    private final transient List<GateFailure> failures;

    public LoadingStopException(List<GateFailure> failures) {
        super(StopMessage.TITLE + ": " + String.join(" ", StopMessage.plainLines(failures)), null);
        this.failures = List.copyOf(failures);
    }

    @Override
    public void initGui(GuiErrorScreen errorScreen, FontRenderer fontRenderer) {
    }

    @Override
    public void drawScreen(GuiErrorScreen errorScreen, FontRenderer fontRenderer, int mouseRelX, int mouseRelY,
                           float tickTime) {
        StopMessage.draw(errorScreen, fontRenderer, errorScreen.width, failures);
    }
}
