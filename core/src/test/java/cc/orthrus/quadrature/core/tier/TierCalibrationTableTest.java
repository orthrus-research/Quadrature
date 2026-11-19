package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Holds the calibration in force to what any calibration must be, so a figure copied wrong shows. */
final class TierCalibrationTableTest {

    private static final int[][] OUTPUTS = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};

    @Test
    void eachTierCostsAndHoldsAtLeastAsMuchAsTheOneBelow() {
        TierCalibration calibration = TierCalibration.CURRENT;
        for (int[] output : OUTPUTS) {
            for (int t = 1; t < Tier.values().length; t++) {
                Tier tier = Tier.values()[t];
                Tier below = Tier.values()[t - 1];
                assertTrue(calibration.referenceMs(tier, output[0], output[1])
                    >= calibration.referenceMs(below, output[0], output[1]), tier + " at " + output[0]);
                assertTrue(calibration.needMiB(tier, output[0], output[1])
                    >= calibration.needMiB(below, output[0], output[1]), tier + " at " + output[0]);
            }
        }
    }

    @Test
    void theBudgetAndBandsAreSane() {
        TierCalibration calibration = TierCalibration.CURRENT;
        assertTrue(calibration.budgetMs() > 0 && calibration.budgetMs() < 8.34);
        assertTrue(calibration.band() > 0 && calibration.band() < 0.5);
        assertTrue(calibration.memoryBand() > 0 && calibration.memoryBand() < 0.5);
        for (int[] output : OUTPUTS) {
            for (Tier tier : Tier.values()) {
                assertTrue(calibration.referenceMs(tier, output[0], output[1]) > 0);
                assertTrue(calibration.needMiB(tier, output[0], output[1]) > 0);
            }
        }
    }

    @Test
    void aCalibratedTableHasEveryReferenceFigureButTheTrace() {
        TierCalibration calibration = TierCalibration.CURRENT;
        if (!calibration.calibrated()) {
            return;
        }
        TierCalibration.Reference reference = calibration.reference();
        for (double figure : new double[] {reference.smallGBs(), reference.largeGBs(), reference.lightMsPerMegaPrimitive()}) {
            assertTrue(figure > 0 && Double.isFinite(figure));
        }
        assertTrue(reference.terrain().size() >= 2);
        for (int i = 1; i < reference.terrain().size(); i++) {
            assertTrue(reference.terrain().get(i).megapixels() > reference.terrain().get(i - 1).megapixels());
        }
        // Terrain's model gives a time for any view; the light's for any box of the size the calibration met, the
        // smallest 1.7 million primitives with about seven fragments each.
        for (long primitives : new long[] {100_000, 1_000_000, 5_000_000}) {
            assertTrue(reference.terrainMs(primitives, 1280, 720) > 0);
            assertTrue(reference.terrainMs(primitives, 3840, 2160) > reference.terrainMs(primitives, 1280, 720));
        }
        for (long primitives : new long[] {1_500_000, 5_000_000, 12_000_000}) {
            assertTrue(reference.lightMs(primitives, primitives * 6.5) > 0);
        }
        // A box far smaller than any met says nothing of the card: the light's ratio is left out.
        BenchmarkResult tiny = new BenchmarkResult(TierBenchmark.VERSION, 1280, 720, 348, 350, 0, 0, java.util.List.of(), null,
            0.3, 0, 200_000, 1_000_000, java.util.List.of(), Double.NaN);
        assertTrue(Double.isNaN(TierSelection.ratios(tiny, reference).light()));
    }
}
