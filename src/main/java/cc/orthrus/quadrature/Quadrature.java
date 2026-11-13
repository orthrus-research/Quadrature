package cc.orthrus.quadrature;

import cc.orthrus.quadrature.core.platform.Capability;
import cc.orthrus.quadrature.core.platform.GateFailure;
import cc.orthrus.quadrature.core.platform.PlatformGate;
import cc.orthrus.quadrature.core.platform.PlatformRequirements;
import cc.orthrus.quadrature.dev.DevSession;
import cc.orthrus.quadrature.platform.ForeignRenderers;
import cc.orthrus.quadrature.platform.ForeignRendererException;
import cc.orthrus.quadrature.platform.PlatformGateHandler;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumSet;
import java.util.List;

/** Mod entry point. Client only: a dedicated server never loads it. */
@Mod(modid = Quadrature.MOD_ID, name = "Quadrature", version = QuadratureBuild.VERSION,
    acceptedMinecraftVersions = "[1.12.2]",
    dependencies = "required-after:cleanroom@[" + QuadratureBuild.CLEANROOM + "]",
    clientSideOnly = true, acceptableRemoteVersions = "*")
public final class Quadrature {
    public static final String MOD_ID = "quadrature";
    public static final Logger LOGGER = LogManager.getLogger("Quadrature");

    /**
     * Raises the GPU memory floor for a test run, so the stop screen can be exercised on a supported
     * card. A value below the built-in floor is ignored: the property can only make the gate stricter.
     */
    private static final String TEST_MEMORY_FLOOR = "quadrature.test.minimumGpuMemoryMiB";

    /** The supported platform, from platform.json through the generated build constants. */
    static final PlatformRequirements REQUIREMENTS = new PlatformRequirements(
        EnumSet.allOf(Capability.class), List.of(QuadratureBuild.GRAPHICS_VENDOR),
        Math.max(QuadratureBuild.MINIMUM_GPU_MEMORY_MIB, Long.getLong(TEST_MEMORY_FLOOR, 0L)));

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        // Other renderers are known before any graphics call: stop here, while Forge can still show why.
        ClassLoader loader = Quadrature.class.getClassLoader();
        List<GateFailure> foreign = PlatformGate.foreignRenderers(ForeignRenderers.KNOWN,
            Loader::isModLoaded, resource -> loader.getResource(resource) != null);
        GateFailure kirino = kirinoGraphics();
        if (kirino != null) {
            foreign = new java.util.ArrayList<>(foreign);
            foreign.add(kirino);
        }
        if (!foreign.isEmpty()) {
            foreign.forEach(failure -> LOGGER.fatal("Stopping: {} {}", failure.found(), failure.required()));
            throw new ForeignRendererException(foreign);
        }
        warnIfCoreProfileRequested();
        // The graphics context belongs to the loading screen until loading ends; it is read at the first screen.
        MinecraftForge.EVENT_BUS.register(new PlatformGateHandler(REQUIREMENTS));
        LOGGER.info("No other renderer found; the graphics context is checked when the first screen opens.");
        DevSession session = DevSession.fromProperties();
        if (session != null) {
            MinecraftForge.EVENT_BUS.register(session);
        }
    }

    /**
     * Kirino, Cleanroom's rendering engine, replaces the game's world render when it is enabled with its
     * render delegate on. That is another renderer. Both settings are plain configuration, readable
     * before any graphics call; Cleanroom's API is not stable, so a missing class is not an error.
     */
    private static GateFailure kirinoGraphics() {
        try {
            if (com.cleanroommc.kirino.config.KirinoConfig.NEEDS_RESTART.enable
                    && com.cleanroommc.kirino.config.KirinoConfig.NEEDS_RESTART.enableRenderDelegate) {
                return new GateFailure(
                    "Kirino's render delegate is on (config/kirino_engine.cfg: enable and enableRenderDelegate).",
                    "Quadrature replaces the game's renderer and does not run beside another one.",
                    "Set enableRenderDelegate to false to use Quadrature, or remove Quadrature to use Kirino's "
                        + "renderer.");
            }
        } catch (LinkageError e) {
            LOGGER.debug("Kirino's configuration could not be read.", e);
        }
        return null;
    }

    /**
     * On a core profile the game itself aborts during startup, before any screen can be shown, so the
     * log is the only place to say why. Cleanroom's setting is read defensively: its API is not stable.
     */
    private static void warnIfCoreProfileRequested() {
        try {
            if (!net.minecraftforge.common.ForgeEarlyConfig.OPENGL_COMPAT_PROFILE) {
                LOGGER.fatal("Cleanroom's OpenGL compatibility profile setting is off. Minecraft cannot start on a "
                    + "core profile and Quadrature requires the compatibility profile. Set OPENGL_COMPAT_PROFILE "
                    + "to true in config/forge_early.cfg.");
            }
        } catch (LinkageError e) {
            LOGGER.debug("Cleanroom's OpenGL profile setting could not be read.", e);
        }
    }
}
