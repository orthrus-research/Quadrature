package cc.orthrus.quadrature.core.stage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * What a shading stage is told of the frame, laid out as a std140 uniform block of 592 bytes:
 *
 * <pre>
 *   0  mat4     inverse view-projection, the eye at the origin
 *  64  mat4[4]  light-view matrices, from the world relative to the eye
 * 320  vec4[4]  light-view scales: x a texel's size in blocks, y the depth's span in blocks
 * 384  vec4     the direction toward the sun
 * 400  vec4     the direction toward the light that casts shadows
 * 416  ivec4    the eye's block
 * 432  vec4     the eye's place within its block
 * 448  vec4     the targets' width and height, and one over each
 * 464  vec4     weather: rain 0 to 1, wetness 0 to 1, an exposure multiplier, time in seconds
 * 480  ivec4    frame: its index, whether history must be dropped, the dimension, and how many light
 *               views are given, which is 0 where no light's depth is drawn: nothing is then in shadow
 * 496  mat4     the view-projection of the frame before, that frame's eye at the origin; this frame's
 *               own on a frame that drops history
 * 560  vec4     how far the eye moved since the frame before, in blocks: this eye less that one; in w,
 *               the horizontal distance in blocks to the far edge of what was written into the
 *               targets, or 0 where it is not known
 * 576  vec4     how far the clouds have drifted, in blocks: x by the faster drift, y by the slower; in
 *               z, the height the world keeps its clouds at, in blocks
 * </pre>
 *
 * Matrices are column-major. The inverse view-projection takes clip x and y in -1 to 1 and the
 * stored depth as z to a place along the world's axes with the eye at the origin. A light-view
 * matrix takes such a place to the light's clip x and y in -1 to 1 and a depth from 0, nearest the
 * light, to 1.
 */
public final class FrameBlock {

    /** The frame block's size in bytes, as a uniform block of the std140 layout. */
    public static final int BYTES = 592;
    public static final int LIGHT_VIEWS = 4;
    /** Seconds at which the block's time starts again. */
    public static final double TIME_WRAP = 3600;

    public static final int OFFSET_INVERSE_VIEW_PROJECTION = 0;
    public static final int OFFSET_LIGHT_VIEWS = 64;
    public static final int OFFSET_LIGHT_SCALES = 320;
    public static final int OFFSET_SUN = 384;
    public static final int OFFSET_LIGHT = 400;
    public static final int OFFSET_EYE_BLOCK = 416;
    public static final int OFFSET_EYE_FRACTION = 432;
    public static final int OFFSET_TARGET_SIZE = 448;
    public static final int OFFSET_WEATHER = 464;
    public static final int OFFSET_FRAME = 480;
    public static final int OFFSET_PREVIOUS_VIEW_PROJECTION = 496;
    public static final int OFFSET_EYE_MOVEMENT = 560;
    public static final int OFFSET_CLOUD_DRIFT = 576;

    /** The block's members by the names a stage's programs give them, each with where it lies. */
    private static final Object[][] MEMBERS = {
        {"qInverseViewProjection", OFFSET_INVERSE_VIEW_PROJECTION},
        {"qShadowViewProjection", OFFSET_LIGHT_VIEWS},
        {"qShadowScale", OFFSET_LIGHT_SCALES},
        {"qSunDirection", OFFSET_SUN},
        {"qLightDirection", OFFSET_LIGHT},
        {"qCameraBlock", OFFSET_EYE_BLOCK},
        {"qCameraFraction", OFFSET_EYE_FRACTION},
        {"qTargetSize", OFFSET_TARGET_SIZE},
        {"qWeather", OFFSET_WEATHER},
        {"qFrame", OFFSET_FRAME},
        {"qPreviousViewProjection", OFFSET_PREVIOUS_VIEW_PROJECTION},
        {"qEyeMovement", OFFSET_EYE_MOVEMENT},
        {"qCloudDrift", OFFSET_CLOUD_DRIFT},
    };

    /**
     * Where a member lies by the name a driver gives it, which may end in {@code [0]} for an array and
     * begin with the block's own name and a period; -1 for a name that is no member.
     */
    public static int offsetOf(String member) {
        String name = member.substring(member.lastIndexOf('.') + 1);
        int bracket = name.indexOf('[');
        if (bracket >= 0) {
            if (!name.substring(bracket).equals("[0]")) {
                return -1;
            }
            name = name.substring(0, bracket);
        }
        for (Object[] known : MEMBERS) {
            if (known[0].equals(name)) {
                return (Integer) known[1];
            }
        }
        return -1;
    }

    public final float[] inverseViewProjection = new float[16];
    public final float[][] lightViews = new float[LIGHT_VIEWS][16];
    public final float[][] lightScales = new float[LIGHT_VIEWS][4];
    public final float[] sun = new float[4];
    public final float[] light = new float[4];
    public final int[] eyeBlock = new int[4];
    public final float[] eyeFraction = new float[4];
    public final float[] targetSize = new float[4];
    public final float[] weather = new float[4];
    public final int[] frame = new int[4];
    public final float[] previousViewProjection = new float[16];
    public final float[] eyeMovement = new float[4];
    public final float[] cloudDrift = new float[4];

    /** Sets the eye from its place in the world: the block it is in, and where in that block. */
    public void eye(double x, double y, double z) {
        double[] at = {x, y, z};
        for (int axis = 0; axis < 3; axis++) {
            double block = Math.floor(at[axis]);
            eyeBlock[axis] = (int) block;
            eyeFraction[axis] = (float) (at[axis] - block);
        }
        eyeBlock[3] = 0;
        eyeFraction[3] = 0;
    }

    /** Sets the targets' size and one over each side. */
    public void targetSize(int width, int height) {
        targetSize[0] = width;
        targetSize[1] = height;
        targetSize[2] = 1f / width;
        targetSize[3] = 1f / height;
    }

    /**
     * @param rain     0 to 1
     * @param wetness  0 to 1
     * @param exposure a multiplier
     * @param seconds  time, of any size: it is wrapped at {@link #TIME_WRAP}
     */
    public void weather(float rain, float wetness, float exposure, double seconds) {
        weather[0] = rain;
        weather[1] = wetness;
        weather[2] = exposure;
        double wrapped = seconds % TIME_WRAP;
        weather[3] = (float) (wrapped < 0 ? wrapped + TIME_WRAP : wrapped);
    }

    /**
     * @param index     the frame's index
     * @param reset     whether what a stage kept from earlier frames must be dropped
     * @param dimension the game's dimension; any but the nether's -1 and the end's 1 is given as 0
     * @param layers    how many light views are given, 0 to {@link #LIGHT_VIEWS}
     */
    public void frame(long index, boolean reset, int dimension, int layers) {
        frame[0] = (int) index;
        frame[1] = reset ? 1 : 0;
        frame[2] = dimension == -1 || dimension == 1 ? dimension : 0;
        frame[3] = Math.max(0, Math.min(LIGHT_VIEWS, layers));
    }

    /** Sets a light view; one not set is all zeros. */
    public void lightView(int layer, float[] matrix, float texelBlocks, float depthBlocks) {
        System.arraycopy(matrix, 0, lightViews[layer], 0, 16);
        lightScales[layer][0] = texelBlocks;
        lightScales[layer][1] = depthBlocks;
        lightScales[layer][2] = 0;
        lightScales[layer][3] = 0;
    }

    /** Writes the block: {@link #BYTES} bytes from the buffer's position, little-endian. */
    public void write(ByteBuffer out) {
        ByteBuffer block = out.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int start = block.position();
        put(block, inverseViewProjection);
        for (float[] view : lightViews) {
            put(block, view);
        }
        for (float[] scale : lightScales) {
            put(block, scale);
        }
        put(block, sun);
        put(block, light);
        put(block, eyeBlock);
        put(block, eyeFraction);
        put(block, targetSize);
        put(block, weather);
        put(block, frame);
        put(block, previousViewProjection);
        put(block, eyeMovement);
        put(block, cloudDrift);
        if (block.position() - start != BYTES) {
            throw new IllegalStateException("The frame block is " + (block.position() - start) + " bytes");
        }
        out.position(out.position() + BYTES);
    }

    private static void put(ByteBuffer out, float[] values) {
        for (float value : values) {
            out.putFloat(value);
        }
    }

    private static void put(ByteBuffer out, int[] values) {
        for (int value : values) {
            out.putInt(value);
        }
    }

    // The matrices.

    /** a * b, column-major. */
    public static double[] multiply(double[] a, double[] b) {
        double[] out = new double[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[column * 4 + k];
                }
                out[column * 4 + row] = sum;
            }
        }
        return out;
    }

    public static double[] doubles(float[] matrix) {
        double[] out = new double[16];
        for (int i = 0; i < 16; i++) {
            out[i] = matrix[i];
        }
        return out;
    }

    public static float[] floats(double[] matrix) {
        float[] out = new float[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (float) matrix[i];
        }
        return out;
    }

    /** The inverse of a matrix, by cofactors; null where it has none. */
    public static double[] inverse(double[] m) {
        double[] inv = new double[16];
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11]
            - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11]
            + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11]
            - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10]
            + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11]
            + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11]
            - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11]
            + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10]
            - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7]
            - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7]
            + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7]
            - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6]
            + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7]
            + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7]
            - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7]
            + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6]
            - m[8] * m[2] * m[5];
        double determinant = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        if (determinant == 0 || Double.isNaN(determinant)) {
            return null;
        }
        for (int i = 0; i < 16; i++) {
            inv[i] /= determinant;
        }
        return inv;
    }

    /**
     * The matrix from clip space, x and y in -1 to 1 and z the depth as stored, to a place along the
     * world's axes with the eye at the origin: the inverse of the projection times the model-view's
     * rotation alone. The model-view's translation, which holds the eye's height above the view's
     * origin and what the game adds for bobbing and for a view from behind, is left out, so the place
     * is relative to where the camera stands.
     *
     * @return the matrix, or null where the two have no inverse
     */
    public static double[] inverseViewProjectionAtEye(float[] projection, float[] modelView) {
        return inverse(viewProjectionAtEye(projection, modelView));
    }

    /**
     * The matrix from a place along the world's axes with the eye at the origin to clip space: the
     * projection times the model-view's rotation alone.
     */
    public static double[] viewProjectionAtEye(float[] projection, float[] modelView) {
        double[] rotation = doubles(modelView);
        rotation[12] = 0;
        rotation[13] = 0;
        rotation[14] = 0;
        rotation[3] = 0;
        rotation[7] = 0;
        rotation[11] = 0;
        rotation[15] = 1;
        return multiply(doubles(projection), rotation);
    }

    /**
     * A light view's matrix for the block, from the one it was drawn through.
     *
     * @param drawn    takes a place relative to the light view's own origin to the light's clip
     *                 space, x and y in -1 to 1 and z from -1 nearest the light to 1
     * @param eyeX     the eye's place minus the light view's origin: how far the eye stands from it
     * @return the matrix from a place relative to the eye to the light's clip x and y in -1 to 1 and
     *         a depth from 0 nearest the light to 1
     */
    public static double[] lightViewFromEye(float[] drawn, double eyeX, double eyeY, double eyeZ) {
        double[] move = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, eyeX, eyeY, eyeZ, 1};
        // Depth from -1 to 1 becomes 0 to 1: z' = z / 2 + w / 2.
        double[] depth = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0.5, 0, 0, 0, 0.5, 1};
        return multiply(depth, multiply(doubles(drawn), move));
    }

    /**
     * Where a texel of a light view's depth lies: the inverse of {@link #lightViewFromEye}'s matrix
     * applied to the texel's place and the depth it holds.
     *
     * @param fromEye the light view's matrix for the block, from a place relative to the eye
     * @param u       across the map, 0 to 1 from its first column
     * @param v       up the map, 0 to 1 from its first row
     * @param depth   the depth, 0 nearest the light to 1
     * @return the place relative to the eye, in blocks, or null where the matrix has no inverse
     */
    public static double[] fromLight(double[] fromEye, double u, double v, double depth) {
        double[] inverse = inverse(fromEye);
        if (inverse == null) {
            return null;
        }
        double[] place = apply(inverse, u * 2 - 1, v * 2 - 1, depth, 1);
        return new double[] {place[0] / place[3], place[1] / place[3], place[2] / place[3]};
    }

    /** A matrix applied to a point, column-major: x, y, z and w. */
    public static double[] apply(double[] matrix, double x, double y, double z, double w) {
        double[] out = new double[4];
        for (int row = 0; row < 4; row++) {
            out[row] = matrix[row] * x + matrix[4 + row] * y + matrix[8 + row] * z + matrix[12 + row] * w;
        }
        return out;
    }
}
