package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.handoff.Handoff;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.shader.Framebuffer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Behind {@code -Dquadrature.handoff=measure}: hands the frame, at the end of the world pass, to a pass on
 * another graphics API (D21) and takes it back. The pass comes from a separately installed module through
 * {@link ServiceLoader}; Quadrature holds none itself, and without the module the switch only logs that it
 * is missing. Off, nothing here runs.
 *
 * <p>Settings are read as {@code quadrature.handoff.<name>}. Two are the host's own, for a measurement of
 * how the handoff survives a window resize: {@code resizeAt} (a frame number) changes the window to
 * {@code resizeTo} (width x height, three quarters of the size by default) and back after {@code resizeHold}
 * frames (600).
 */
public final class HandoffHost {

    static final String SWITCH = "quadrature.handoff";
    public static final boolean REQUESTED = requested(System.getProperty(SWITCH));

    private static Handoff handoff;
    private static boolean stopped;
    private static long frames;
    private static WorldClient world;
    private static long resizeAt = -1;
    private static int restoreWidth;
    private static int restoreHeight;

    private HandoffHost() {
    }

    static boolean requested(String value) {
        return value != null && !value.isEmpty() && !value.equals("off") && !value.equals("false");
    }

    /** The world pass ended, after its stages were timed; the interface is not drawn yet. */
    public static void endOfWorld() {
        if (stopped) {
            return;
        }
        if (handoff == null && TemporalScaler.REQUESTED) {
            // The handoff reads the game's framebuffer as the world pass leaves it and takes its size as the
            // output's, and the scaler stands a smaller framebuffer in for it while the world is drawn. No design
            // yet takes the scaler's images as the handoff's inputs, so the two do not run together.
            Quadrature.LOGGER.warn("The handoff ({}) does not run beside the scaler (own temporal anti-aliasing or a "
                + "scale below 1): it is turned off, and the scaler runs.", SWITCH);
            stopped = true;
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (handoff == null && !open(minecraft)) {
            stopped = true;
            return;
        }
        if (world != minecraft.world) {
            if (world != null && !contain("a world change", handoff::worldChanged)) {
                return;
            }
            world = minecraft.world;
        }
        Framebuffer framebuffer = minecraft.getFramebuffer();
        if (framebuffer.framebufferObject <= 0 || framebuffer.depthBuffer <= 0) {
            Quadrature.LOGGER.error("The handoff needs the game's framebuffer with a depth buffer; it is turned off.");
            stop();
            return;
        }
        Handoff.Frame frame = new Handoff.Frame(frames++, framebuffer.framebufferObject, framebuffer.framebufferTexture,
            framebuffer.depthBuffer, framebuffer.framebufferWidth, framebuffer.framebufferHeight);
        if (contain("a frame", () -> handoff.frame(frame))) {
            resizeFault(minecraft);
        }
    }

    /**
     * Runs a call into the handoff, turning the handoff off when it throws. Errors are kept in too: the game
     * answers an {@link OutOfMemoryError}, which is also how LWJGL reports a full thread stack, by dropping the
     * world, and a {@link LinkageError} from a module built against something else would end the client.
     * Returns whether the handoff is still on.
     */
    private static boolean contain(String what, Runnable call) {
        try {
            call.run();
            return true;
        } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
            Quadrature.LOGGER.error("The handoff failed in {} and is turned off; the frame goes on without it.", what, e);
            stop();
            return false;
        }
    }

    /** The client's world goes; a handoff that keeps images across worlds is told before the next one comes. */
    @SubscribeEvent
    public static void worldUnloaded(WorldEvent.Unload event) {
        if (handoff != null && !stopped && event.getWorld() == world) {
            contain("a world change", handoff::worldChanged);
            world = null;
        }
    }

    private static boolean open(Minecraft minecraft) {
        Handoff found = find(minecraft);
        if (found == null) {
            Quadrature.LOGGER.warn("The handoff was asked for ({}={}), but no module provides one: install "
                + "quadrature-nvidia beside Quadrature.", SWITCH, System.getProperty(SWITCH));
            return false;
        }
        Path folder = minecraft.gameDir.toPath().resolve("quadrature-handoff");
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            Quadrature.LOGGER.error("The handoff's folder {} could not be made; it is turned off.", folder, e);
            return false;
        }
        String reason;
        try {
            reason = found.open(host(folder));
        } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
            Quadrature.LOGGER.error("The {} failed to open; the frame goes on without it.", found.name(), e);
            return false;
        }
        if (reason != null) {
            Quadrature.LOGGER.warn("The {} cannot run, so the frame goes on without it: {}", found.name(), reason);
            return false;
        }
        handoff = found;
        resizeAt = Long.getLong(SWITCH + ".resizeAt", -1L);
        MinecraftForge.EVENT_BUS.register(HandoffHost.class);
        return true;
    }

    private static Handoff.Host host(Path folder) {
        return new Handoff.Host() {
            @Override
            public void info(String message) {
                Quadrature.LOGGER.info(message);
            }

            @Override
            public void warn(String message) {
                Quadrature.LOGGER.warn(message);
            }

            @Override
            public String option(String name, String fallback) {
                return System.getProperty(SWITCH + "." + name, fallback);
            }

            @Override
            public Path outputDirectory() {
                return folder;
            }
        };
    }

    /**
     * The module is found where the game's class loader already sees it; failing that, among the mods, whose
     * folder Forge may not have put on the class path for a JAR that is not a mod itself.
     */
    private static Handoff find(Minecraft minecraft) {
        ClassLoader loader = HandoffHost.class.getClassLoader();
        Handoff found = first(ServiceLoader.load(Handoff.class, loader));
        if (found != null) {
            return found;
        }
        File[] jars = new File(minecraft.gameDir, "mods").listFiles(
            (directory, name) -> name.startsWith("quadrature-nvidia") && name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return null;
        }
        try {
            net.minecraft.launchwrapper.Launch.classLoader.addURL(jars[0].toURI().toURL());
        } catch (MalformedURLException e) {
            Quadrature.LOGGER.error("The module at {} could not be added to the class path.", jars[0], e);
            return null;
        }
        Quadrature.LOGGER.info("The handoff's module {} was added to the class path from the mods folder.", jars[0].getName());
        return first(ServiceLoader.load(Handoff.class, loader));
    }

    private static Handoff first(ServiceLoader<Handoff> services) {
        try {
            Iterator<Handoff> iterator = services.iterator();
            return iterator.hasNext() ? iterator.next() : null;
        } catch (ServiceConfigurationError | LinkageError e) {
            Quadrature.LOGGER.error("The handoff's module could not be loaded.", e);
            return null;
        }
    }

    /** Changes the window and changes it back, at the frames the settings name. */
    private static void resizeFault(Minecraft minecraft) {
        if (resizeAt < 0) {
            return;
        }
        long frame = frames - 1;
        long hold = Long.getLong(SWITCH + ".resizeHold", 600L);
        try {
            if (frame == resizeAt) {
                restoreWidth = minecraft.displayWidth;
                restoreHeight = minecraft.displayHeight;
                String[] size = System.getProperty(SWITCH + ".resizeTo",
                    restoreWidth * 3 / 4 + "x" + restoreHeight * 3 / 4).split("x");
                int width = Integer.parseInt(size[0].trim());
                int height = Integer.parseInt(size[1].trim());
                Quadrature.LOGGER.info("Handoff: changing the window from {}x{} to {}x{} at frame {}", restoreWidth,
                    restoreHeight, width, height, frame);
                org.lwjgl.opengl.Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(width, height));
            } else if (frame == resizeAt + hold) {
                Quadrature.LOGGER.info("Handoff: changing the window back to {}x{} at frame {}", restoreWidth, restoreHeight,
                    frame);
                org.lwjgl.opengl.Display.setDisplayMode(new org.lwjgl.opengl.DisplayMode(restoreWidth, restoreHeight));
            }
        } catch (Exception e) {
            Quadrature.LOGGER.error("Handoff: the window could not be resized", e);
            resizeAt = -1;
        }
    }

    private static void stop() {
        stopped = true;
        if (handoff != null) {
            try {
                handoff.close();
            } catch (RuntimeException | LinkageError | OutOfMemoryError e) {
                Quadrature.LOGGER.error("The handoff failed while closing.", e);
            }
            handoff = null;
        }
    }
}
