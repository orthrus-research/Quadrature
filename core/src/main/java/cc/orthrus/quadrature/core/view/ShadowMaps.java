package cc.orthrus.quadrature.core.view;

/**
 * What is published each frame about the depth drawn along the light, for whoever samples it: one
 * array texture of depth, and for each of its layers the matrix it was drawn through and the box that
 * matrix covers.
 *
 * <p>A layer's matrix takes a position relative to the published origin, in blocks, to the layer's clip
 * space: a plain orthographic box, clip depth from -1 nearest the light to 1 farthest. The previous
 * frame's matrix is relative to the previous frame's origin. The host draws with no depth bias beyond
 * the polygon offset named here; a sampler biases as it sees fit.
 *
 * <p>The texture is not drawn every frame. The matrices and origin published are always those it was
 * last drawn with, never the light's of the moment, which is published beside them.
 *
 * <p>The texture can be sampled two ways, through one of two sampler objects: as depth compared with
 * a reference, filtered, or as the depth itself, unfiltered.
 */
public final class ShadowMaps {

    private final int layers;
    private final int side;
    private final long bytes;
    private int texture;
    private int compareSampler;
    private int depthSampler;
    private final float[][] viewProjection;
    private final float[][] previousViewProjection;
    private final double[] boxSide;
    private final double[] origin = new double[3];
    private final double[] previousOrigin = new double[3];
    private final float[] towardLight = new float[3];
    private final float[] drawnTowardLight = new float[3];
    private float polygonOffsetFactor;
    private float polygonOffsetUnits;
    private long generation;
    private long contentGeneration;
    private boolean drawn;

    /**
     * @param layers how many layers the texture has
     * @param side   the texels along a side of each layer
     * @param bytes  the memory the texture takes
     */
    public ShadowMaps(int layers, int side, long bytes) {
        this.layers = layers;
        this.side = side;
        this.bytes = bytes;
        viewProjection = new float[layers][16];
        previousViewProjection = new float[layers][16];
        boxSide = new double[layers];
    }

    // What the host sets.

    public void objects(int texture, int compareSampler, int depthSampler) {
        this.texture = texture;
        this.compareSampler = compareSampler;
        this.depthSampler = depthSampler;
    }

    public void polygonOffset(float factor, float units) {
        polygonOffsetFactor = factor;
        polygonOffsetUnits = units;
    }

    /**
     * Starts a frame in which the texture is drawn again: what was current becomes previous, and the
     * frame's origin and light are the ones it is drawn with.
     */
    public void beginFrame(double originX, double originY, double originZ, double towardLightX,
        double towardLightY, double towardLightZ) {
        hold(towardLightX, towardLightY, towardLightZ);
        origin[0] = originX;
        origin[1] = originY;
        origin[2] = originZ;
        System.arraycopy(towardLight, 0, drawnTowardLight, 0, 3);
    }

    /**
     * Starts a frame in which the texture is kept as it is: its matrices and origin stay those it was
     * drawn with, the frame before's become the same, and only the direction toward the light moves on.
     */
    public void hold(double towardLightX, double towardLightY, double towardLightZ) {
        for (int layer = 0; layer < layers; layer++) {
            System.arraycopy(viewProjection[layer], 0, previousViewProjection[layer], 0, 16);
        }
        System.arraycopy(origin, 0, previousOrigin, 0, 3);
        double length = Math.sqrt(towardLightX * towardLightX + towardLightY * towardLightY
            + towardLightZ * towardLightZ);
        towardLight[0] = (float) (towardLightX / length);
        towardLight[1] = (float) (towardLightY / length);
        towardLight[2] = (float) (towardLightZ / length);
    }

    /**
     * Sets a layer's matrix from the view it is drawn through.
     *
     * @param contentChanged whether what the layer holds can differ from the frame before apart from
     *                       the light's own motion: a mesh in the box was placed, replaced or
     *                       released, or the box moved to another texel
     */
    public void layer(int layer, View view, double boxSide, boolean contentChanged) {
        float[] out = viewProjection[layer];
        float[] p = view.projection();
        float[] m = view.modelView();
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += p[k * 4 + row] * m[column * 4 + k];
                }
                out[column * 4 + row] = sum;
            }
        }
        this.boxSide[layer] = boxSide;
        boolean moved = !drawn || origin[0] != previousOrigin[0] || origin[1] != previousOrigin[1]
            || origin[2] != previousOrigin[2] || !java.util.Arrays.equals(out, previousViewProjection[layer]);
        if (moved || contentChanged) {
            generation++;
        }
        if (contentChanged || !drawn) {
            contentGeneration++;
        }
        drawn = true;
    }

    // What a sampler reads.

    public int layers() {
        return layers;
    }

    /** Texels along a side of each layer. */
    public int side() {
        return side;
    }

    /** The memory the texture takes. */
    public long bytes() {
        return bytes;
    }

    /** The array texture's name, or 0 before the first frame it is drawn in. */
    public int texture() {
        return texture;
    }

    /** A sampler that compares a reference with the depth, less-or-equal, filtered. */
    public int compareSampler() {
        return compareSampler;
    }

    /** A sampler that returns the depth itself, unfiltered. */
    public int depthSampler() {
        return depthSampler;
    }

    /** From a position relative to {@link #origin}, in blocks, to the layer's clip space. Column-major. */
    public float[] viewProjection(int layer) {
        return viewProjection[layer];
    }

    /** The frame before's matrix, from a position relative to {@link #previousOrigin}. */
    public float[] previousViewProjection(int layer) {
        return previousViewProjection[layer];
    }

    /** The side of the layer's box, in blocks; it is as deep as it is wide. */
    public double boxSide(int layer) {
        return boxSide[layer];
    }

    /** The world position the frame's matrices are relative to. */
    public double[] origin() {
        return origin;
    }

    public double[] previousOrigin() {
        return previousOrigin;
    }

    /** A unit vector from the scene toward the sun, or the moon while the sun is down, this frame. */
    public float[] towardLight() {
        return towardLight;
    }

    /** The direction toward the light the texture was last drawn along; the light may have turned a little since. */
    public float[] drawnTowardLight() {
        return drawnTowardLight;
    }

    public float polygonOffsetFactor() {
        return polygonOffsetFactor;
    }

    public float polygonOffsetUnits() {
        return polygonOffsetUnits;
    }

    /** Changes whenever what the texture holds may differ from the frame before, the light's motion included. */
    public long generation() {
        return generation;
    }

    /**
     * Changes only when what the texture holds can differ apart from the light's own motion: a mesh in
     * the box was placed, replaced or released, or the box moved to another texel. The matrices say how
     * far the light has turned; how much of that matters is the sampler's to judge.
     */
    public long contentGeneration() {
        return contentGeneration;
    }
}
