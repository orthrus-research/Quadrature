package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.terrain.TerrainHook;

import net.minecraftforge.fml.common.Loader;

/**
 * The one place the rest of the mod reaches distant terrain through. It names nothing of
 * Cubature's, so it loads where Cubature is absent; the classes that draw load only once distant
 * terrain is asked for and can run.
 *
 * <p>Off unless {@code -Dquadrature.distant=own}, which needs {@code -Dquadrature.terrain=own}:
 * distant terrain is drawn after own near terrain and leaves out what near terrain covers.
 */
public final class DistantHook {

    /**
     * How far the far plane is pushed while distant terrain is on, in blocks: past the far corner
     * of a 32 km world from its opposite corner, and past any height.
     */
    public static final float FAR_PLANE = 65_536f;

    private static final boolean REQUESTED = "own".equals(System.getProperty("quadrature.distant"));
    private static boolean decided;
    private static Distant distant;

    private DistantHook() {
    }

    /** Whether distant terrain was asked for on the command line. */
    public static boolean requested() {
        return REQUESTED;
    }

    /**
     * The far distance the game is about to build a projection with. With distant terrain on it is
     * pushed out, for every projection of the world pass alike, so that near and distant terrain,
     * and everything the game and other mods draw in the world, share one mapping from distance to
     * depth and hide each other correctly.
     */
    public static float far(float far) {
        return REQUESTED ? Math.max(far, FAR_PLANE) : far;
    }

    /** Draws distant terrain. Called once a world pass, after the opaque layers of near terrain. */
    public static void draw(float partialTicks) {
        if (!REQUESTED) {
            return;
        }
        if (!decided) {
            decided = true;
            if (!TerrainHook.requested()) {
                Quadrature.LOGGER.error("Distant terrain was requested without own near terrain "
                    + "(-Dquadrature.terrain=own); it stays off.");
            } else if (!Loader.isModLoaded("cubicchunks")) {
                Quadrature.LOGGER.error("Distant terrain was requested, but Cubature is not installed.");
            } else {
                distant = Distant.start();
            }
        }
        if (distant != null && TerrainHook.active()) {
            distant.frame(partialTicks);
        }
    }
}
