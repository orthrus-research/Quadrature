package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /**
     * This card's own result, measured on the GPU (VERIFICATION, the tier benchmark: run 25 of the r16 tour, 2026-11-19,
     * the calibrated jar), at each output: the tiers the calibration in force chooses for it. At 1280 by 720, the
     * window it ran in, it chose HIGH, and the table it ran with chose HIGH at 1920 by 1080 and 2560 by 1440 and MEDIUM
     * at 3840 by 2160. At 3840 by 2160 it stands near the boundary between LOW and MEDIUM: MEDIUM's fit is 1.07 with the
     * scaler's gaps between passes held fixed, as measured, and would be 1.01 were they to follow the output's pixels; a
     * measurement of the scaler at that output settles it. The run's trace was timed on the walk that read the bricks'
     * coarser bits, and is scaled to the walk as shipped by the same factor as the reference, so its ratio stands.
     */
    @Test
    void thisCardsMeasuredResultTakesTheTiersItTookOnTheGpu() {
        int[][] sizes = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
        double[] millis = {1.3117, 1.4817, 1.7203, 3.2143};
        double[] fragments = {2_042_959, 4_440_170, 7_796_303, 17_329_617};
        Tier[] expected = {Tier.HIGH, Tier.HIGH, Tier.HIGH, Tier.MEDIUM};
        for (int o = 0; o < sizes.length; o++) {
            java.util.List<BenchmarkResult.TerrainPoint> terrain = new java.util.ArrayList<>();
            for (int i = 0; i < sizes.length; i++) {
                terrain.add(new BenchmarkResult.TerrainPoint(sizes[i][0], sizes[i][1], millis[i], 0, fragments[i], 4_796_322));
            }
            BenchmarkResult result = new BenchmarkResult(TierBenchmark.VERSION, sizes[o][0], sizes[o][1], 347.671, 349.759, 0, 0,
                terrain, null, 4.72576, 0, 19_338_946, 47_308_444, java.util.List.of(), 0.30918 * 0.180 / 0.222);
            TierSelection.Outcome outcome = TierSelection.choose(result, TierCalibration.CURRENT, 8_637, null, null);
            assertEquals(expected[o], outcome.speed(), sizes[o][0] + " by " + sizes[o][1]);
            assertEquals(Tier.MAX, outcome.memory(), sizes[o][0] + " by " + sizes[o][1]);
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
