package cc.orthrus.quadrature.core.view;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SunPathTest {

    private static final double NOON = 0;
    private static final double SUNSET = 0.25;
    private static final double MIDNIGHT = 0.5;
    private static final double SUNRISE = 0.75;

    @Test
    void aTiltOfTwentyFiveDegreesLeansTheNoonSunSouth() {
        double[] at = new double[3];
        SunPath.towardSun(SUNRISE, 25, at);
        assertArrayEquals(new double[] {1, 0, 0}, at, 1e-12);
        SunPath.towardSun(NOON, 25, at);
        assertArrayEquals(new double[] {0, 0.9063, 0.4226}, at, 5e-5);
        SunPath.towardSun(SUNSET, 25, at);
        assertArrayEquals(new double[] {-1, 0, 0}, at, 1e-12);
        SunPath.towardMoon(MIDNIGHT, 25, at);
        assertArrayEquals(new double[] {0, 0.9063, 0.4226}, at, 5e-5);
    }

    @Test
    void withNoTiltTheDirectionIsTheGamesOwnAndLiesInThePlaneOfEastAndUp() {
        double[] at = new double[3];
        for (int step = 0; step < 240; step++) {
            double angle = step / 240.0;
            double a = angle * 2 * Math.PI;
            boolean sun = SunPath.towardLight(angle, 0, at);
            // What was computed before there was a tilt: (-sin a, cos a, 0), turned about when below the horizon.
            double x = -Math.sin(a);
            double y = Math.cos(a);
            if (y < 0) {
                x = -x;
                y = -y;
            }
            assertEquals(Double.doubleToLongBits(x), Double.doubleToLongBits(at[0]));
            assertEquals(Double.doubleToLongBits(y), Double.doubleToLongBits(at[1]));
            assertEquals(0L, Double.doubleToLongBits(at[2]));
            assertEquals(Math.cos(a) >= 0, sun);
        }
    }

    @Test
    void theDirectionIsOfUnitLengthAtAnyHourAndTilt() {
        double[] at = new double[3];
        for (double tilt : new double[] {0, 10, 25, 45, -25}) {
            for (int step = 0; step < 48; step++) {
                SunPath.towardSun(step / 48.0, tilt, at);
                assertEquals(1.0, Math.sqrt(at[0] * at[0] + at[1] * at[1] + at[2] * at[2]), 1e-12);
            }
        }
    }

    @Test
    void theLightIsTheSunByDayAndTheMoonByNightAndNeverBelowTheHorizon() {
        double[] at = new double[3];
        assertTrue(SunPath.towardLight(NOON, 25, at));
        assertTrue(at[1] > 0.9);
        assertFalse(SunPath.towardLight(MIDNIGHT, 25, at));
        assertArrayEquals(new double[] {0, 0.9063, 0.4226}, at, 5e-5);
        for (int step = 0; step < 96; step++) {
            SunPath.towardLight(step / 96.0, 25, at);
            assertTrue(at[1] >= 0);
        }
        // With a tilt the light never comes within eight degrees of straight up, where the light
        // view's axes would turn about.
        for (int step = 0; step < 960; step++) {
            SunPath.towardLight(step / 960.0, 25, at);
            assertTrue(at[1] < 0.99);
        }
    }
}
