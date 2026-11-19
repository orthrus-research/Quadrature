package cc.orthrus.quadrature.core.view;

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

    /** The wide box's side at a reach of twelve chunks: the near box is as deep along the light. */
    private static final double WIDE = 544;

    private static LightBox nearBox(View player, double[] light) {
        return LightBox.around(light[0], light[1], light[2], player.originX(), player.originY(), player.originZ(),
            ShadowLayers.NEAR_SIDE, WIDE, ShadowLayers.TEXELS);
    }

    private static View through(View player, LightBox box) {
        return View.throughLight(new View(), player, box);
    }

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
    void theNearBoxIs192BlocksAcrossAsDeepAsTheWideBoxAndATexelIsUnderAFiftiethOfABlock() {
        for (double[] light : LIGHTS) {
            View box = through(player(2563.4, 146, 7624.7), nearBox(player(2563.4, 146, 7624.7), light));
            float[] p = box.projection();
            assertEquals(1 / 96f, p[0], 0f);
            assertEquals(1 / 96f, p[5], 0f);
            assertEquals(-1 / 272f, p[10], 0f);
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
        assertEquals(256L << 20, on.bytesWithScratch());
        assertEquals(64L << 20, off.bytes());
        assertEquals(64L << 20, ShadowLayers.layerBytes());
    }

    @Test
    void theWideBoxIsTheDiagonalOfTheSquareOfCubesHeld() {
        assertEquals(544, ShadowLayers.wideSide(12, 12));
        assertEquals(544, ShadowLayers.wideSide(12, 8));
        assertEquals(725, ShadowLayers.wideSide(16, 4));
    }

    @Test
    void theNearBoxIsCenteredOnAWholeTexelOfTheLightsAxes() {
        Random random = new Random(7);
        double texel = ShadowLayers.texel(ShadowLayers.NEAR_SIDE);
        for (double[] light : LIGHTS) {
            for (int i = 0; i < 200; i++) {
                double x = 2000 + random.nextDouble() * 1000;
                double y = 60 + random.nextDouble() * 100;
                double z = 7000 + random.nextDouble() * 1000;
                View box = through(player(x, y, z), nearBox(player(x, y, z), light));
                float[] m = box.modelView();
                float[] p = box.projection();
                for (int axis = 0; axis < 3; axis++) {
                    // The box's center along the axis, from the origin, then from the world's origin.
                    double center = axis == 2 ? p[14] * 272.0 : -p[12 + axis] * 96.0;
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
    void theNearBoxHoldsWhatLiesWithin95BlocksOfThePlayerAndAcrossTheLightNothingBeyond96() {
        Random random = new Random(11);
        for (double[] light : LIGHTS) {
            View player = player(2550.3, 143, 7642.9);
            View box = through(player, nearBox(player, light));
            assertTrue(inside(clip(box, 0, 0, 0)));
            assertTrue(box.boxVisible(2550, 143, 7642, 2551, 144, 7643));
            for (int i = 0; i < 2000; i++) {
                double dx = random.nextGaussian();
                double dy = random.nextGaussian();
                double dz = random.nextGaussian();
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                assertTrue(inside(clip(box, dx / length * 95.9, dy / length * 95.9, dz / length * 95.9)));
            }
            // Across the light it ends at 96 blocks and along it at 272, give or take the snap.
            float[] m = box.modelView();
            for (int axis = 0; axis < 3; axis++) {
                double half = axis == 2 ? 272 : 96;
                assertTrue(inside(clip(box, m[axis] * (half - 0.1), m[4 + axis] * (half - 0.1), m[8 + axis] * (half - 0.1))));
                assertFalse(inside(clip(box, m[axis] * (half + 0.1), m[4 + axis] * (half + 0.1), m[8 + axis] * (half + 0.1))));
                assertFalse(inside(clip(box, -m[axis] * (half + 0.1), -m[4 + axis] * (half + 0.1), -m[8 + axis] * (half + 0.1))));
            }
            // A place 200 blocks off across the light is outside, one 200 blocks toward it inside.
            double ax = m[0];
            double ay = m[4];
            double az = m[8];
            assertFalse(box.boxVisible(2550 + 200 * ax, 143 + 200 * ay, 7642 + 200 * az, 2551 + 200 * ax, 144 + 200 * ay,
                7643 + 200 * az));
            assertTrue(box.boxVisible(2550 + 200 * light[0], 143 + 200 * light[1], 7642 + 200 * light[2],
                2551 + 200 * light[0], 144 + 200 * light[1], 7643 + 200 * light[2]));
        }
    }

    @Test
    void aBoxKeptWhileThePlayerMovesPutsEveryPlaceWhereItWas() {
        // The box is made once; each frame's view through it is made for that frame's origin.
        double[] light = LIGHTS[1];
        View first = player(2563.4, 146.0, 7624.7);
        LightBox wide = LightBox.around(light[0], light[1], light[2], first.originX(), first.originY(), first.originZ(),
            WIDE, WIDE, ShadowLayers.TEXELS);
        ShadowMaps maps = new ShadowMaps(1, ShadowLayers.TEXELS, ShadowLayers.layerBytes());
        maps.beginFrame(first.originX(), first.originY(), first.originZ(), light[0], light[1], light[2]);
        maps.layer(0, through(first, wide), wide, true, true);
        float[] before = maps.viewProjection(0).clone();
        double[][] places = {{2570, 150, 7630}, {2500, 120, 7700}, {2563.4, 146, 7624.7}, {2810, 60, 7400}};
        View last = first;
        for (int frame = 1; frame <= 500; frame++) {
            // Five hundred frames of walking: a matrix carried from frame to frame would gather error.
            View now = player(2563.4 + frame * 0.037, 146.0 + frame * 0.003, 7624.7 - frame * 0.021);
            maps.beginFrame(now.originX(), now.originY(), now.originZ(), light[0], light[1], light[2]);
            maps.layer(0, through(now, wide), wide, false, false);
            last = now;
        }
        for (double[] place : places) {
            double[] was = apply(before, place[0] - first.originX(), place[1] - first.originY(), place[2] - first.originZ());
            double[] is = apply(maps.viewProjection(0), place[0] - last.originX(), place[1] - last.originY(),
                place[2] - last.originZ());
            for (int i = 0; i < 3; i++) {
                // A sixty-fourth of a texel of the wide box, in clip units.
                assertEquals(was[i], is[i], 2.0 / 4096 / 64);
            }
        }
        assertEquals(last.originX(), maps.origin()[0], 0);
        assertEquals(1, maps.generation());
        assertEquals(1, maps.contentGeneration());
    }

    @Test
    void aTreeNinetyBlocksTowardAnEighteenDegreeSunIsInTheNearBoxAndItsShadowFallsInsideIt() {
        // A sun 18 degrees up in the east, as on the tour's low-sun views.
        double elevation = Math.toRadians(18);
        double[] light = {Math.cos(elevation), Math.sin(elevation), 0};
        View player = player(2563.5, 146, 7624.5);
        View box = through(player, nearBox(player, light));
        // The tree stands 90 blocks east on the player's ground, 30 blocks tall, a trunk and a crown.
        for (double height = 0; height <= 30; height += 1) {
            assertTrue(inside(clip(box, 90, height, 0)), "tree at " + height + " blocks up");
        }
        // Its crown's shadow falls 30 / tan(18 deg) = 92 blocks back toward the player: on near ground.
        double reach = 30 / Math.tan(elevation);
        assertTrue(reach > 90 && reach < 93);
        assertTrue(inside(clip(box, 90 - reach, 0, 0)));
        // In the wide box's depth, the crown stands nearer the light than the ground it shadows.
        assertTrue(clip(box, 90, 30, 0)[2] < clip(box, 90 - reach, 0, 0)[2]);
        assertTrue(box.boxVisible(2563 + 90, 146, 7624, 2564 + 90, 176, 7625));
        // A 40-block tree 120 blocks out shadows the player's own ground too, from 126.5 blocks along the
        // light: a box 192 deep leaves its crown out, the near box holds it and the ground it shadows.
        View shallow = through(player, LightBox.around(light[0], light[1], light[2], 2563.5, 146, 7624.5, 192, 192,
            ShadowLayers.TEXELS));
        assertTrue(inside(clip(shallow, 90, 30, 0)));
        assertFalse(inside(clip(shallow, 120, 40, 0)));
        assertTrue(inside(clip(box, 120, 40, 0)));
        double tallReach = 40 / Math.tan(elevation);
        assertEquals(0, 120 - tallReach, 5);
        assertTrue(inside(clip(box, 120 - tallReach, 0, 0)));
        assertTrue(box.boxVisible(2563 + 120, 146, 7624, 2564 + 120, 186, 7625));
        assertFalse(shallow.boxVisible(2563 + 120, 180, 7624, 2564 + 120, 186, 7625));
    }

    @Test
    void theNearLayersDepthSpanIsTheWideBoxsAndItsTexelIsItsOwn() {
        ShadowLayers layers = new ShadowLayers(true);
        ShadowMaps maps = new ShadowMaps(layers.count(), ShadowLayers.TEXELS, layers.bytes());
        double[] light = LIGHTS[1];
        View first = player(2563.4, 146.0, 7624.7);
        LightBox near = nearBox(first, light);
        LightBox wide = LightBox.around(light[0], light[1], light[2], first.originX(), first.originY(), first.originZ(),
            WIDE, WIDE, ShadowLayers.TEXELS);
        maps.beginFrame(first.originX(), first.originY(), first.originZ(), light[0], light[1], light[2]);
        maps.layer(layers.nearLayer(), through(first, near), near, true, true);
        maps.layer(layers.wideLayer(), through(first, wide), wide, true, true);
        assertEquals(192, maps.boxSide(0));
        assertEquals(544, maps.boxDepth(0));
        assertEquals(544, maps.boxSide(1));
        assertEquals(544, maps.boxDepth(1));
        // What the frame block is given: blocks a texel in x, the depth's span in y.
        assertEquals(0.046875, maps.boxSide(0) / maps.side(), 0);
        // Clip depth from -1 to 1 spans the depth: a step of one block along the light is 2 / 544 of it.
        View view = through(first, near);
        double[] a = clip(view, 0, 0, 0);
        double[] b = clip(view, light[0], light[1], light[2]);
        double length = Math.sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2]);
        assertEquals(-2.0 / 544, (b[2] - a[2]) / length, 1e-6);
        // Each layer says the light it was drawn along.
        assertEquals(light[0] / length, maps.drawnTowardLight(0)[0], 1e-6);
        assertEquals(light[1] / length, maps.drawnTowardLight(1)[1], 1e-6);
    }

    private static double[] apply(float[] m, double x, double y, double z) {
        double[] out = new double[3];
        for (int row = 0; row < 3; row++) {
            out[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row];
        }
        return out;
    }
}
