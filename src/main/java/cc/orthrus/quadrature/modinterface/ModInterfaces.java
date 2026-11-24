package cc.orthrus.quadrature.modinterface;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.platform.GateFailure;
import cc.orthrus.quadrature.platform.LoadingStopException;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import net.minecraftforge.fml.common.Loader;

/**
 * The render interface Quadrature provides, once started: the bridge to OrthLib's where OrthLib is installed,
 * {@link ModInterface#NONE} otherwise. The bridge's classes name OrthLib's and are reached by name, so nothing loads
 * them where OrthLib is absent (D26).
 */
public final class ModInterfaces {

    private static final String BRIDGE = "cc.orthrus.quadrature.orthlib.OrthLibBridge";

    /**
     * The interface type the bridge implements, looked for as a resource before the bridge loads: an OrthLib from
     * before the render interface lacks it.
     */
    private static final String INTERFACE = "dev/orthlib/api/render/RenderProvider.class";

    private static volatile ModInterface current = ModInterface.NONE;

    private ModInterfaces() {
    }

    public static ModInterface current() {
        return current;
    }

    /** Whether a mod interface is provided: OrthLib installed and Quadrature its provider. */
    public static boolean provided() {
        return current != ModInterface.NONE;
    }

    /**
     * Registers Quadrature as the render interface's provider where OrthLib is installed; client thread, once, while
     * mods load. Where OrthLib refuses, another renderer provides the interface, and loading stops naming it (D6).
     */
    public static void start() {
        if (!Loader.isModLoaded("orthlib")) {
            return;
        }
        if (ModInterfaces.class.getClassLoader().getResource(INTERFACE) == null) {
            Quadrature.LOGGER.error("OrthLib is installed but has no render interface, so Quadrature does not provide "
                + "it: install an OrthLib that has the render interface (dev.orthlib.api.render), the one this "
                + "Quadrature was released with or a later one.");
            return;
        }
        Object bridge;
        try {
            bridge = Class.forName(BRIDGE).getMethod("start").invoke(null);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalStateException refused) {
                Quadrature.LOGGER.fatal("Stopping: OrthLib refused Quadrature as its render interface's provider.", refused);
                throw new LoadingStopException(List.of(new GateFailure(
                    "OrthLib refused Quadrature as the provider of its render interface: " + refused.getMessage(),
                    "Quadrature replaces the game's renderer and does not run beside another one.",
                    "Remove the other renderer OrthLib names, or remove Quadrature to use it.")));
            }
            Quadrature.LOGGER.error("OrthLib is installed but Quadrature could not provide its render interface.", e.getCause());
            return;
        } catch (ReflectiveOperationException | LinkageError e) {
            Quadrature.LOGGER.error("OrthLib is installed but Quadrature could not provide its render interface.", e);
            return;
        }
        if (bridge instanceof ModInterface provided) {
            current = provided;
            Quadrature.LOGGER.info("Quadrature provides OrthLib's render interface.");
        }
    }
}
