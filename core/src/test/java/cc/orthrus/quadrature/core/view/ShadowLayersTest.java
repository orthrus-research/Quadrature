package cc.orthrus.quadrature.core.view;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Random;

class ShadowLayersTest {

    private static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1;
        return m;
    }

    private static View player(double x, double y, double z) {
        return new View().set(View.Purpose.MAIN, View.Depth.CONVENTIONAL, x, y, z, x, y + 1.62, z, identity(), identity(),
            12, 12, true);
    }

    /** Lights from overhead, from low in the east, and a low sun leaned toward the south. */
    private static final double[][] LIGHTS = {{0, 1, 0}, {0.9389919, 0.3117149, 0.14535503}, {-0.6, 0.3, 0.74}};

    /** Where a position relative to the view's origin lands in the box's clip space. */
    private static double[] clip(View box, double x, double y, double z) {
        float[] p = box.projection();
        float[] m = box.modelView();
        double[] eye = new double[3];
        for (int row = 0; row < 3; row++) {
            eye[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row];
        }
        double[] out = new double[3];
        for (int row = 0; row < 3; row++) {
            out[row] = p[row] * eye[0] + p[4 + row] * eye[1] + p[8 + row] * eye[2] + p[12 + row];
        }
        return out;
    }

    private static boolean inside(double[] clip) {
        return Math.abs(clip[0]) <= 1 && Math.abs(clip[1]) <= 1 && Math.abs(clip[2]) <= 1;
    }

    @Test
    void theNearBoxIs192BlocksAcrossAndDeepAndATexelIsUnderAFiftiethOfABlock() {
        View box = new View();
        for (double[] light : LIGHTS) {
            assertEquals(192.0, ShadowLayers.nearBox(box, player(2563.4, 146, 7624.7), light[0], light[1], light[2]));
            float[] p = box.projection();
            assertEquals(1 / 96f, p[0], 0f);
            assertEquals(1 / 96f, p[5], 0f);
            assertEquals(-1 / 96f, p[10], 0f);
            assertEquals(View.Purpose.DEPTH_ONLY, box.purpose());
        }
        assertEquals(0.046875, ShadowLayers.texel(ShadowLayers.NEAR_SIDE), 0);
        assertEquals(4096, ShadowLayers.TEXELS);
    }

    @Test
    void layersAndTheirMemory() {
        ShadowLayers on = new ShadowLayers(true);
        ShadowLayers off = new ShadowLayers(false);
        assertEquals(2, on.count());
        assertEquals(0, on.nearLayer());
        assertEquals(1, on.wideLayer());
        assertEquals(1, off.count());
        assertEquals(-1, off.nearLayer());
        assertEquals(0, off.wideLayer());
        assertEquals(128L << 20, on.bytes());
        assertEquals(64L << 20, off.bytes());
        assertEquals(64L << 20, ShadowLayers.layerBytes());
    }

    @Test
    void theNearBoxIsCenteredOnAWholeTexelOfTheLightsAxes() {
        Random random = new Random(7);
        double texel = ShadowLayers.texel(ShadowLayers.NEAR_SIDE);
        View box = new View();
        for (double[] light : LIGHTS) {
            for (int i = 0; i < 200; i++) {
                double x = 2000 + random.nextDouble() * 1000;
                double y = 60 + random.nextDouble() * 100;
                double z = 7000 + random.nextDouble() * 1000;
                ShadowLayers.nearBox(box, player(x, y, z), light[0], light[1], light[2]);
                float[] m = box.modelView();
                float[] p = box.projection();
                for (int axis = 0; axis < 3; axis++) {
                    // The box's center along the axis, from the origin, then from the world's origin.
                    double center = axis == 2 ? p[14] * 96.0 : -p[12 + axis] * 96.0;
                    double world = m[axis] * x + m[4 + axis] * y + m[8 + axis] * z + center;
                    assertTrue(Math.abs(center) <= texel / 2 + 1e-6, "within half a texel of the player");
                    // The snap is made in double; the axes are read back in single precision, which at
                    // thousands of blocks from the world's origin leaves a few ten-thousandths of a block.
                    assertEquals(Math.rint(world / texel), world / texel, 1e-2, "a whole texel along axis " + axis);
                }
            }
        }
    }

    @Test
    void theNearBoxHoldsWhatLiesWithin95BlocksOfThePlayerAndNothingBeyond167() {
        Random random = new Random(11);
        View box = new View();
        for (double[] light : LIGHTS) {
            ShadowLayers.nearBox(box, player(2550.3, 143, 7642.9), light[0], light[1], light[2]);
            assertTrue(inside(clip(box, 0, 0, 0)));
            assertTrue(box.boxVisible(2550, 143, 7642, 2551, 144, 7643));
            for (int i = 0; i < 2000; i++) {
                double dx = random.nextGaussian();
                double dy = random.nextGaussian();
                double dz = random.nextGaussian();
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                assertTrue(inside(clip(box, dx / length * 95.9, dy / length * 95.9, dz / length * 95.9)));
                // The cube's half diagonal is 96 times the root of three, 166.3 blocks.
                assertFalse(inside(clip(box, dx / length * 167, dy / length * 167, dz / length * 167)));
            }
            // Along the box's own axes it ends at 96 blocks, give or take the snap.
            float[] m = box.modelView();
            for (int axis = 0; axis < 3; axis++) {
                assertTrue(inside(clip(box, m[axis] * 95.9, m[4 + axis] * 95.9, m[8 + axis] * 95.9)));
                assertFalse(inside(clip(box, m[axis] * 96.1, m[4 + axis] * 96.1, m[8 + axis] * 96.1)));
                assertFalse(inside(clip(box, -m[axis] * 96.1, -m[4 + axis] * 96.1, -m[8 + axis] * 96.1)));
            }
            assertFalse(box.boxVisible(2550 + 200, 143, 7642, 2551 + 200, 144, 7643));
        }
    }

    @Test
    void aStepWithinATexelKeepsTheLayerAndOneAcrossItDrawsItAgain() {
        View box = new View();
        double texel = ShadowLayers.texel(ShadowLayers.NEAR_SIDE);
        ShadowLayers.nearBox(box, player(2563.0, 146, 7624.0), 0, 1, 0);
        float[] m = box.modelView();
        // Overhead light: the first axis runs along x. A place a tenth of a texel on stays in the texel.
        double x = Math.rint(2563.0 / texel) * texel;
        assertFalse(ShadowLayers.newTexel(m, texel, x, 146, 7624, x + texel * 0.1, 146, 7624));
        assertTrue(ShadowLayers.newTexel(m, texel, x, 146, 7624, x + texel, 146, 7624));
        assertTrue(ShadowLayers.newTexel(m, texel, x, 146, 7624, x, 146 + texel, 7624));
        // The wide box's texel is nearly three times as large: the same step keeps it.
        double wide = ShadowLayers.texel(544);
        double w = Math.rint(2563.0 / wide) * wide;
        assertFalse(ShadowLayers.newTexel(m, wide, w, 146, 7624, w + texel, 146, 7624));
    }

    @Test
    void aKeptLayerIsCarriedToTheNewOriginAndPutsEveryPlaceWhereItWas() {
        ShadowLayers layers = new ShadowLayers(true);
        ShadowMaps maps = new ShadowMaps(layers.count(), ShadowLayers.TEXELS, layers.bytes());
        double[] light = LIGHTS[1];
        View first = player(2563.4, 146.0, 7624.7);
        View near = new View();
        View wide = new View();
        maps.beginFrame(first.originX(), first.originY(), first.originZ(), light[0], light[1], light[2]);
        ShadowLayers.nearBox(near, first, light[0], light[1], light[2]);
        View.alongLight(wide, first, light[0], light[1], light[2], 544, ShadowLayers.TEXELS);
        maps.layer(0, near, 192, true);
        maps.layer(1, wide, 544, true);
        float[] wideBefore = maps.viewProjection(1).clone();

        // The player moves: the near layer is drawn again, the wide one kept.
        View second = player(2563.9, 146.0, 7625.1);
        maps.beginFrame(second.originX(), second.originY(), second.originZ(), light[0], light[1], light[2]);
        ShadowLayers.nearBox(near, second, light[0], light[1], light[2]);
        maps.layer(0, near, 192, true);
        maps.keep(1);
        assertArrayEquals(wideBefore, maps.previousViewProjection(1));
        assertEquals(544, maps.boxSide(1));
        double[][] places = {{2570, 150, 7630}, {2500, 120, 7700}, {2563.4, 146, 7624.7}};
        for (double[] place : places) {
            double[] before = apply(wideBefore, place[0] - first.originX(), place[1] - first.originY(), place[2] - first.originZ());
            double[] after = apply(maps.viewProjection(1), place[0] - second.originX(), place[1] - second.originY(),
                place[2] - second.originZ());
            for (int i = 0; i < 3; i++) {
                // A sixteenth of a texel of the wide box, in clip units.
                assertEquals(before[i], after[i], 2.0 / 4096 / 16);
            }
        }
        assertEquals(2563.9, maps.origin()[0], 0);
    }

    private static double[] apply(float[] m, double x, double y, double z) {
        double[] out = new double[3];
        for (int row = 0; row < 3; row++) {
            out[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row];
        }
        return out;
    }
}
