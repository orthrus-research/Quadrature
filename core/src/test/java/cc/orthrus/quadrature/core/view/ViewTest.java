package cc.orthrus.quadrature.core.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewTest {

    private static float[] identity() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1;
        return m;
    }

    /** A square perspective projection with a right angle of view, column-major. */
    private static float[] perspective(float near, float far) {
        float[] m = new float[16];
        m[0] = 1;
        m[5] = 1;
        m[10] = -(far + near) / (far - near);
        m[11] = -1;
        m[14] = -2 * far * near / (far - near);
        return m;
    }

    /** The same volume with clip depth from 1 at the near plane to 0 at the far plane. */
    private static float[] reversed(float near, float far) {
        float[] m = new float[16];
        m[0] = 1;
        m[5] = 1;
        m[10] = near / (far - near);
        m[11] = -1;
        m[14] = far * near / (far - near);
        return m;
    }

    private static View lookingNorth(View.Depth depth) {
        float[] projection = depth == View.Depth.REVERSED ? reversed(0.05f, 200) : perspective(0.05f, 200);
        return new View().set(View.Purpose.MAIN, depth, 1000.5, 64, -3000.5, 1000.5, 65.62, -3000.5, identity(),
            projection, 12, 12, true);
    }

    @Test
    void aBoxAheadIsSeenAndOneBehindOrBesideIsNot() {
        for (View.Depth depth : View.Depth.values()) {
            View view = lookingNorth(depth);
            // Looking down negative z from the origin.
            assertTrue(view.boxVisible(995, 60, -3050, 1005, 70, -3040), depth.toString());
            assertFalse(view.boxVisible(995, 60, -2990, 1005, 70, -2980), depth.toString());
            assertFalse(view.boxVisible(1200, 60, -3050, 1210, 70, -3040), depth.toString());
            assertFalse(view.boxVisible(995, 60, -3300, 1005, 70, -3290), depth.toString());
            assertTrue(view.boxVisible(900, 0, -3100, 1100, 128, -2900), depth.toString());
        }
    }

    @Test
    void cubesAndReachAreCountedFromTheOriginsCube() {
        View view = lookingNorth(View.Depth.CONVENTIONAL);
        assertEquals(62, view.originCubeX());
        assertEquals(4, view.originCubeY());
        assertEquals(-188, view.originCubeZ());
        assertEquals(4, view.eyeCubeY());
        assertTrue(view.cubeWithinReach(74, 16, -200));
        assertFalse(view.cubeWithinReach(75, 4, -188));
        assertFalse(view.cubeWithinReach(62, -9, -188));
        assertTrue(view.cubeVisible(62, 4, -190));
        assertFalse(view.cubeVisible(62, 4, -186));
    }

    @Test
    void aDegenerateProjectionExcludesNothing() {
        View view = new View().set(View.Purpose.MAIN, View.Depth.CONVENTIONAL, 0, 0, 0, 0, 0, 0, identity(),
            new float[16], 2, 2, true);
        assertTrue(view.boxVisible(-1e6, -1e6, -1e6, -1e6 + 1, -1e6 + 1, -1e6 + 1));
    }

    @Test
    void sameVolumeTellsAnUnchangedViewFromAMovedOne() {
        View a = lookingNorth(View.Depth.CONVENTIONAL);
        View b = new View().copy(a);
        assertTrue(a.sameVolume(b));
        b.set(View.Purpose.MAIN, View.Depth.CONVENTIONAL, 1000.5, 64, -3000.25, 1000.5, 65.62, -3000.25,
            identity(), perspective(0.05f, 200), 12, 12, true);
        assertFalse(a.sameVolume(b));
    }

    @Test
    void aViewThroughALightBoxIsAsWideAsTheBoxAndPutsAPlaceOnTheSameTexelFromAnyOrigin() {
        double side = 544;
        int texels = 4096;
        double[] light = {-0.6, 0.8, 0};
        View main = lookingNorth(View.Depth.CONVENTIONAL);
        LightBox box = LightBox.around(light[0], light[1], light[2], main.originX(), main.originY(), main.originZ(), side,
            side, texels);
        View a = View.throughLight(new View(), main, box);
        // The box is as wide as asked whatever the main view sees.
        assertEquals(2 / side, a.projection()[0], 1e-9);
        assertEquals(2 / side, a.projection()[5], 1e-9);
        assertEquals(View.Purpose.DEPTH_ONLY, a.purpose());
        assertFalse(a.occlusion());
        // The player's own position is inside it, and a point more than half the side away is not.
        assertTrue(a.boxVisible(999.5, 63, -3001.5, 1001.5, 65, -2999.5));
        assertFalse(a.boxVisible(1000, 64, -2600, 1001, 65, -2599));
        // It has no reach of its own: a cube beyond the main view's 12 cubes is within it, and the box alone bounds it.
        assertFalse(main.cubeWithinReach(62 + 13, 4, -188));
        assertTrue(a.cubeWithinReach(62 + 13, 4, -188));
        assertTrue(a.cubeWithinReach(-2_000_000, 20_000, 2_000_000));

        // The same box seen from another origin: a world point lands on the same place in the map.
        View moved = new View().set(View.Purpose.MAIN, View.Depth.CONVENTIONAL, 1003.87, 64.2, -3002.41, 1003.87,
            65.8, -3002.41, identity(), perspective(0.05f, 512f), 12, 12, true);
        View b = View.throughLight(new View(), moved, box);
        double[] world = {1010.25, 70.5, -3020.75};
        double[] inA = clip(a, world[0] - 1000.5, world[1] - 64, world[2] + 3000.5);
        double[] inB = clip(b, world[0] - 1003.87, world[1] - 64.2, world[2] + 3002.41);
        for (int axis = 0; axis < 3; axis++) {
            assertEquals(inA[axis], inB[axis], 2.0 / texels / 64);
        }
    }
    private static double[] clip(View view, double x, double y, double z) {
        float[] m = view.modelView();
        float[] p = view.projection();
        double[] eye = new double[4];
        for (int row = 0; row < 4; row++) {
            eye[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row];
        }
        double[] out = new double[4];
        for (int row = 0; row < 4; row++) {
            out[row] = p[row] * eye[0] + p[4 + row] * eye[1] + p[8 + row] * eye[2] + p[12 + row] * eye[3];
        }
        return out;
    }
}