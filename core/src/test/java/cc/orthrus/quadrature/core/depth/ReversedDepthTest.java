package cc.orthrus.quadrature.core.depth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReversedDepthTest {

    /** A conventional perspective projection, column-major. */
    private static double[] perspective(double near, double far) {
        double[] m = new double[16];
        m[0] = 1.0;
        m[5] = 1.0;
        m[10] = -(far + near) / (far - near);
        m[11] = -1.0;
        m[14] = -2.0 * far * near / (far - near);
        return m;
    }

    private static double[] orthographic(double near, double far) {
        double[] m = new double[16];
        m[0] = 1.0;
        m[5] = 1.0;
        m[10] = -2.0 / (far - near);
        m[14] = -(far + near) / (far - near);
        m[15] = 1.0;
        return m;
    }

    /** Depth after the perspective divide of a point on the view axis at the given distance. */
    private static double depthAt(double[] m, double distance) {
        double z = m[10] * -distance + m[14];
        double w = m[11] * -distance + m[15];
        return z / w;
    }

    @Test
    void comparisonsSwapDirectionAndNothingElse() {
        assertEquals(ReversedDepth.GREATER_OR_EQUAL, ReversedDepth.comparison(ReversedDepth.LESS_OR_EQUAL));
        assertEquals(ReversedDepth.GREATER, ReversedDepth.comparison(ReversedDepth.LESS));
        assertEquals(ReversedDepth.LESS, ReversedDepth.comparison(ReversedDepth.GREATER));
        assertEquals(ReversedDepth.LESS_OR_EQUAL, ReversedDepth.comparison(ReversedDepth.GREATER_OR_EQUAL));
        for (int same : new int[] {ReversedDepth.NEVER, ReversedDepth.EQUAL, ReversedDepth.NOT_EQUAL,
            ReversedDepth.ALWAYS}) {
            assertEquals(same, ReversedDepth.comparison(same));
        }
    }

    @Test
    void comparisonIsItsOwnInverse() {
        for (int function = ReversedDepth.NEVER; function <= ReversedDepth.ALWAYS; function++) {
            assertEquals(function, ReversedDepth.comparison(ReversedDepth.comparison(function)));
        }
    }

    @Test
    void clearValueAndOffsetFlip() {
        assertEquals(0.0, ReversedDepth.depth(1.0));
        assertEquals(1.0, ReversedDepth.depth(0.0));
        assertEquals(3.0f, ReversedDepth.offset(-3.0f));
    }

    @Test
    void perspectivePutsNearAtOneAndFarAtZero() {
        double[] m = perspective(0.05, 512.0);
        ReversedDepth.reverse(m);
        assertEquals(1.0, depthAt(m, 0.05), 1e-12);
        assertEquals(0.0, depthAt(m, 512.0), 1e-12);
    }

    @Test
    void orthographicPutsNearAtOneAndFarAtZero() {
        double[] m = orthographic(1000.0, 3000.0);
        ReversedDepth.reverse(m);
        assertEquals(1.0, depthAt(m, 1000.0), 1e-12);
        assertEquals(0.0, depthAt(m, 3000.0), 1e-12);
    }

    @Test
    void reversedDepthFallsWithDistance() {
        double[] m = perspective(0.05, 512.0);
        ReversedDepth.reverse(m);
        double last = 2.0;
        for (double distance = 0.05; distance <= 512.0; distance *= 1.5) {
            double depth = depthAt(m, distance);
            assertEquals(true, depth < last);
            last = depth;
        }
    }

    @Test
    void restoreUndoesReverse() {
        double[] original = perspective(0.05, 512.0);
        double[] m = original.clone();
        ReversedDepth.reverse(m);
        ReversedDepth.restore(m);
        assertArrayEquals(original, m, 1e-12);
    }
}
