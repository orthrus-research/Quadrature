package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class CubeKeyTest {

    @Test
    void roundTripsPositiveNegativeAndExtremeCoordinates() {
        int[][] cases = {{0, 0, 0}, {1, 2, 3}, {-1, -1, -1}, {-534, -104, 737}, {2_097_151, 524_287, -2_097_152},
            {-2_097_152, -524_288, 2_097_151}};
        for (int[] c : cases) {
            long key = CubeKey.of(c[0], c[1], c[2]);
            assertEquals(c[0], CubeKey.x(key));
            assertEquals(c[1], CubeKey.y(key));
            assertEquals(c[2], CubeKey.z(key));
        }
    }

    @Test
    void neighborsHaveDifferentKeys() {
        assertNotEquals(CubeKey.of(0, 0, 0), CubeKey.of(0, 0, -1));
        assertNotEquals(CubeKey.of(0, 0, 0), CubeKey.of(0, -1, 0));
        assertNotEquals(CubeKey.of(0, 0, 0), CubeKey.of(-1, 0, 0));
    }

    @Test
    void distanceIsMeasuredToTheMiddleOfTheCube() {
        assertEquals(0.0, CubeKey.distanceSquared(CubeKey.of(0, 0, 0), 8, 8, 8));
        assertEquals(16.0 * 16.0, CubeKey.distanceSquared(CubeKey.of(-1, 0, 0), 8, 8, 8));
    }
}
