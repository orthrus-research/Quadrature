package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.core.platform.GateFailure;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.util.List;

/** The only screen an unsupported platform reaches: the reasons, and a button to quit. */
final class StopScreen extends GuiScreen {

    private final List<GateFailure> failures;

    StopScreen(List<GateFailure> failures) {
        this.failures = List.copyOf(failures);
    }

    @Override
    public void initGui() {
        buttonList.clear();
        buttonList.add(new GuiButton(0, width / 2 - 100, height - 40, 200, 20, "Quit Game"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        StopMessage.draw(this, fontRenderer, width, failures);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            mc.shutdown();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // Escape must not close this screen.
    }
}
