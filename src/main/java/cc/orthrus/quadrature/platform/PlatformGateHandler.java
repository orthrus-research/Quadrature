package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.platform.GateFailure;
import cc.orthrus.quadrature.core.platform.PlatformGate;
import cc.orthrus.quadrature.core.platform.PlatformProfile;
import cc.orthrus.quadrature.core.platform.PlatformRequirements;

import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.List;

/**
 * Checks the graphics context when the first screen opens, the first point at which the client
 * thread owns the context. On an unsupported platform every screen is replaced by the stop screen.
 */
public final class PlatformGateHandler {

    private final PlatformRequirements requirements;
    /** Null until the context has been read; empty when the platform is supported. */
    private List<GateFailure> failures;

    public PlatformGateHandler(PlatformRequirements requirements) {
        this.requirements = requirements;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGuiOpen(GuiOpenEvent event) {
        if (failures == null) {
            PlatformProfile profile = PlatformReader.read();
            Quadrature.LOGGER.info("Platform: {}", profile.describe());
            failures = PlatformGate.evaluate(profile, requirements);
            if (failures.isEmpty()) {
                Quadrature.LOGGER.info("Platform gate passed.");
            } else {
                for (GateFailure failure : failures) {
                    Quadrature.LOGGER.fatal("Stopping: {} {} {}", failure.found(), failure.required(), failure.remedy());
                }
            }
        }
        if (!failures.isEmpty() && !(event.getGui() instanceof StopScreen)) {
            event.setGui(new StopScreen(failures));
        }
    }
}
