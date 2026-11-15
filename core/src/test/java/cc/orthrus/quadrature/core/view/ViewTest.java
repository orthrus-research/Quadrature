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
    void aViewAlongTheLightHoldsWhatTheMainViewSees() {
        View main = lookingNorth(View.Depth.CONVENTIONAL);
        for (double[] light : new double[][] {{0, 1, 0}, {-0.6, 0.8, 0}, {0.9, 0.1, 0.3}, {0, 0.3, -1}}) {
            View along = View.alongLight(new View(), main, light[0], light[1], light[2]);
            assertEquals(View.Purpose.DEPTH_ONLY, along.purpose());
            assertFalse(along.occlusion());
            // The camera and the corners of the main view at its reach of 192 blocks.
            assertTrue(along.boxVisible(1000, 63, -3001, 1001, 65, -3000));
            for (int corner = 0; corner < 4; corner++) {
                double x = 1000.5 + ((corner & 1) == 0 ? -192 : 192);
                double y = 64 + ((corner & 2) == 0 ? -192 : 192);
                assertTrue(along.boxVisible(x - 0.5, y - 0.5, -3193, x + 0.5, y + 0.5, -3192));
            }
            // Something between the light and the middle of the volume casts into it.
            double scale = 150 / Math.sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2]);
            double x = 1000.5 + light[0] * scale;
            double y = 64 + light[1] * scale;
            double z = -3096 + light[2] * scale;
            assertTrue(along.boxVisible(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1));
            // Something far behind the camera is outside it.
            assertFalse(along.boxVisible(1000, 60, -1000, 1001, 61, -999));
        }
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
}
