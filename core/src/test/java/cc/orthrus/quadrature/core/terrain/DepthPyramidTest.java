package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.orthrus.quadrature.core.depth.ReversedDepth;

import java.util.Random;

import org.junit.jupiter.api.Test;

class DepthPyramidTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 36;

    @Test
    void levelsAreSizedAsOpenGlSizesATexturesLevels() {
        DepthPyramid pyramid = new DepthPyramid(new float[13 * 7], 13, 7);
        assertEquals(4, pyramid.levelCount());
        int[][] sizes = {{13, 7}, {6, 3}, {3, 1}, {1, 1}};
        for (int level = 0; level < sizes.length; level++) {
            assertEquals(sizes[level][0], pyramid.width(level));
            assertEquals(sizes[level][1], pyramid.height(level));
        }
    }

    @Test
    void eachTexelIsTheFarthestOfExactlyThePixelsUnderIt() {
        Random random = new Random(7);
        for (int[] size : new int[][] {{13, 7}, {64, 36}, {1, 5}, {9, 1}, {17, 16}}) {
            int width = size[0];
            int height = size[1];
            float[] depth = new float[width * height];
            for (int i = 0; i < depth.length; i++) {
                depth[i] = random.nextFloat();
            }
            DepthPyramid pyramid = new DepthPyramid(depth, width, height);
            for (int level = 0; level < pyramid.levelCount(); level++) {
                int w = pyramid.width(level);
                int h = pyramid.height(level);
                float[] expected = new float[w * h];
                java.util.Arrays.fill(expected, Float.POSITIVE_INFINITY);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int at = DepthPyramid.under(y, level, h) * w + DepthPyramid.under(x, level, w);
                        expected[at] = Math.min(expected[at], depth[y * width + x]);
                    }
                }
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        assertEquals(expected[y * w + x], pyramid.at(level, x, y), "level " + level + " at " + x + ", " + y
                            + " of " + width + " by " + height);
                    }
                }
            }
        }
    }

    @Test
    void aRectangleIsReadAtTheFinestLevelWithTwoTexelsEachWay() {
        DepthPyramid pyramid = new DepthPyramid(new float[WIDTH * HEIGHT], WIDTH, HEIGHT);
        int[] widths = new int[pyramid.levelCount()];
        int[] heights = new int[pyramid.levelCount()];
        for (int level = 0; level < widths.length; level++) {
            widths[level] = pyramid.width(level);
            heights[level] = pyramid.height(level);
        }
        Random random = new Random(11);
        for (int i = 0; i < 2000; i++) {
            int minX = random.nextInt(WIDTH);
            int maxX = minX + random.nextInt(WIDTH - minX);
            int minY = random.nextInt(HEIGHT);
            int maxY = minY + random.nextInt(HEIGHT - minY);
            int level = DepthPyramid.levelFor(minX, minY, maxX, maxY, widths, heights);
            assertTrue(spans(maxX, minX, level, widths) <= 1 && spans(maxY, minY, level, heights) <= 1);
            if (level > 0) {
                assertTrue(spans(maxX, minX, level - 1, widths) > 1 || spans(maxY, minY, level - 1, heights) > 1);
            }
        }
    }

    private static int spans(int max, int min, int level, int[] sizes) {
        return DepthPyramid.under(max, level, sizes[level]) - DepthPyramid.under(min, level, sizes[level]);
    }

    @Test
    void theFarthestUnderARectangleIsNeverNearerThanItsPixels() {
        Random random = new Random(3);
        float[] depth = new float[WIDTH * HEIGHT];
        for (int i = 0; i < depth.length; i++) {
            depth[i] = random.nextFloat();
        }
        DepthPyramid pyramid = new DepthPyramid(depth, WIDTH, HEIGHT);
        for (int i = 0; i < 2000; i++) {
            int minX = random.nextInt(WIDTH);
            int maxX = minX + random.nextInt(WIDTH - minX);
            int minY = random.nextInt(HEIGHT);
            int maxY = minY + random.nextInt(HEIGHT - minY);
            float least = Float.POSITIVE_INFINITY;
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    least = Math.min(least, depth[y * WIDTH + x]);
                }
            }
            assertTrue(pyramid.farthestUnder(minX, minY, maxX, maxY) <= least);
            assertTrue(pyramid.farthestUnder(minX, minY, maxX, maxY, 1) <= pyramid.farthestUnder(minX, minY, maxX, maxY));
        }
    }

    /** A perspective looking down -z, reversed into depth from 0 to w, as the frame draws. */
    private static float[] projection(double near, double far) {
        double f = 1.0 / Math.tan(Math.toRadians(70) / 2);
        double aspect = (double) WIDTH / HEIGHT;
        double[] m = new double[16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1;
        m[14] = 2 * far * near / (near - far);
        ReversedDepth.reverse(m);
        float[] out = new float[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (float) m[i];
        }
        return out;
    }

    /** The reversed depth of a point straight ahead at a distance. */
    private static float depthAt(float[] m, float distance) {
        float z = -distance;
        return (m[10] * z + m[14]) / (m[11] * z + m[15]);
    }

    @Test
    void aBoxBehindAWallIsHiddenAndOneBeforeItIsKept() {
        float[] m = projection(0.05, 512);
        int[] viewport = {0, 0, WIDTH, HEIGHT};
        float[] depth = new float[WIDTH * HEIGHT];
        java.util.Arrays.fill(depth, depthAt(m, 10));
        DepthPyramid wall = new DepthPyramid(depth, WIDTH, HEIGHT);
        assertEquals(DepthPyramid.Result.HIDDEN, wall.test(m, viewport, -2, -2, -20, 2, 2, -16));
        assertEquals(DepthPyramid.Result.KEPT, wall.test(m, viewport, -2, -2, -8, 2, 2, -4));
        // Reaching through the wall: its near face is nearer than the wall.
        assertEquals(DepthPyramid.Result.KEPT, wall.test(m, viewport, -2, -2, -20, 2, 2, -9));
        assertEquals(DepthPyramid.Result.OUTSIDE, wall.test(m, viewport, 200, -2, -20, 204, 2, -16));
        assertEquals(DepthPyramid.Result.AT_EYE, wall.test(m, viewport, -2, -2, -2, 2, 2, 2));
        // A hole in the wall where the box would show: kept.
        for (int y = 16; y < 20; y++) {
            for (int x = 30; x < 34; x++) {
                depth[y * WIDTH + x] = 0;
            }
        }
        DepthPyramid holed = new DepthPyramid(depth, WIDTH, HEIGHT);
        assertEquals(DepthPyramid.Result.KEPT, holed.test(m, viewport, -2, -2, -20, 2, 2, -16));
    }

    @Test
    void aConventionalOrthographicViewReadsAsReversed() {
        // An orthographic view down -z over 64 by 36 blocks, depth from z = 0 (nearest, -1) to z = -100 (1).
        float[] m = new float[16];
        m[0] = 2f / WIDTH;
        m[5] = 2f / HEIGHT;
        m[10] = -2f / 100;
        m[14] = -1;
        m[15] = 1;
        int[] viewport = {0, 0, WIDTH, HEIGHT};
        // A ground at z = -30: conventional depth 0.3, stored in the pyramid as one minus it.
        float[] flipped = new float[WIDTH * HEIGHT];
        java.util.Arrays.fill(flipped, 1 - 0.3f);
        DepthPyramid ground = new DepthPyramid(flipped, WIDTH, HEIGHT);
        assertEquals(DepthPyramid.Result.HIDDEN, ground.test(m, viewport, true, -4, -4, -60, 4, 4, -40));
        assertEquals(DepthPyramid.Result.KEPT, ground.test(m, viewport, true, -4, -4, -20, 4, 4, -10));
        assertEquals(DepthPyramid.Result.KEPT, ground.test(m, viewport, true, -4, -4, -40, 4, 4, -29));
    }

    @Test
    void whatIsHiddenIsBehindEveryPixelItCovers() {
        float[] m = projection(0.05, 512);
        int[] viewport = {0, 0, WIDTH, HEIGHT};
        Random random = new Random(5);
        int hidden = 0;
        for (int scene = 0; scene < 40; scene++) {
            float[] depth = new float[WIDTH * HEIGHT];
            float base = depthAt(m, 4 + random.nextFloat() * 40);
            for (int i = 0; i < depth.length; i++) {
                depth[i] = random.nextInt(50) == 0 ? 0 : base * (0.9f + 0.2f * random.nextFloat());
            }
            DepthPyramid pyramid = new DepthPyramid(depth, WIDTH, HEIGHT);
            for (int i = 0; i < 200; i++) {
                float x = random.nextFloat() * 60 - 30;
                float y = random.nextFloat() * 30 - 15;
                float z = -(5 + random.nextFloat() * 120);
                float size = 1 + random.nextFloat() * 16;
                DepthPyramid.Result result = pyramid.test(m, viewport, false, i % 2, x, y, z - size, x + size, y + size, z);
                if (result != DepthPyramid.Result.HIDDEN) {
                    continue;
                }
                hidden++;
                // Every pixel the box could touch is nearer than the box's nearest point.
                float nearest = Float.NEGATIVE_INFINITY;
                float left = Float.POSITIVE_INFINITY;
                float right = Float.NEGATIVE_INFINITY;
                float bottom = Float.POSITIVE_INFINITY;
                float top = Float.NEGATIVE_INFINITY;
                for (int corner = 0; corner < 8; corner++) {
                    float cx = (corner & 1) == 0 ? x : x + size;
                    float cy = (corner & 2) == 0 ? y : y + size;
                    float cz = (corner & 4) == 0 ? z - size : z;
                    float w = m[3] * cx + m[7] * cy + m[11] * cz + m[15];
                    nearest = Math.max(nearest, (m[2] * cx + m[6] * cy + m[10] * cz + m[14]) / w);
                    float sx = ((m[0] * cx + m[8] * cz) / w * 0.5f + 0.5f) * WIDTH;
                    float sy = ((m[5] * cy + m[9] * cz) / w * 0.5f + 0.5f) * HEIGHT;
                    left = Math.min(left, sx);
                    right = Math.max(right, sx);
                    bottom = Math.min(bottom, sy);
                    top = Math.max(top, sy);
                }
                for (int py = Math.max(0, (int) Math.floor(bottom)); py <= Math.min(HEIGHT - 1, (int) Math.floor(top)); py++) {
                    for (int px = Math.max(0, (int) Math.floor(left)); px <= Math.min(WIDTH - 1, (int) Math.floor(right)); px++) {
                        assertTrue(depth[py * WIDTH + px] > nearest, "a hidden box shows at " + px + ", " + py);
                    }
                }
            }
        }
        assertTrue(hidden > 100, "too few hidden boxes to show anything: " + hidden);
    }
}
