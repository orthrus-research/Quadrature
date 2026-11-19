package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.orthrus.quadrature.core.tier.TierCalibration.Cost;
import cc.orthrus.quadrature.core.tier.TierCalibration.Memory;
import cc.orthrus.quadrature.core.tier.TierCalibration.Probe;
import cc.orthrus.quadrature.core.tier.TierCalibration.Reference;

import org.junit.jupiter.api.Test;

import java.util.List;

final class TierSelectionTest {

    private static final double EPSILON = 1e-9;

    /** A reference card whose figures are round, and tiers costing 2, 4, 6 and 8 ms at any output. */
    private static TierCalibration calibration() {
        // Terrain 0.5 ms a million primitives and 1 ms a million pixels; the light 0.25 ms a million primitives.
        Reference reference = new Reference("test", 100, 50, 0.5, 1.0, 0.25, 0, 2.0);
        List<List<Cost>> costs = List.of(costs(2), costs(4), costs(6), costs(8));
        List<Memory> memory = List.of(new Memory(1000, 0, 0), new Memory(2000, 0, 0), new Memory(3000, 0, 0),
            new Memory(4000, 0, 0));
        return new TierCalibration(true, reference, 5.0, 0.1, 0.05, costs, memory, dlss(Double.NaN));
    }

    /** DLSS at 0.5 ms on the reference card at 2560 by 1440, with a handoff where one is given. */
    private static TierCalibration.Dlss dlss(double handoffMs) {
        return new TierCalibration.Dlss("test", 0.5 / (2560 * 1440 / 1e6), handoffMs);
    }

    /** A tier's cost split evenly over the four probes, fixed, so that the output does not matter. */
    private static List<Cost> costs(double total) {
        return List.of(new Cost("terrain", Probe.TERRAIN, total / 4, 0, 0), new Cost("light", Probe.LIGHT, total / 4, 0, 0),
            new Cost("trace", Probe.TRACE, total / 4, 0, 0), new Cost("passes", Probe.BANDWIDTH, total / 4, 0, 0));
    }

    /** A result as the reference card would give it, every probe {@code speed} times as fast. */
    private static BenchmarkResult result(double speed, boolean trace) {
        // A million primitives at 2560 by 1440: 0.5 + 3.6864 ms on the reference. Four million in the light box: 1 ms.
        List<BenchmarkResult.TerrainPoint> terrain = List.of(
            new BenchmarkResult.TerrainPoint(2560, 1440, (0.5 + 3.6864) / speed, 0, 5e6, 1_000_000));
        return new BenchmarkResult(TierBenchmark.VERSION, 2560, 1440, 100 * speed, 50 * speed, 0, 0, terrain, null,
            1.0 / speed, 0, 4_000_000, 0, List.of(), trace ? 2.0 * speed : Double.NaN);
    }

    @Test
    void theReferenceCardHasRatiosOfOneAndItsOwnCosts() {
        TierCalibration calibration = calibration();
        TierSelection.Ratios ratios = TierSelection.ratios(result(1, true), calibration.reference());
        assertEquals(1, ratios.terrain(), EPSILON);
        assertEquals(1, ratios.light(), EPSILON);
        assertEquals(1, ratios.trace(), EPSILON);
        assertEquals(1, ratios.bandwidth(), EPSILON);
        double[] predicted = TierSelection.predictedMs(ratios, calibration, 2560, 1440);
        assertEquals(2, predicted[0], EPSILON);
        assertEquals(8, predicted[3], EPSILON);
        assertEquals(1, TierSelection.score(ratios, calibration, 2560, 1440), EPSILON);
    }

    @Test
    void aCardTwiceAsFastIsPredictedAtHalfTheCost() {
        TierCalibration calibration = calibration();
        TierSelection.Outcome outcome = TierSelection.choose(result(2, true), calibration, 10_000, null, null);
        assertEquals(2, outcome.score(), EPSILON);
        assertEquals(3, outcome.predictedMs()[Tier.HIGH.ordinal()], EPSILON);
        // Budget 5 ms: HIGH costs 3, MAX 4.
        assertEquals(Tier.MAX, outcome.speed());
        TierSelection.Outcome reference = TierSelection.choose(result(1, true), calibration, 10_000, null, null);
        // LOW 2 and MEDIUM 4 fit 5 ms; HIGH 6 does not.
        assertEquals(Tier.MEDIUM, reference.speed());
    }

    @Test
    void aMissingProbeIsStoodInForByTheOthers() {
        TierSelection.Ratios ratios = TierSelection.ratios(result(2, false), calibration().reference());
        assertTrue(Double.isNaN(ratios.trace()));
        assertEquals(2, ratios.fallback(), EPSILON);
        assertEquals(2, ratios.of(Probe.TRACE), EPSILON);
        assertEquals(2, TierSelection.score(ratios, calibration(), 2560, 1440), EPSILON);
    }

    @Test
    void theTerrainRatioIsTheReferencesModelOverTheTimesTakenAtEveryResolution() {
        Reference reference = calibration().reference();
        // The reference would take 0.5 + 0.9216 ms at 1280 by 720 and 0.5 + 3.6864 at 2560 by 1440.
        List<BenchmarkResult.TerrainPoint> terrain = List.of(
            new BenchmarkResult.TerrainPoint(1280, 720, 2.0, 0, 2e6, 1_000_000),
            new BenchmarkResult.TerrainPoint(2560, 1440, 5.0, 0, 8e6, 1_000_000),
            new BenchmarkResult.TerrainPoint(3840, 2160, 9.0, 0, 0, 0));
        BenchmarkResult result = new BenchmarkResult(1, 2560, 1440, 100, 50, 0, 0, terrain, null, Double.NaN, 0, 0, 0,
            List.of(), Double.NaN);
        // A resolution that drew nothing is left out.
        assertEquals((1.4216 + 4.1864) / 7.0, TierSelection.ratios(result, reference).terrain(), 1e-9);
        assertTrue(Double.isNaN(TierSelection.ratios(result, reference).light()));
    }

    @Test
    void withoutAPreviousTierTheHighestFittingIsTaken() {
        assertEquals(Tier.HIGH, TierSelection.withBand(new double[] {3, 2, 1.0, 0.99}, null, 0.1));
        assertEquals(Tier.LOW, TierSelection.withBand(new double[] {0.5, 0.4, 0.3, 0.2}, null, 0.1));
        assertEquals(Tier.MAX, TierSelection.withBand(new double[] {4, 3, 2, 1}, null, 0.1));
    }

    @Test
    void aHigherTierIsTakenOnlyPastTheBand() {
        // MAX fits by 5%: not enough to leave HIGH.
        assertEquals(Tier.HIGH, TierSelection.withBand(new double[] {3, 2, 1.5, 1.05}, Tier.HIGH, 0.1));
        // By 10%: taken.
        assertEquals(Tier.MAX, TierSelection.withBand(new double[] {3, 2, 1.5, 1.1}, Tier.HIGH, 0.1));
        // Two tiers up at once where both fit past the band.
        assertEquals(Tier.HIGH, TierSelection.withBand(new double[] {3, 2, 1.2, 0.9}, Tier.LOW, 0.1));
    }

    @Test
    void theLastTierIsLeftOnlyPastTheBand() {
        // HIGH misses by 5%: kept.
        assertEquals(Tier.HIGH, TierSelection.withBand(new double[] {3, 2, 0.95, 0.7}, Tier.HIGH, 0.1));
        // By 11%: the highest lower tier that fits plainly.
        assertEquals(Tier.MEDIUM, TierSelection.withBand(new double[] {3, 1.01, 0.89, 0.7}, Tier.HIGH, 0.1));
        assertEquals(Tier.LOW, TierSelection.withBand(new double[] {0.8, 0.6, 0.5, 0.4}, Tier.HIGH, 0.1));
    }

    @Test
    void aRunJustInsideTheBandDoesNotMoveTheTierEitherWay() {
        double[] justAbove = {3, 2, 1.04, 0.8};
        double[] justBelow = {3, 2, 0.96, 0.8};
        Tier tier = TierSelection.withBand(justBelow, Tier.MEDIUM, 0.05);
        assertEquals(Tier.MEDIUM, tier);
        tier = TierSelection.withBand(justAbove, tier, 0.05);
        assertEquals(Tier.MEDIUM, tier);
        tier = TierSelection.withBand(new double[] {3, 2, 1.06, 0.8}, tier, 0.05);
        assertEquals(Tier.HIGH, tier);
        tier = TierSelection.withBand(justBelow, tier, 0.05);
        assertEquals(Tier.HIGH, tier);
    }

    @Test
    void theMemoryTierIsTheHighestWhoseMemoryIsAvailable() {
        TierSelection.Outcome outcome = TierSelection.choose(result(1, true), calibration(), 3_500, null, null);
        assertEquals(Tier.HIGH, outcome.memory());
        assertEquals(3_500 / 4_000.0, outcome.memoryFits()[Tier.MAX.ordinal()], EPSILON);
        // Kept at MAX by the band from a last choice of MAX where 3,900 MiB is 2.5% short.
        assertEquals(Tier.MAX, TierSelection.choose(result(1, true), calibration(), 3_900, null, Tier.MAX).memory());
    }

    @Test
    void costsFollowTheRenderAndOutputPixels() {
        Reference reference = calibration().reference();
        List<Cost> perPixel = List.of(new Cost("terrain", Probe.TERRAIN, 1, 1, 0), new Cost("scaler", Probe.BANDWIDTH, 0, 0, 2));
        TierCalibration pixels = new TierCalibration(true, reference, 5, 0.1, 0.05,
            List.of(perPixel, perPixel, perPixel, perPixel), calibration().memory(), dlss(Double.NaN));
        // 1,000 by 1,000 output: LOW renders at 67%, 0.4489 megapixels; the scaler works at the output.
        assertEquals(1 + 0.4489 + 2, pixels.referenceMs(Tier.LOW, 1000, 1000), 1e-6);
        assertEquals(1 + 1 + 2, pixels.referenceMs(Tier.MAX, 1000, 1000), 1e-6);
    }

    @Test
    void dlssIsOfferedWhereTheSpeedTierLeavesRoomForIt() {
        // The reference card: MEDIUM costs 4 ms of a 5 ms budget, and DLSS 0.5 ms.
        TierSelection.Outcome reference = TierSelection.choose(result(1, true), calibration(), 10_000, null, null);
        assertEquals(0.5, reference.dlssMs(), EPSILON);
        assertEquals(5 / 4.5, reference.dlssFit(), EPSILON);
        assertTrue(reference.dlssOffered());
        // A card twice as fast takes MAX at 4 ms; DLSS costs it half as much.
        TierSelection.Outcome fast = TierSelection.choose(result(2, true), calibration(), 10_000, null, null);
        assertEquals(Tier.MAX, fast.speed());
        assertEquals(0.25, fast.dlssMs(), EPSILON);
        // A measured handoff of 1 ms is counted, and takes the room away on the reference card.
        TierCalibration handed = new TierCalibration(true, calibration().reference(), 5.0, 0.1, 0.05,
            calibration().costs(), calibration().memory(), dlss(1.0));
        TierSelection.Outcome withHandoff = TierSelection.choose(result(1, true), handed, 10_000, null, null);
        assertEquals(1.5, withHandoff.dlssMs(), EPSILON);
        assertTrue(!withHandoff.dlssOffered());
    }
}
