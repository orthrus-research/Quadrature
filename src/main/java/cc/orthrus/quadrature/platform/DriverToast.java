package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.platform.GateFailure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.toasts.SystemToast;
import net.minecraft.util.text.TextComponentString;

/**
 * Shows a warning of the platform gate, which does not stop the game, as a toast on the first screen: what
 * was found and what Quadrature is tested on. The warning is in the log whether or not the toast shows.
 */
final class DriverToast {

    private DriverToast() {
    }

    static void show(GateFailure warning) {
        try {
            Minecraft.getMinecraft().getToastGui().add(new SystemToast(SystemToast.Type.TUTORIAL_HINT,
                new TextComponentString("Quadrature: " + warning.found()), new TextComponentString(warning.required())));
        } catch (RuntimeException | LinkageError failed) {
            Quadrature.LOGGER.warn("The platform warning could not be shown on screen; it is in the log above.", failed);
        }
    }
}
