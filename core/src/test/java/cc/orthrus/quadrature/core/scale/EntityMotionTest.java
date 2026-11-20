package cc.orthrus.quadrature.core.scale;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

class EntityMotionTest {

    private static double[] apply(double[] m, double x, double y, double z) {
        return new double[] {m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14]};
    }

    /** How the game places a point of the model: turned by 180 - yaw about the vertical (glRotate), then moved. */
    private static double[] drawn(double[] local, double[] position, double yaw) {
        double a = Math.toRadians(180 - yaw);
        double x = Math.cos(a) * local[0] + Math.sin(a) * local[2];
        double z = -Math.sin(a) * local[0] + Math.cos(a) * local[2];
        return new double[] {x + position[0], local[1] + position[1], z + position[2]};
    }

    @Test
    void aPointOfTheModelLandsWhereTheGameDrewItTheFrameBefore() {
        Random random = new Random(76);
        for (int round = 0; round < 1000; round++) {
            double[] local = {random.nextDouble() * 2 - 1, random.nextDouble() * 3, random.nextDouble() * 2 - 1};
            double[] position = {random.nextDouble() * 4000 - 2000, random.nextDouble() * 200, random.nextDouble() * 4000 - 2000};
            double[] previous = {position[0] + random.nextGaussian() * 0.3, position[1] + random.nextGaussian() * 0.1,
                position[2] + random.nextGaussian() * 0.3};
            double yaw = random.nextDouble() * 720 - 360;
            double previousYaw = yaw + random.nextGaussian() * 10;
            double[] origin = {position[0] + random.nextGaussian() * 5, position[1] + 1.6, position[2] + random.nextGaussian() * 5};
            double[] previousOrigin = {origin[0] - 0.4, origin[1], origin[2] + 0.2};

            double[] now = drawn(local, position, yaw);
            double[] before = drawn(local, previous, previousYaw);
            double[] m = EntityMotion.toPrevious(position, yaw, previous, previousYaw, origin, previousOrigin);
            double[] got = apply(m, now[0] - origin[0], now[1] - origin[1], now[2] - origin[2]);
            assertEquals(before[0] - previousOrigin[0], got[0], 1e-9);
            assertEquals(before[1] - previousOrigin[1], got[1], 1e-9);
            assertEquals(before[2] - previousOrigin[2], got[2], 1e-9);
        }
    }

    @Test
    void anEntityStandingStillMovesOnlyWithTheOrigin() {
        double[] at = {10, 64, -5};
        double[] m = EntityMotion.toPrevious(at, 30, at, 30, new double[] {12, 65, -3}, new double[] {11, 65, -3});
        double[] got = apply(m, 1, 2, 3);
        // The same place, relative to an origin one block further back along x.
        assertEquals(2, got[0], 1e-12);
        assertEquals(2, got[1], 1e-12);
        assertEquals(3, got[2], 1e-12);
    }
}
