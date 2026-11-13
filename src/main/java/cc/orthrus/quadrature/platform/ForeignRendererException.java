package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.core.platform.GateFailure;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiErrorScreen;
import net.minecraftforge.fml.client.CustomModLoadingErrorDisplayException;

import java.util.List;

/** Stops loading when another renderer is installed, on Forge's own loading error screen. */
public final class ForeignRendererException extends CustomModLoadingErrorDisplayException {

    private static final long serialVersionUID = 1L;

    private final transient List<GateFailure> failures;

    public ForeignRendererException(List<GateFailure> failures) {
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
