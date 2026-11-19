package cc.orthrus.quadrature.core.tier;

import cc.orthrus.quadrature.core.tier.TierCalibration.Cost;
import cc.orthrus.quadrature.core.tier.TierCalibration.Memory;
import cc.orthrus.quadrature.core.tier.TierCalibration.Probe;
import cc.orthrus.quadrature.core.tier.TierCalibration.Reference;

import java.util.List;

/**
 * The figures of {@link TierCalibration#CURRENT}, kept apart so that a new calibration changes this
 * file and {@link TierBenchmark#VERSION} and nothing else.
 */
final class TierCalibrationTable {

    /**
     * NVIDIA's published cost on the RTX 3060 (DLSS Super Resolution Programming Guide 310.6.0, March 2026,
     * measured by NVIDIA's command-line tool without a renderer): presets J and K in Performance mode, 1.45 ms
     * at 1920 by 1080 output. The handoff to Vulkan and back is not yet measured.
     */
    static final TierCalibration.Dlss DLSS = new TierCalibration.Dlss("Super Resolution, preset K, Performance",
        1.45 / (1920 * 1080 / 1e6), Double.NaN);

    private TierCalibrationTable() {
    }

    static TierCalibration current() {
        // Not yet measured by the benchmark itself: provisional figures from the stage reports at 1280 by
        // 720 (VERIFICATION, opaque terrain against the near radius; the light's depth by tiles; the stage
        // handed over in a folder), with the terrain's time split by the radius-8-to-20 slope.
        Reference reference = new Reference("RTX 3060 12 GB, driver 610.88", Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        List<List<Cost>> costs = List.of(
            tier(0.23, 0.05),
            tier(1.08, 0.10),
            tier(1.50, 0.17),
            tier(1.59, 0.17));
        List<Memory> memory = List.of(
            new Memory(187, 30.5, 15.3),
            new Memory(557, 30.5, 15.3),
            new Memory(985, 30.5, 15.3),
            new Memory(1338, 30.5, 15.3));
        return new TierCalibration(false, reference, 7.0, 0.10, 0.05, costs, memory, DLSS);
    }

    private static List<Cost> tier(double terrainFixedMs, double lightMs) {
        return List.of(
            new Cost("opaque terrain", Probe.TERRAIN, terrainFixedMs, 0.79, 0),
            new Cost("the light's depth", Probe.LIGHT, lightMs, 0, 0),
            new Cost("shading", Probe.BANDWIDTH, 0, 0.19, 0),
            new Cost("the game's other drawing", Probe.BANDWIDTH, 0, 0.33, 0));
    }
}
