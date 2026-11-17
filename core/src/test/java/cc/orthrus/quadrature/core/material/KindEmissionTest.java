package cc.orthrus.quadrature.core.material;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KindEmissionTest {

    /** Nine colors, from black to nearly white. */
    private static final float[][] COLORS = {
        {0f, 0f, 0f}, {0.05f, 0.10f, 0.02f}, {0.12f, 0.12f, 0.12f}, {0.20f, 0.15f, 0.10f}, {0.30f, 0.25f, 0.05f},
        {0.45f, 0.40f, 0.20f}, {0.65f, 0.50f, 0.30f}, {0.70f, 0.70f, 0.70f}, {1.00f, 0.90f, 0.60f},
    };

    private static void rows(int kind, double[] values, int[] stored) {
        for (int color = 0; color < COLORS.length; color++) {
            float[] c = COLORS[color];
            assertEquals(values[color], KindEmission.of(kind, c[0], c[1], c[2]), 1e-6, "kind " + kind + ", color " + color);
            assertEquals(stored[color], KindEmission.stored(kind, c[0], c[1], c[2]), "kind " + kind + ", color " + color);
        }
    }

    @Test
    void lavaGivesATenthWhateverItsColor() {
        rows(101, new double[] {0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1}, new int[] {26, 26, 26, 26, 26, 26, 26, 26, 26});
    }

    @Test
    void aDimEmitterRisesWithItsColorToTwoThirds() {
        rows(120, new double[] {0, 0, 0, 0, 0.067600, 0.325000, 0.631800, 0.650000, 0.650000},
            new int[] {0, 0, 0, 0, 17, 83, 161, 166, 166});
    }

    @Test
    void aBrightEmitterRisesSoonerToNineTenths() {
        rows(121, new double[] {0, 0, 0, 0.055326, 0.240916, 0.612246, 0.900000, 0.900000, 0.900000},
            new int[] {0, 0, 0, 14, 61, 156, 230, 230, 230});
    }

    @Test
    void everyOtherKindGivesNothing() {
        double[] nothing = new double[9];
        int[] none = new int[9];
        rows(199, nothing, none);
        rows(140, nothing, none);
    }

    @Test
    void aBlockThatGivesLightAndIsNotNamedIsBrightFromTwelve() {
        assertEquals(MaterialKinds.NONE, KindEmission.fallbackKind(0));
        assertEquals(KindEmission.DIM, KindEmission.fallbackKind(1));
        assertEquals(KindEmission.DIM, KindEmission.fallbackKind(11));
        assertEquals(KindEmission.BRIGHT, KindEmission.fallbackKind(12));
        assertEquals(KindEmission.BRIGHT, KindEmission.fallbackKind(15));
    }
}
