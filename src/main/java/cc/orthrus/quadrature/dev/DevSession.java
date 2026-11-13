package cc.orthrus.quadrature.dev;

import cc.orthrus.quadrature.Quadrature;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * A development aid, off unless asked for: enters a world without anyone at the keyboard and leaves
 * after a set time, so a run can exercise the world pass and exit on its own.
 *
 * <ul>
 *   <li>{@code -Dquadrature.dev.autoWorld=true}: from the main menu, open the world
 *       {@code quadrature-dev}, creating it as a flat creative world if it does not exist.</li>
 *   <li>{@code -Dquadrature.dev.exitAfterSeconds=N}: quit N seconds after the world is entered.</li>
 * </ul>
 */
public final class DevSession {

    private static final String WORLD = "quadrature-dev";

    private final int exitAfterTicks;
    private boolean launched;
    private int ticksInWorld;

    private DevSession(int exitAfterSeconds) {
        this.exitAfterTicks = exitAfterSeconds * 20;
    }

    /** The session to register on the event bus, or null when the development properties are not set. */
    public static DevSession fromProperties() {
        if (!Boolean.getBoolean("quadrature.dev.autoWorld")) {
            return null;
        }
        return new DevSession(Integer.getInteger("quadrature.dev.exitAfterSeconds", 0));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!launched && mc.currentScreen instanceof GuiMainMenu) {
            launched = true;
            WorldSettings settings = new WorldSettings(1L, GameType.CREATIVE, false, false, WorldType.FLAT);
            settings.enableCommands();
            Quadrature.LOGGER.info("Development session: entering world {}.", WORLD);
            mc.launchIntegratedServer(WORLD, WORLD, settings);
            return;
        }
        if (launched && exitAfterTicks > 0 && mc.world != null && mc.player != null
                && ++ticksInWorld >= exitAfterTicks) {
            Quadrature.LOGGER.info("Development session: leaving after {} ticks in the world.", ticksInWorld);
            mc.shutdown();
        }
    }
}
