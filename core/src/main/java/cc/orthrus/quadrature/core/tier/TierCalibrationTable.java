package cc.orthrus.quadrature.core.tier;

import cc.orthrus.quadrature.core.tier.TierCalibration.Cost;
import cc.orthrus.quadrature.core.tier.TierCalibration.Memory;
import cc.orthrus.quadrature.core.tier.TierCalibration.Probe;
import cc.orthrus.quadrature.core.tier.TierCalibration.Reference;
import cc.orthrus.quadrature.core.tier.TierCalibration.TerrainLine;

import java.util.List;

/**
 * The figures of {@link TierCalibration#CURRENT}, kept apart so that a new calibration changes this
 * file and {@link TierBenchmark#VERSION} and nothing else. Measured on an RTX 3060 12 GB, driver
 * 610.88, at settled clocks, over 93 runs at the forest tour's 17 places on two fresh instances at
 * radius 12; how each figure was found is in ARCHITECTURE 12.1.1 and VERIFICATION, the tier benchmark.
 */
final class TierCalibrationTable {

    /**
     * NVIDIA's published cost on the RTX 3060 (DLSS Super Resolution Programming Guide 310.6.0, March 2026,
     * measured by NVIDIA's command-line tool without a renderer): presets J and K in Performance mode, 1.45 ms
     * at 1920 by 1080 output. The handoff to Vulkan and back is not yet measured.
     */
    static final TierCalibration.Dlss DLSS = new TierCalibration.Dlss("Super Resolution, preset K, Performance",
        1.45 / (1920 * 1080 / 1e6), Double.NaN);

    /** Opaque terrain drawn forward at radius 12, the median place: ms = a + b MP + c MP squared. */
    private static final double[] RADIUS_12 = {0.5042, -0.0317, 0.02231};
    /**
     * The forward draw at each tier's radius against radius 12: radius 8 from the near radius's table at 1280
     * by 720, radii 16 and 20 from the one place measured settled at 20 and that table's step from 16 to 20.
     */
    private static final double[] RADIUS_FACTOR = {0.53, 1.0, 1.68, 1.75};
    /** The targets for shading drawn beside the forward picture, as a share of it (the near radius's table). */
    private static final double TARGETS = 0.5;
    /** The light's depth a frame that draws it: one layer (LOW, MEDIUM) and both (HIGH, MAX). */
    private static final double[] LIGHT_MS = {0.17, 0.17, 0.49, 0.49};
    /** Indirect light's rays, each tier's whole batch every frame (an upper bound), and MAX's far shadows. */
    private static final double[] RAYS = {0, 65_536, 524_288, 2_097_152};
    private static final double RAYS_A_SECOND = 0.376e9;
    private static final double FAR_SHADOWS_MS = 4.3;

    private TierCalibrationTable() {
    }

    static TierCalibration current() {
        Reference reference = new Reference("RTX 3060 12 GB, driver 610.88", 348.1, 350.0, List.of(
            new TerrainLine(0.9216, 0.0602, 0.2643),
            new TerrainLine(2.0736, 0.0935, 0.2922),
            new TerrainLine(3.6864, 0.1295, 0.3361),
            new TerrainLine(8.2944, 0.1287, 1.0039)),
            -0.2987, 0.0870, 0.0627, 0.376);
        List<List<Cost>> costs = List.of(tier(Tier.LOW), tier(Tier.MEDIUM), tier(Tier.HIGH), tier(Tier.MAX));
        // Near terrain's arenas at the tier's radius, distant terrain's reach, the light's layers with their
        // scratch, the light scene's buffers; the targets for shading and a stage's own at the render resolution,
        // a history at the output's.
        List<Memory> memory = List.of(
            new Memory(128 + 40 + 32, 30.5, 15.3),
            new Memory(320 + 80 + 128 + 8.6, 30.5, 15.3),
            new Memory(448 + 141 + 256 + 8.6, 30.5, 15.3),
            new Memory(704 + 250 + 256 + 8.6, 30.5, 15.3));
        return new TierCalibration(true, reference, 7.0, 0.15, 0.05, costs, memory, DLSS);
    }

    private static List<Cost> tier(Tier tier) {
        int t = tier.ordinal();
        double f = RADIUS_FACTOR[t];
        List<Cost> costs = new java.util.ArrayList<>(List.of(
            new Cost("opaque terrain drawn forward", Probe.TERRAIN, f * RADIUS_12[0], f * RADIUS_12[1], f * RADIUS_12[2], 0),
            new Cost("the targets for shading", Probe.TERRAIN, TARGETS * f * RADIUS_12[0], TARGETS * f * RADIUS_12[1],
                TARGETS * f * RADIUS_12[2], 0),
            new Cost("the light's depth", Probe.LIGHT, LIGHT_MS[t], 0, 0),
            new Cost("shading", Probe.BANDWIDTH, 0, 0.19, 0),
            new Cost("the game's other drawing", Probe.BANDWIDTH, 0, 0.33, 0)));
        if (RAYS[t] > 0) {
            costs.add(new Cost("indirect light's rays", Probe.TRACE, RAYS[t] / RAYS_A_SECOND * 1e3, 0, 0));
        }
        if (tier == Tier.MAX) {
            costs.add(new Cost("far shadows' rays", Probe.TRACE, FAR_SHADOWS_MS, 0, 0));
        }
        return costs;
    }
}
