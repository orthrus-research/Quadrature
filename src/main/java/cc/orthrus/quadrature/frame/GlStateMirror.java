package cc.orthrus.quadrature.frame;

import org.lwjgl.opengl.GL11;

/**
 * What the game's state cache, {@code GlStateManager}, believes of the state a stage of Quadrature's own
 * changes and must put back: kept by {@code MixinGlStateManager} as each setter is called, so that a
 * stage learns it without asking the driver. A {@code glGet} waits for the driver's own thread to catch
 * up with everything sent before it, which in a frame is milliseconds; this costs a field write a call.
 *
 * <p>The values are the game's as it passed them, before reversed depth translates a depth function or
 * an offset; a stage puts them back through {@link cc.orthrus.quadrature.depth.DepthCalls}, which
 * translates them as the game's own calls are. A mod that calls the driver past the cache leaves both
 * the cache and this mirror behind, and a stage then puts back what the game believes.
 *
 * <p>Read and written on the thread that owns the context. The first values are the cache's own.
 */
public final class GlStateMirror {

    private GlStateMirror() {
    }

    public static int depthFunction = GL11.GL_LESS;
    public static boolean depthMask = true;
    public static boolean cull;
    public static boolean polygonOffsetFill;
    public static float polygonOffsetFactor;
    public static float polygonOffsetUnits;
}
