package cc.orthrus.quadrature.core.terrain;

/**
 * A pyramid over a reversed depth buffer (1 nearest, 0 farthest), and the test of a box against it.
 * The GPU's compute stages do the same arithmetic; this is their reference.
 *
 * <p>Level 0 is the depth itself. Each further level is half as wide and high as the one below, rounded
 * down and at least 1, as OpenGL sizes a texture's levels, and each of its texels holds the farthest
 * (least) depth of the texels below it: two by two, and three where the level below is odd and the texel
 * is the last of its row or column. A pixel lies under texel {@code min(p >> level, size - 1)} of a level,
 * and a texel's depth is the farthest of exactly the pixels that lie under it.
 *
 * <p>A box is hidden when its nearest depth is farther than the farthest depth under the screen rectangle
 * it covers. The rectangle is read at the finest level where it lies under at most two texels each way,
 * so at most four are read; they cover the rectangle and more, which only makes the test keep more.
 */
public final class DepthPyramid {

    /** What a box's test found. */
    public enum Result {
        /** Its nearest point is farther than everything drawn under it. */
        HIDDEN,
        /** Something under it may be farther than its nearest point. */
        KEPT,
        /** Wholly outside the viewport. */
        OUTSIDE,
        /** A corner lies at or behind the eye's plane, so it has no rectangle; kept. */
        AT_EYE
    }

    private final int[] widths;
    private final int[] heights;
    private final float[][] levels;

    /** The pyramid over a depth buffer of {@code width} by {@code height}, rows from the bottom. */
    public DepthPyramid(float[] depth, int width, int height) {
        int count = levels(width, height);
        widths = new int[count];
        heights = new int[count];
        levels = new float[count][];
        widths[0] = width;
        heights[0] = height;
        levels[0] = depth.clone();
        for (int level = 1; level < count; level++) {
            widths[level] = next(widths[level - 1]);
            heights[level] = next(heights[level - 1]);
            levels[level] = reduce(levels[level - 1], widths[level - 1], heights[level - 1]);
        }
    }

    /** How many levels a buffer of this size has: down to one texel each way. */
    public static int levels(int width, int height) {
        int count = 1;
        for (int size = Math.max(width, height); size > 1; size >>= 1) {
            count++;
        }
        return count;
    }

    /** The size of the next level along one axis. */
    public static int next(int size) {
        return Math.max(1, size / 2);
    }

    /** The next level of a level of {@code width} by {@code height}: each texel the farthest below it. */
    public static float[] reduce(float[] level, int width, int height) {
        int nextWidth = next(width);
        int nextHeight = next(height);
        float[] out = new float[nextWidth * nextHeight];
        for (int y = 0; y < nextHeight; y++) {
            int y1 = y == nextHeight - 1 ? height - 1 : 2 * y + 1;
            for (int x = 0; x < nextWidth; x++) {
                int x1 = x == nextWidth - 1 ? width - 1 : 2 * x + 1;
                float farthest = Float.POSITIVE_INFINITY;
                for (int sy = Math.min(2 * y, height - 1); sy <= y1; sy++) {
                    for (int sx = Math.min(2 * x, width - 1); sx <= x1; sx++) {
                        farthest = Math.min(farthest, level[sy * width + sx]);
                    }
                }
                out[y * nextWidth + x] = farthest;
            }
        }
        return out;
    }

    public int levelCount() {
        return levels.length;
    }

    public int width(int level) {
        return widths[level];
    }

    public int height(int level) {
        return heights[level];
    }

    /** The texel's depth at a level. */
    public float at(int level, int x, int y) {
        return levels[level][y * widths[level] + x];
    }

    /**
     * The level a rectangle of pixels, inclusive, is read at: the finest where it lies under at most two
     * texels each way.
     */
    public static int levelFor(int minX, int minY, int maxX, int maxY, int[] widths, int[] heights) {
        int level = 0;
        while (level < widths.length - 1
            && (under(maxX, level, widths[level]) - under(minX, level, widths[level]) > 1
                || under(maxY, level, heights[level]) - under(minY, level, heights[level]) > 1)) {
            level++;
        }
        return level;
    }

    /** The texel a pixel lies under along one axis. */
    public static int under(int pixel, int level, int size) {
        return Math.min(pixel >> level, size - 1);
    }

    /** The farthest depth under a rectangle of pixels, inclusive, as the test reads it. */
    public float farthestUnder(int minX, int minY, int maxX, int maxY) {
        int level = levelFor(minX, minY, maxX, maxY, widths, heights);
        int x0 = under(minX, level, widths[level]);
        int x1 = under(maxX, level, widths[level]);
        int y0 = under(minY, level, heights[level]);
        int y1 = under(maxY, level, heights[level]);
        float farthest = Float.POSITIVE_INFINITY;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                farthest = Math.min(farthest, at(level, x, y));
            }
        }
        return farthest;
    }

    /**
     * Tests an axis-aligned box against the pyramid.
     *
     * @param viewProjection column-major, mapping the box's coordinates to clip space whose depth runs from
     *                       0 to w (reversed depth: w at the near plane)
     * @param viewport x, y, width and height of the viewport in the buffer's pixels
     */
    public Result test(float[] viewProjection, int[] viewport, float minX, float minY, float minZ, float maxX,
                       float maxY, float maxZ) {
        return test(viewProjection, viewport, false, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * As {@link #test(float[], int[], float, float, float, float, float, float)}, for a pyramid built from a
     * conventional depth buffer ({@code conventional}: clip depth from -w nearest to w, depth 0 nearest, the
     * pyramid built over one minus the depth, so that it reads as reversed depth).
     */
    public Result test(float[] viewProjection, int[] viewport, boolean conventional, float minX, float minY, float minZ,
                       float maxX, float maxY, float maxZ) {
        float left = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float bottom = Float.POSITIVE_INFINITY;
        float top = Float.NEGATIVE_INFINITY;
        float nearest = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            float x = (corner & 1) == 0 ? minX : maxX;
            float y = (corner & 2) == 0 ? minY : maxY;
            float z = (corner & 4) == 0 ? minZ : maxZ;
            float[] m = viewProjection;
            float clipX = m[0] * x + m[4] * y + m[8] * z + m[12];
            float clipY = m[1] * x + m[5] * y + m[9] * z + m[13];
            float clipZ = m[2] * x + m[6] * y + m[10] * z + m[14];
            float clipW = m[3] * x + m[7] * y + m[11] * z + m[15];
            if (clipW <= EYE_PLANE) {
                return Result.AT_EYE;
            }
            float screenX = viewport[0] + (clipX / clipW * 0.5f + 0.5f) * viewport[2];
            float screenY = viewport[1] + (clipY / clipW * 0.5f + 0.5f) * viewport[3];
            left = Math.min(left, screenX);
            right = Math.max(right, screenX);
            bottom = Math.min(bottom, screenY);
            top = Math.max(top, screenY);
            nearest = Math.max(nearest, conventional ? 0.5f - 0.5f * clipZ / clipW : clipZ / clipW);
        }
        int width = widths[0];
        int height = heights[0];
        int x0 = Math.max(viewport[0], 0);
        int y0 = Math.max(viewport[1], 0);
        int x1 = Math.min(viewport[0] + viewport[2], width) - 1;
        int y1 = Math.min(viewport[1] + viewport[3], height) - 1;
        if (right < x0 || left > x1 + 1 || top < y0 || bottom > y1 + 1) {
            return Result.OUTSIDE;
        }
        int minPixelX = Math.max(x0, (int) Math.floor(left));
        int maxPixelX = Math.min(x1, (int) Math.floor(right));
        int minPixelY = Math.max(y0, (int) Math.floor(bottom));
        int maxPixelY = Math.min(y1, (int) Math.floor(top));
        return nearest < farthestUnder(minPixelX, minPixelY, maxPixelX, maxPixelY) ? Result.HIDDEN : Result.KEPT;
    }

    /** A clip w at or under this is taken as the eye's plane. */
    public static final float EYE_PLANE = 1e-4f;
}
