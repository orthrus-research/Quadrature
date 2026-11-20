package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /** The scaler's and DLSS's lines pass through the points measured at 2560 by 1440 and 3840 by 2160. */
    @Test
    void theScalersAndDlsssCostsMeetTheirMeasurements() {
        double[] mp = {2560 * 1440 / 1e6, 3840 * 2160 / 1e6};
        double[][] measured = {{0.982, 1.520}, {0.972, 1.781}, {0.957, 2.010}, {0.957, 2.010}};
        for (Tier tier : Tier.values()) {
            double scale = TierBenchmark.renderScale(tier);
            TierCalibration.Cost cost = TierCalibration.CURRENT.costs().get(tier.ordinal()).stream()
                .filter(c -> c.name().startsWith("own temporal anti-aliasing")).findFirst().orElseThrow();
            for (int o = 0; o < 2; o++) {
                assertEquals(measured[tier.ordinal()][o], cost.ms(mp[o] * scale * scale, mp[o]), 1e-9, tier + " at " + mp[o]);
            }
        }
        TierCalibration.Dlss dlss = TierCalibration.CURRENT.dlss();
        assertEquals(2.653, dlss.ms(mp[0]), 1e-9);
        assertEquals(5.482, dlss.ms(mp[1]), 1e-9);
        assertTrue(Double.isFinite(dlss.handoffMs()), "the handoff is measured and counted");
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
     * at 3840 by 2160. With the scaler measured at 3840 by 2160 as well as 2560 by 1440 (VERIFICATION, the scaler's
     * costs), MEDIUM's fit there is 1.11 and HIGH's at 2560 by 1440 1.24; the choices stand. The run's trace was timed on the walk that read the bricks'
     * coarser bits, and is scaled to the walk as shipped by the same factor as the reference, so its ratio stands.
     */
    @Test
    void thisCardsMeasuredResultTakesTheTiersItTookOnTheGpu() {
        int[][] sizes = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
        double[] millis = {1.3117, 1.4817, 1.7203, 3.2143};
        double[] fragments = {2_042_959, 4_440_170, 7_796_303, 17_329_617};
        // With distant terrain counted (benchmark version 6) the card takes a tier lower than before from 1920 by 1080 up.
        Tier[] expected = {Tier.HIGH, Tier.MEDIUM, Tier.MEDIUM, Tier.LOW};
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
            assertTrue(outcome.traceAgrees());
            // Drawing below the output pays on this card at every size, distant terrain's pixels counted: LOW and
            // MEDIUM keep their own scales.
            assertEquals(0.67, outcome.scales()[Tier.LOW.ordinal()], 1e-12, sizes[o][0] + " by " + sizes[o][1]);
            assertEquals(0.77, outcome.scales()[Tier.MEDIUM.ordinal()], 1e-12, sizes[o][0] + " by " + sizes[o][1]);
        }
    }

    /**
     * Two runs of this card that timed the light scene's copy on the GPU before it had caught up with the scene
     * (VERIFICATION, the tier benchmark), at 1280 by 720: one on the forest tour at radius 12, 2026-11-19, whose rays
     * met a copy that held no brick, and one at radius 16 whose first run at a place read the trace a third as fast as
     * the place's later runs. Each trace stands past {@link TierSelection#TRACE_LEEWAY} from the other probes, so
     * neither run chooses. The traces were timed on the walk that read the bricks' coarser bits and are scaled to the
     * walk as shipped, as the reference is.
     */
    @Test
    void runsThatTimedTheScenesCopyBeforeItCaughtUpChooseNothing() {
        TierSelection.Outcome empty = outcomeAt720(new double[] {0.478208, 0.572416, 0.693248, 1.439744},
            new double[] {1_590_275, 3_457_276, 6_058_498, 13_407_253}, 1_445_920, 348.364, 351.164,
            2.376704, 5_980_284, 29_649_601, 4.266667);
        assertFalse(empty.traceAgrees());
        assertTrue(empty.ratios().traceApart() > 10);
        TierSelection.Outcome slow = outcomeAt720(new double[] {0.914432, 1.074176, 1.283072, 2.886656},
            new double[] {1_948_828, 4_251_588, 7_458_228, 16_572_749}, 3_103_454, 348.133, 349.992,
            2.605056, 11_890_966, 30_237_551, 0.109589);
        assertFalse(slow.traceAgrees());
        assertTrue(slow.ratios().traceApart() > 3);
    }

    private static TierSelection.Outcome outcomeAt720(double[] millis, double[] fragments, long primitives, double smallGBs,
                                                      double largeGBs, double lightMs, long lightPrimitives,
                                                      double lightFragments, double traceGigaRays) {
        java.util.List<BenchmarkResult.TerrainPoint> terrain = new java.util.ArrayList<>();
        for (int i = 0; i < OUTPUTS.length; i++) {
            terrain.add(new BenchmarkResult.TerrainPoint(OUTPUTS[i][0], OUTPUTS[i][1], millis[i], 0, fragments[i], primitives));
        }
        BenchmarkResult result = new BenchmarkResult(TierBenchmark.VERSION, 1280, 720, smallGBs, largeGBs, 0, 0, terrain,
            null, lightMs, 0, lightPrimitives, lightFragments, java.util.List.of(), traceGigaRays * 0.180 / 0.222);
        return TierSelection.choose(result, TierCalibration.CURRENT, 8_550, null, null);
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
