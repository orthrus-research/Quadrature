package cc.orthrus.quadrature.core.view;

/**
 * The layers of the light's depth and the box each is drawn through. Every layer is 4,096 texels a
 * side, in an array texture of 24-bit depth kept in 4 bytes a texel, 64 MiB a layer.
 *
 * <p>With the near layer on there are two: layer 0, a box 192 blocks across centered on the player,
 * 0.047 of a block a texel, and as deep along the light as the wide box, so that what stands far
 * toward a low light casts into it; and layer 1, the box that holds every cube the client has. With it
 * off there is one, that wide box, as layer 0. Each box is moved to a whole texel of its own along the
 * light's axes, measured from the world's origin, by {@link View#alongLight(View, View, double, double,
 * double, double, double, int)}.
 */
public final class ShadowLayers {

    /** Texels along a side of every layer. */
    public static final int TEXELS = 4096;
    /** The near box's side across the light, in blocks; along the light it is as deep as the wide box. */
    public static final double NEAR_SIDE = 192;
    /** Bytes a texel takes: 24 bits of depth in 4 bytes. */
    public static final int BYTES_A_TEXEL = 4;

    private final boolean near;

    /** @param near whether the near layer is drawn */
    public ShadowLayers(boolean near) {
        this.near = near;
    }

    public boolean near() {
        return near;
    }

    /** How many layers the texture has: 2 with the near layer, 1 without. */
    public int count() {
        return near ? 2 : 1;
    }

    /** The near box's layer, or -1 where it is not drawn. */
    public int nearLayer() {
        return near ? 0 : -1;
    }

    /** The wide box's layer. */
    public int wideLayer() {
        return near ? 1 : 0;
    }

    /** The memory the texture takes. */
    public long bytes() {
        return (long) TEXELS * TEXELS * BYTES_A_TEXEL * count();
    }

    /** The memory one layer takes. */
    public static long layerBytes() {
        return (long) TEXELS * TEXELS * BYTES_A_TEXEL;
    }

    /** Blocks a texel covers in a box of a side. */
    public static double texel(double side) {
        return side / TEXELS;
    }

    /**
     * Fills {@code out} as the near box along the light, centered on {@code main}'s origin and moved to
     * a whole texel of its own: {@link #NEAR_SIDE} across, and as deep along the light as the wide box,
     * so that what stands far toward a low light, outside the near box's square, still casts into it.
     *
     * @param depth the box's extent along the light, in blocks: the wide box's side
     * @return the box's side across, in blocks
     */
    public static double nearBox(View out, View main, double towardLightX, double towardLightY, double towardLightZ,
        double depth) {
        View.alongLight(out, main, towardLightX, towardLightY, towardLightZ, NEAR_SIDE, depth, TEXELS);
        return NEAR_SIDE;
    }

    /**
     * Whether a place has moved into another texel of a box since another place, along the axes of
     * the view the box was drawn through: the rule by which a layer is drawn again when the player
     * moves.
     *
     * @param modelView the view's model-view matrix, column-major; its first three rows are the
     *                  light's axes
     * @param texel     blocks a texel of the layer covers
     */
    public static boolean newTexel(float[] modelView, double texel, double fromX, double fromY, double fromZ,
        double toX, double toY, double toZ) {
        for (int axis = 0; axis < 3; axis++) {
            double now = modelView[axis] * toX + modelView[4 + axis] * toY + modelView[8 + axis] * toZ;
            double before = modelView[axis] * fromX + modelView[4 + axis] * fromY + modelView[8 + axis] * fromZ;
            if (Math.rint(now / texel) != Math.rint(before / texel)) {
                return true;
            }
        }
        return false;
    }
}
