package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.terrain.TerrainHook;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.Loader;

import org.lwjgl.opengl.GL11;

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
    private static boolean sampling;
    private static boolean fogSaid;

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

    /** Told by distant terrain each world pass: whether it has a terrain preview to draw from. */
    static void sampling(boolean now) {
        sampling = now;
    }

    /**
     * The game has just set its fog for the world, with {@code far} as its view distance in blocks.
     * While distant terrain draws, the game's ordinary fog (linear, complete at the view distance)
     * is given distant terrain's two distances instead, so near terrain, everything the game draws
     * in the world and distant terrain fade by one rule. Any other fog is left alone: under water or
     * lava, in blindness, inside a cloud, or whatever another mod has set, none of which is linear
     * and ends at the view distance.
     */
    public static void fog(float far) {
        if (!REQUESTED || !sampling || !DistantSettings.FOG
            || DistantSettings.DEBUG == DistantSettings.DEBUG_LEVEL) {
            return;
        }
        if (GL11.glGetInteger(GL11.GL_FOG_MODE) != GL11.GL_LINEAR
            || Math.abs(GL11.glGetFloat(GL11.GL_FOG_END) - far) > 0.01f) {
            return;
        }
        GlStateManager.setFogStart(DistantSettings.FOG_START);
        GlStateManager.setFogEnd(DistantSettings.FOG_END);
        if (!fogSaid) {
            fogSaid = true;
            Quadrature.LOGGER.info("Distant terrain: the world's fog runs from {} to {} blocks for near and distant "
                + "terrain alike, in place of the game's {} to {}.", DistantSettings.FOG_START,
                DistantSettings.FOG_END, far * 0.75f, far);
        }
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
        } else {
            sampling = false;
        }
    }
}
