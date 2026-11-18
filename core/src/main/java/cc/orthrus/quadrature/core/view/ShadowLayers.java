package cc.orthrus.quadrature.core.view;

/**
 * The layers of the light's depth and the boxes each is drawn through. Every layer is 4,096 texels a
 * side, in an array texture of 24-bit depth kept in 4 bytes a texel, 64 MiB a layer; a scratch texture
 * of as many layers is where a new box is drawn before it replaces a published one
 * ({@link LightLayerPlan}).
 *
 * <p>With the near layer on there are two: layer 0, a box 192 blocks across, 0.047 of a block a
 * texel, and as deep along the light as the wide box, so that what stands far toward a low light casts
 * into it; and layer 1, the box that holds every cube the client has. With it off there is one, that
 * wide box, as layer 0. Each box is fixed in the world at a whole texel of its own
 * ({@link LightBox}).
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

    /** The memory the published texture takes. */
    public long bytes() {
        return (long) TEXELS * TEXELS * BYTES_A_TEXEL * count();
    }

    /** The memory the published texture and its scratch take together. */
    public long bytesWithScratch() {
        return 2 * bytes();
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
     * The wide box's side: the diagonal of the square of cubes the client holds about the player, the
     * most any axis across the light can span, whatever the hour.
     *
     * @param across the view's reach across, in cubes
     * @param upward the view's reach upward, in cubes
     */
    public static double wideSide(int across, int upward) {
        return Math.ceil(2 * Math.sqrt(2) * Math.max(across, upward) * 16);
    }
}
