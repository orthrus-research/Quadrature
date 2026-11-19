package cc.orthrus.quadrature.core.view;

/**
 * What is published each frame about the depth drawn along the light, for whoever samples it: one
 * array texture of depth, and for each of its layers the matrix it was drawn through and the box that
 * matrix covers.
 *
 * <p>A layer's matrix takes a position relative to this frame's origin, in blocks, to the layer's clip
 * space: a plain orthographic box, clip depth from -1 nearest the light to 1 farthest. It is made each
 * frame from the layer's {@link LightBox}, which is fixed in the world, so it stays exact however long
 * the layer is kept. The previous frame's matrix is relative to the previous frame's origin. The host
 * draws with no depth bias beyond the polygon offset named here; a sampler biases as it sees fit.
 *
 * <p>A layer is drawn a tile at a time and its box is replaced only when a new one has been drawn
 * whole, so the light a layer was drawn along can trail this frame's by a set angle, and each layer
 * by its own; both are published. A box need not be centered on the player: it is moved only once the
 * player has come a share of its side from its center ({@link LightLayerPlan#DRIFT_SHARE}).
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
    private final double[] boxDepth;
    private final float[][] drawnTowardLight;
    private final double[] origin = new double[3];
    private final double[] previousOrigin = new double[3];
    private final float[] towardLight = new float[3];
    private float polygonOffsetFactor;
    private float polygonOffsetUnits;
    private long generation;
    private long contentGeneration;

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
        boxDepth = new double[layers];
        drawnTowardLight = new float[layers][3];
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

    /** Starts a frame: what was current becomes previous; the frame's origin and light are given. */
    public void beginFrame(double originX, double originY, double originZ, double towardLightX,
        double towardLightY, double towardLightZ) {
        for (int layer = 0; layer < layers; layer++) {
            System.arraycopy(viewProjection[layer], 0, previousViewProjection[layer], 0, 16);
        }
        System.arraycopy(origin, 0, previousOrigin, 0, 3);
        origin[0] = originX;
        origin[1] = originY;
        origin[2] = originZ;
        double length = Math.sqrt(towardLightX * towardLightX + towardLightY * towardLightY
            + towardLightZ * towardLightZ);
        towardLight[0] = (float) (towardLightX / length);
        towardLight[1] = (float) (towardLightY / length);
        towardLight[2] = (float) (towardLightZ / length);
    }

    /**
     * Publishes a layer for this frame.
     *
     * @param view           the view through the layer's box for this frame's origin
     *                       ({@link View#throughLight})
     * @param contentChanged whether what the layer holds may differ apart from the light's own motion
     *                       since the frame before: tiles drawn again, or a box for another place
     * @param boxChanged     whether the layer's box was replaced since the frame before
     */
    public void layer(int layer, View view, LightBox box, boolean contentChanged, boolean boxChanged) {
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
        boxSide[layer] = box.side();
        boxDepth[layer] = box.depth();
        for (int axis = 0; axis < 3; axis++) {
            drawnTowardLight[layer][axis] = (float) box.toward(axis);
        }
        if (contentChanged || boxChanged) {
            generation++;
        }
        if (contentChanged) {
            contentGeneration++;
        }
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

    /** The side of the layer's box across the light, in blocks. */
    public double boxSide(int layer) {
        return boxSide[layer];
    }

    /** The extent of the layer's box along the light, in blocks: the span its depth from 0 to 1 covers. */
    public double boxDepth(int layer) {
        return boxDepth[layer];
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

    /** The direction toward the light a layer was drawn along; the light may have turned a little since. */
    public float[] drawnTowardLight(int layer) {
        return drawnTowardLight[layer];
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
     * Changes only when what the texture holds can differ apart from the light's own motion: tiles were
     * drawn again for meshes placed, replaced or released, or a layer's box was replaced by one for
     * another place. The matrices say how far the light has turned; how much of that matters is the
     * sampler's to judge.
     */
    public long contentGeneration() {
        return contentGeneration;
    }
}
