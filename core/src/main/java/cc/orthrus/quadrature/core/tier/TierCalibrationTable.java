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
 * 610.88, at settled clocks: the reference models over 128 runs at radii 12, 16 and 20, the costs from
 * the benchmark's own probes and the other items' measurements; how each figure was found is in
 * ARCHITECTURE 12.1.1 and VERIFICATION, the tier benchmark.
 */
final class TierCalibrationTable {

    /**
     * NVIDIA's published cost on the RTX 3060 (DLSS Super Resolution Programming Guide 310.6.0, March 2026,
     * measured by NVIDIA's command-line tool without a renderer): presets J and K in Performance mode, 1.45 ms
     * at 1920 by 1080 output. The handoff to Vulkan and back is not yet measured.
     */
    static final TierCalibration.Dlss DLSS = new TierCalibration.Dlss("Super Resolution, preset K, Performance",
        1.45 / (1920 * 1080 / 1e6), Double.NaN);

    /**
     * Opaque terrain drawn forward at each tier's radius, the median place: ms = a + b MP + c MP squared. Radius 12
     * over the forest tour's 17 places; 16 and 20 from the same places measured at those radii, the step at each
     * resolution taken where the views coincide; 8 as 0.53 of 12, from the near radius's table at 1280 by 720.
     */
    private static final double[][] TERRAIN = {
        {0.2673, -0.0168, 0.01182},
        {0.5042, -0.0317, 0.02231},
        {0.7258, -0.0388, 0.02607},
        {0.9771, -0.0528, 0.02934}};
    /** The targets for shading drawn beside the forward picture, as a share of it (the near radius's table). */
    private static final double TARGETS = 0.5;
    /** The light's depth a frame that draws it: one layer (LOW, MEDIUM) and both (HIGH, MAX). */
    private static final double[] LIGHT_MS = {0.17, 0.17, 0.49, 0.49};
    /**
     * Indirect light's rays a frame, by the trace's own measurement at the top state (rays, f1154a0): MEDIUM's
     * 65,536 rays every frame at 256 blocks; a quarter of HIGH's 524,288 a frame at 256 blocks; MAX's two cascades
     * of 1,048,576, the near at 64 blocks and the far at 256, a quarter a frame.
     */
    private static final double[] INDIRECT_MS = {0, 0.37, 0.67, 1.7};
    /** MAX's far shadows: 2,097,152 rays toward a low sun at 256 blocks, 2.28 ms a million; an upper bound. */
    private static final double FAR_SHADOWS_MS = 4.78;

    private TierCalibrationTable() {
    }

    static TierCalibration current() {
        Reference reference = new Reference("RTX 3060 12 GB, driver 610.88", 347.9, 350.0, List.of(
            new TerrainLine(0.9216, 0.0621, 0.2629),
            new TerrainLine(2.0736, 0.0969, 0.2886),
            new TerrainLine(3.6864, 0.1353, 0.3301),
            new TerrainLine(8.2944, 0.1868, 0.8668)),
            -0.2486, 0.1257, 0.0528, 0.376);
        List<List<Cost>> costs = List.of(tier(Tier.LOW), tier(Tier.MEDIUM), tier(Tier.HIGH), tier(Tier.MAX));
        // Near terrain's arenas at the tier's radius, distant terrain's reach, the light's layers with their
        // scratch, the light scene's lookup and bricks (and for MAX its states within radius 12, sized); the
        // targets for shading and a stage's own at the render resolution, a history at the output's.
        List<Memory> memory = List.of(
            new Memory(128 + 40 + 32, 30.5, 15.3),
            new Memory(320 + 80 + 128 + 8.6, 30.5, 15.3),
            new Memory(448 + 141 + 256 + 15.0, 30.5, 15.3),
            new Memory(704 + 250 + 256 + 23.1 + 39, 30.5, 15.3));
        return new TierCalibration(true, reference, 7.0, 0.15, 0.05, costs, memory, DLSS);
    }

    /**
     * Own temporal anti-aliasing and the scaler (scaler, F2 at 0.67 of 2560 by 1440): the whole stage below the output
     * resolution, 1.386 ms there; at the output resolution its temporal pass, motion and depth copies, 0.41 ms.
     */
    private static final double SCALER_MS_A_MEGAPIXEL = 1.386 / 3.6864;
    private static final double TEMPORAL_MS_A_MEGAPIXEL = 0.41 / 3.6864;

    private static List<Cost> tier(Tier tier) {
        int t = tier.ordinal();
        double[] terrain = TERRAIN[t];
        List<Cost> costs = new java.util.ArrayList<>(List.of(
            new Cost("opaque terrain drawn forward", Probe.TERRAIN, terrain[0], terrain[1], terrain[2], 0),
            new Cost("the targets for shading", Probe.TERRAIN, TARGETS * terrain[0], TARGETS * terrain[1], TARGETS * terrain[2], 0),
            new Cost("the light's depth", Probe.LIGHT, LIGHT_MS[t], 0, 0),
            new Cost("shading", Probe.BANDWIDTH, 0, 0.19, 0),
            new Cost("the game's other drawing", Probe.BANDWIDTH, 0, 0.33, 0),
            TierBenchmark.renderScale(tier) < 1
                ? new Cost("own temporal anti-aliasing and the scaler", Probe.BANDWIDTH, 0, 0, SCALER_MS_A_MEGAPIXEL)
                : new Cost("own temporal anti-aliasing", Probe.BANDWIDTH, 0, 0, TEMPORAL_MS_A_MEGAPIXEL)));
        if (INDIRECT_MS[t] > 0) {
            costs.add(new Cost("indirect light's rays", Probe.TRACE, INDIRECT_MS[t], 0, 0));
        }
        if (tier == Tier.MAX) {
            costs.add(new Cost("far shadows' rays", Probe.TRACE, FAR_SHADOWS_MS, 0, 0));
        }
        return costs;
    }
}
