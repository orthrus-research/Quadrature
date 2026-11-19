package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TerrainFitTest {

    private static final double EPSILON = 1e-9;

    @Test
    void anExactLineIsRecovered() {
        TerrainFit fit = TerrainFit.fit(new double[] {1e6, 2e6, 4e6}, new double[] {1.5, 2.0, 3.0}, 1_000_000);
        assertEquals(1.0, fit.interceptMs(), EPSILON);
        assertEquals(0.5, fit.msPerMegaFragment(), EPSILON);
        assertEquals(1.0, fit.nsPerPrimitive(), EPSILON);
        assertEquals(1.0 + 0.5 * 3, fit.predictMs(1_000_000, 3e6), EPSILON);
    }

    @Test
    void aSlopeBelowZeroPutsAllTheTimeToTheGeometry() {
        TerrainFit fit = TerrainFit.fit(new double[] {1e6, 2e6}, new double[] {2.0, 1.8}, 1000);
        assertEquals(0, fit.msPerMegaFragment(), EPSILON);
        assertEquals(1.9, fit.interceptMs(), EPSILON);
    }

    @Test
    void anInterceptBelowZeroPutsAllTheTimeToTheFragments() {
        // The line through these meets the axis at -1 ms.
        TerrainFit fit = TerrainFit.fit(new double[] {1e6, 2e6}, new double[] {1.0, 3.0}, 1000);
        assertEquals(0, fit.interceptMs(), EPSILON);
        assertEquals((1.0 * 1 + 3.0 * 2) / (1 + 4), fit.msPerMegaFragment(), EPSILON);
    }

    @Test
    void onePointHasNoSlopeAndNoPointsNoFit() {
        TerrainFit one = TerrainFit.fit(new double[] {2e6}, new double[] {3.0}, 1000);
        assertEquals(0, one.interceptMs(), EPSILON);
        assertEquals(1.5, one.msPerMegaFragment(), EPSILON);
        assertTrue(Double.isNaN(TerrainFit.fit(new double[0], new double[0], 1000).interceptMs()));
        assertTrue(Double.isNaN(new TerrainFit(1, 1, 0, 2).nsPerPrimitive()));
    }
}
