package cc.orthrus.quadrature.core.fade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NearFadeTest {

    @Test
    void theBandIsTheLastBlocksBeforeTheLastChunkWhereNoneIsSet() {
        assertArrayEquals(new float[] {160f, 176f}, NearFade.band(Float.NaN, Float.NaN, 12));
        assertArrayEquals(new float[] {100f, 120f}, NearFade.band(100f, 120f, 12));
        // An end at or before the start is put a block past it, so nothing divides by nothing.
        assertArrayEquals(new float[] {100f, 101f}, NearFade.band(100f, 90f, 12));
    }

    @Test
    void theOriginIsTheViewsWhereTheMatrixMovesNothing() {
        float[] turnedOnly = rotationAboutY(0.7f, 0f, 0f, 0f);
        assertArrayEquals(new double[] {10.5, 64.0, -3.25}, NearFade.origin(10.5, 64.0, -3.25, turnedOnly), 1e-9);
    }

    @Test
    void aViewFromBehindMeasuresFromTheCamera() {
        // The camera four blocks behind the eye along its own line of sight: the matrix moves the world
        // four blocks down the eye's z after turning it.
        float angle = 0.9f;
        float[] matrix = rotationAboutY(angle, 0f, 0f, -4f);
        double[] origin = NearFade.origin(100, 70, 200, matrix);
        // Sent through the matrix, the origin found is the eye: nothing.
        double x = origin[0] - 100;
        double y = origin[1] - 70;
        double z = origin[2] - 200;
        for (int row = 0; row < 3; row++) {
            double at = matrix[row] * x + matrix[4 + row] * y + matrix[8 + row] * z + matrix[12 + row];
            assertEquals(0.0, at, 1e-5);
        }
        assertEquals(4.0, Math.sqrt(x * x + y * y + z * z), 1e-5);
        assertEquals(0.0, y, 1e-6);
    }

    @Test
    void theDistanceIsSquareAndTheShareRunsAcrossTheBand() {
        double[] origin = {8, 64, 8};
        assertEquals(30.0, NearFade.distance(origin, 38, 20), 0);
        assertEquals(30.0, NearFade.distance(origin, 20, -22), 0);
        float[] band = NearFade.band(Float.NaN, Float.NaN, 12);
        assertEquals(0f, NearFade.shows(100, band), 0f);
        assertEquals(0f, NearFade.shows(160, band), 0f);
        assertEquals(0.5f, NearFade.shows(168, band), 0f);
        assertEquals(1f, NearFade.shows(176, band), 0f);
        assertEquals(1f, NearFade.shows(400, band), 0f);
    }

    @Test
    void acrossTheBandExactlyOneSideKeepsEachPixel() {
        float[] band = NearFade.band(Float.NaN, Float.NaN, 12);
        int near = 0;
        for (int distance = 150; distance <= 190; distance++) {
            float shows = NearFade.shows(distance, band);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    boolean nearKeeps = FadePattern.keeps(x, y, shows, true, 0);
                    boolean beyondKeeps = FadePattern.keeps(x, y, shows, false, 0);
                    assertTrue(nearKeeps != beyondKeeps);
                    near += nearKeeps ? 1 : 0;
                    if (distance <= 160) {
                        assertTrue(nearKeeps);
                    }
                    if (distance >= 176) {
                        assertFalse(nearKeeps);
                    }
                }
            }
        }
        assertTrue(near > 0);
    }

    /** A rotation about the upward axis and then a move, column-major, as a model-view matrix is. */
    private static float[] rotationAboutY(float angle, float tx, float ty, float tz) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        return new float[] {
            c, 0, -s, 0,
            0, 1, 0, 0,
            s, 0, c, 0,
            tx, ty, tz, 1,
        };
    }
}
