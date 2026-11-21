package cc.orthrus.quadrature.core.bloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class BloomChainTest {

    @Test
    void theTapsWeighAsTheGroupsPublishedGive() {
        double[] weights = BloomChain.tapWeights();
        double sum = 0;
        for (double weight : weights) {
            sum += weight;
        }
        assertEquals(1.0, sum, 1e-12);
        assertEquals(1 / 8.0, weights[0], 1e-12);
        for (int tap = 1; tap <= 4; tap++) {
            assertEquals(1 / 8.0, weights[tap], 1e-12);
        }
        for (int tap = 5; tap <= 8; tap++) {
            assertEquals(1 / 16.0, weights[tap], 1e-12);
        }
        for (int tap = 9; tap <= 12; tap++) {
            assertEquals(1 / 32.0, weights[tap], 1e-12);
        }
        double tent = 0;
        for (double weight : BloomChain.TENT) {
            tent += weight;
        }
        assertEquals(1.0, tent, 1e-12);
    }

    @Test
    void eachQuadrantsGroupLiesInItsQuadrant() {
        int[][] quadrants = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
        for (int box = 1; box < BloomChain.BOXES.length; box++) {
            for (int tap : BloomChain.BOXES[box]) {
                int[] offset = BloomChain.TAPS[tap];
                assertTrue(offset[0] * quadrants[box - 1][0] >= 0 && offset[1] * quadrants[box - 1][1] >= 0,
                    "tap " + tap + " of group " + box);
            }
        }
    }

    @Test
    void twoWeightsGiveGtceuReloadedsRule() {
        Random random = new Random(27);
        for (int round = 0; round < 10_000; round++) {
            float low = random.nextFloat();
            float high = low + random.nextFloat() * (1 - low);
            float base = random.nextFloat() * 0.5f;
            float strength = random.nextFloat() * 2f;
            double[] background = {random.nextDouble(), random.nextDouble(), random.nextDouble()};
            double[] glow = {random.nextDouble(), random.nextDouble(), random.nextDouble()};
            if (BloomChain.luma(glow[0], glow[1], glow[2]) < 1e-3) {
                continue;
            }
            double g0 = BloomChain.overBlack(strength, base, high);
            double g1 = BloomChain.overWhite(strength, base, low);
            if (g0 * BloomChain.luma(glow[0], glow[1], glow[2]) < 10 * BloomChain.LEAST_LUMA) {
                // Under the floor the darkening is left out on purpose: a glow that faint does not show in 8 bits.
                continue;
            }
            double[] weighted = {glow[0] * g0, glow[1] * g0, glow[2] * g0};
            double darkening = BloomChain.luma(glow[0], glow[1], glow[2]) * (g0 - g1);
            double[] got = BloomChain.composite(background, weighted, darkening);
            double[] wanted = BloomChain.reference(background, glow, strength, base, low, high);
            // The weights are single precision, as on the GPU; the rule itself is reproduced exactly.
            for (int c = 0; c < 3; c++) {
                assertEquals(wanted[c], got[c], 1e-6);
            }
        }
    }

    @Test
    void gtceuReloadedsThreeKindsHaveTheirWeights() {
        // Emissive textures, the fusion reactor and the heat effect, from its generated configuration documentation.
        assertEquals(0.9f, BloomChain.overBlack(1.5f, 0.1f, 0.5f), 1e-6);
        assertEquals(0.45f, BloomChain.overWhite(1.5f, 0.1f, 0.2f), 1e-6);
        assertEquals(1.95f, BloomChain.overBlack(1.5f, 0f, 1.3f), 1e-6);
        assertEquals(0.45f, BloomChain.overWhite(1.5f, 0f, 0.3f), 1e-6);
        assertEquals(1.54f, BloomChain.overBlack(1.1f, 0f, 1.4f), 1e-6);
        assertEquals(0.66f, BloomChain.overWhite(1.1f, 0f, 0.6f), 1e-6);
        assertEquals(BloomChain.GREATEST_WEIGHT, BloomChain.overBlack(10f, 1f, 1f), 0);
    }

    @Test
    void levelsAreTheOutputHalvedAndRoundedUp() {
        int[] widths = {960, 480, 240, 120, 60, 30};
        int[] heights = {540, 270, 135, 68, 34, 17};
        for (int level = 0; level < widths.length; level++) {
            assertEquals(widths[level], BloomChain.levelSide(1920, level));
            assertEquals(heights[level], BloomChain.levelSide(1080, level));
        }
        assertEquals(1, BloomChain.levelSide(1, 5));
        assertEquals(2, BloomChain.levels(1));
        assertEquals(6, BloomChain.levels(9));
        assertEquals(0.5f, BloomChain.radius(0.1f));
        assertEquals(1.5f, BloomChain.radius(4f));
    }

    @Test
    void aSourceOfOneColorGlowsAsGtceuReloadedsRuleSays() {
        float strength = 1.5f;
        float base = 0.1f;
        float low = 0.2f;
        float high = 0.5f;
        double[] color = {0.8, 0.4, 0.1};
        Chain chain = new Chain(23, 17, 23, 17, 3, 1f, false);
        chain.fill(color, BloomChain.overBlack(strength, base, high), BloomChain.overWhite(strength, base, low));
        double[] background = {0.3, 0.5, 0.2};
        for (int y = 0; y < 17; y++) {
            for (int x = 0; x < 23; x++) {
                double[] got = chain.composite(background, x, y);
                double[] wanted = BloomChain.reference(background, color, strength, base, low, high);
                for (int c = 0; c < 3; c++) {
                    assertEquals(wanted[c], got[c], 1e-6, "at " + x + ", " + y);
                }
            }
        }
    }

    @Test
    void aSinglePointGlowsTheSameEveryWayRound() {
        Chain chain = new Chain(33, 33, 33, 33, 4, 1f, false);
        chain.point(16, 16, new double[] {1, 1, 1}, 0.9, 0.45);
        double[] black = {0, 0, 0};
        for (int y = 0; y < 33; y++) {
            for (int x = 0; x < 33; x++) {
                double here = chain.composite(black, x, y)[0];
                assertEquals(here, chain.composite(black, 32 - x, y)[0], 1e-12);
                assertEquals(here, chain.composite(black, x, 32 - y)[0], 1e-12);
                assertEquals(here, chain.composite(black, y, x)[0], 1e-12);
            }
        }
        assertTrue(chain.composite(black, 16, 16)[0] > chain.composite(black, 16, 20)[0]);
        assertTrue(chain.composite(black, 16, 20)[0] > chain.composite(black, 16, 30)[0]);
    }

    @Test
    void theBrightnessWeightingHoldsALoneBrightTexelBack() {
        Chain plain = new Chain(32, 32, 32, 32, 3, 1f, false);
        Chain weighed = new Chain(32, 32, 32, 32, 3, 1f, true);
        double[] bright = {8, 8, 8};
        plain.point(16, 16, bright, 1, 1);
        weighed.point(16, 16, bright, 1, 1);
        double[] black = {0, 0, 0};
        assertTrue(weighed.composite(black, 16, 16)[0] < plain.composite(black, 16, 16)[0]);
    }

    /**
     * The chain as the stage runs it, on the CPU: the first halving from the picture's color times the kind's weight,
     * formed a texel at a time and read between texels as a bilinear read would, the halvings and doublings by bilinear
     * reads clamped to the edge, and the composite.
     */
    private static final class Chain {
        private final int drawnWidth;
        private final int drawnHeight;
        private final int outputWidth;
        private final int outputHeight;
        private final int levels;
        private final float radius;
        private final boolean brightnessWeighted;
        private final double[][] color;
        private final double[][] weights;
        private double[][][] top;
        private int topWidth;
        private int topHeight;

        Chain(int drawnWidth, int drawnHeight, int outputWidth, int outputHeight, int levels, float radius,
            boolean brightnessWeighted) {
            this.drawnWidth = drawnWidth;
            this.drawnHeight = drawnHeight;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.levels = levels;
            this.radius = radius;
            this.brightnessWeighted = brightnessWeighted;
            color = new double[drawnWidth * drawnHeight][3];
            weights = new double[drawnWidth * drawnHeight][2];
        }

        void fill(double[] rgb, double overBlack, double overWhite) {
            for (int i = 0; i < color.length; i++) {
                color[i] = rgb.clone();
                weights[i] = new double[] {overBlack / BloomChain.GREATEST_WEIGHT, overWhite / BloomChain.GREATEST_WEIGHT};
            }
            run();
        }

        void point(int x, int y, double[] rgb, double overBlack, double overWhite) {
            color[y * drawnWidth + x] = rgb.clone();
            weights[y * drawnWidth + x] = new double[] {overBlack / BloomChain.GREATEST_WEIGHT,
                overWhite / BloomChain.GREATEST_WEIGHT};
            run();
        }

        private void run() {
            int width = BloomChain.levelSide(outputWidth, 0);
            int height = BloomChain.levelSide(outputHeight, 0);
            double[][][] level = first(width, height);
            double[][][][] down = new double[levels][][][];
            int[][] sizes = new int[levels][];
            down[0] = level;
            sizes[0] = new int[] {width, height};
            for (int i = 1; i < levels; i++) {
                int w = BloomChain.levelSide(outputWidth, i);
                int h = BloomChain.levelSide(outputHeight, i);
                down[i] = halve(down[i - 1], sizes[i - 1][0], sizes[i - 1][1], w, h);
                sizes[i] = new int[] {w, h};
            }
            double[][][] up = down[levels - 1];
            for (int i = levels - 2; i >= 0; i--) {
                up = doubled(down[i], sizes[i][0], sizes[i][1], up, sizes[i + 1][0], sizes[i + 1][1]);
            }
            top = up;
            topWidth = sizes[0][0];
            topHeight = sizes[0][1];
        }

        private double[][][] first(int width, int height) {
            double[][][] out = new double[height][width][];
            double[][] products = new double[color.length][];
            for (int i = 0; i < color.length; i++) {
                double a = weights[i][0] * BloomChain.GREATEST_WEIGHT;
                products[i] = new double[] {color[i][0] * a, color[i][1] * a, color[i][2] * a};
            }
            double hx = 0.5 * drawnWidth / width;
            double hy = 0.5 * drawnHeight / height;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double cx = (x + 0.5) * drawnWidth / width;
                    double cy = (y + 0.5) * drawnHeight / height;
                    double[][] taps = new double[13][];
                    double sumOverBlack = 0;
                    double sumOverWhite = 0;
                    double[] tapWeights = BloomChain.tapWeights();
                    for (int t = 0; t < 13; t++) {
                        double px = cx + BloomChain.TAPS[t][0] * hx;
                        double py = cy + BloomChain.TAPS[t][1] * hy;
                        taps[t] = bilinear(products, drawnWidth, drawnHeight, px, py, 3);
                        double[] w = bilinear(weights, drawnWidth, drawnHeight, px, py, 2);
                        sumOverBlack += tapWeights[t] * w[0];
                        sumOverWhite += tapWeights[t] * w[1];
                    }
                    double[] sum = new double[3];
                    double total = 0;
                    for (int box = 0; box < 5; box++) {
                        double[] mean = new double[3];
                        for (int tap : BloomChain.BOXES[box]) {
                            for (int c = 0; c < 3; c++) {
                                mean[c] += taps[tap][c] / 4;
                            }
                        }
                        double weight = BloomChain.BOX_WEIGHTS[box]
                            / (brightnessWeighted ? 1 + BloomChain.luma(mean[0], mean[1], mean[2]) : 1);
                        for (int c = 0; c < 3; c++) {
                            sum[c] += mean[c] * weight;
                        }
                        total += weight;
                    }
                    for (int c = 0; c < 3; c++) {
                        sum[c] /= total;
                    }
                    double ratio = sumOverBlack > 0 ? 1 - sumOverWhite / sumOverBlack : 0;
                    out[y][x] = new double[] {sum[0], sum[1], sum[2], BloomChain.luma(sum[0], sum[1], sum[2]) * ratio};
                }
            }
            return out;
        }

        private static double[][][] halve(double[][][] from, int fromWidth, int fromHeight, int width, int height) {
            double[][][] out = new double[height][width][];
            double[] tapWeights = BloomChain.tapWeights();
            double hx = 0.5 * fromWidth / width;
            double hy = 0.5 * fromHeight / height;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double cx = (x + 0.5) * fromWidth / width;
                    double cy = (y + 0.5) * fromHeight / height;
                    double[] sum = new double[4];
                    for (int t = 0; t < 13; t++) {
                        double[] tap = bilinear(from, fromWidth, fromHeight, cx + BloomChain.TAPS[t][0] * hx,
                            cy + BloomChain.TAPS[t][1] * hy);
                        for (int c = 0; c < 4; c++) {
                            sum[c] += tap[c] * tapWeights[t];
                        }
                    }
                    out[y][x] = sum;
                }
            }
            return out;
        }

        private double[][][] doubled(double[][][] current, int width, int height, double[][][] lower, int lowerWidth,
            int lowerHeight) {
            double[][][] out = new double[height][width][];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // The tent around this texel's place in the lower level, its reads r texels of that level apart.
                    double lx = (x + 0.5) * lowerWidth / width;
                    double ly = (y + 0.5) * lowerHeight / height;
                    double[] sum = current[y][x].clone();
                    for (int n = -1; n <= 1; n++) {
                        for (int m = -1; m <= 1; m++) {
                            double[] tap = bilinear(lower, lowerWidth, lowerHeight, lx + m * radius, ly + n * radius);
                            double weight = BloomChain.TENT[(n + 1) * 3 + (m + 1)];
                            for (int c = 0; c < 4; c++) {
                                sum[c] += tap[c] * weight;
                            }
                        }
                    }
                    out[y][x] = sum;
                }
            }
            return out;
        }

        double[] composite(double[] background, int x, int y) {
            double[] b = bilinear(top, topWidth, topHeight, (x + 0.5) * topWidth / outputWidth,
                (y + 0.5) * topHeight / outputHeight);
            double scale = 1.0 / levels;
            return BloomChain.composite(background, new double[] {b[0] * scale, b[1] * scale, b[2] * scale}, b[3] * scale);
        }

        /** A bilinear read at a place in texels (centers at half texels), clamped to the edge, of a level. */
        private static double[] bilinear(double[][][] level, int width, int height, double x, double y) {
            double fx = x - 0.5;
            double fy = y - 0.5;
            int x0 = (int) Math.floor(fx);
            int y0 = (int) Math.floor(fy);
            double ax = fx - x0;
            double ay = fy - y0;
            double[] out = new double[4];
            for (int j = 0; j <= 1; j++) {
                for (int i = 0; i <= 1; i++) {
                    double weight = (i == 0 ? 1 - ax : ax) * (j == 0 ? 1 - ay : ay);
                    double[] texel = level[clamp(y0 + j, height)][clamp(x0 + i, width)];
                    for (int c = 0; c < 4; c++) {
                        out[c] += texel[c] * weight;
                    }
                }
            }
            return out;
        }

        /** The same of a picture held texel by texel, row after row, for its first {@code channels} channels. */
        private static double[] bilinear(double[][] picture, int width, int height, double x, double y, int channels) {
            double fx = x - 0.5;
            double fy = y - 0.5;
            int x0 = (int) Math.floor(fx);
            int y0 = (int) Math.floor(fy);
            double ax = fx - x0;
            double ay = fy - y0;
            double[] out = new double[channels];
            for (int j = 0; j <= 1; j++) {
                for (int i = 0; i <= 1; i++) {
                    double weight = (i == 0 ? 1 - ax : ax) * (j == 0 ? 1 - ay : ay);
                    double[] texel = picture[clamp(y0 + j, height) * width + clamp(x0 + i, width)];
                    for (int c = 0; c < channels; c++) {
                        out[c] += texel[c] * weight;
                    }
                }
            }
            return out;
        }

        private static int clamp(int index, int size) {
            return Math.max(0, Math.min(size - 1, index));
        }
    }
}
