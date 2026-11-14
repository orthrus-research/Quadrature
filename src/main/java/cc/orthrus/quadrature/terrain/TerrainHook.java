package cc.orthrus.quadrature.terrain;

import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;

/**
 * The one place the rest of the mod reaches own terrain through. It names nothing of Cubature's, so
 * it loads where Cubature is absent; the terrain classes themselves load only once a cube source
 * exists.
 */
public final class TerrainHook {

    /** What own terrain offers the frame. */
    public interface Drawer {
        /** Whether the opaque layers of the current world are drawn from own meshes. */
        boolean active();

        void draw(BlockRenderLayer layer, double partialTicks, Entity viewer);

        /** One line for the timing log. */
        String summary();
    }

    private static Drawer drawer;

    private TerrainHook() {
    }

    static void install(Drawer installed) {
        drawer = installed;
    }

    public static boolean active() {
        return drawer != null && drawer.active();
    }

    public static void draw(BlockRenderLayer layer, double partialTicks, Entity viewer) {
        drawer.draw(layer, partialTicks, viewer);
    }

    /** The timing log's line, or null when own terrain is off. */
    public static String summary() {
        return drawer == null ? null : drawer.summary();
    }

    /** Starts own terrain if it was asked for; call only where Cubature is installed. */
    public static void start() {
        Terrain.start();
    }

    /** Whether own terrain was asked for on the command line. */
    public static boolean requested() {
        return "own".equals(System.getProperty("quadrature.terrain"));
    }
}
