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
     * DLSS taking the scaler's picture on the reference card, preset K in Performance mode, the whole step at P0
     * (VERIFICATION, DLSS taking the scaler's picture, and the scaler's costs): 2.653 ms at 2560 by 1440 output and
     * 5.482 ms at 3840 by 2160, the copies in, the round trip with NGX's work and the copy out. A line through the two:
     * 0.614 ms a million output pixels, NGX's work and the copies, and 0.390 ms fixed, the round trip's latency.
     */
    static final TierCalibration.Dlss DLSS = new TierCalibration.Dlss("Super Resolution, preset K, Performance",
        (5.482 - 2.653) / (8.2944 - 3.6864), 2.653 - (5.482 - 2.653) / (8.2944 - 3.6864) * 3.6864);

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
     * Indirect light's rays a frame, by the trace's own measurement of the walk as shipped, which reads no coarser
     * bits (VERIFICATION, rays through the scene; that run sampled no clocks, and its figures are taken as the top
     * state's): MEDIUM's 65,536 rays every frame at 256 blocks; a quarter of HIGH's 524,288 a frame at 256 blocks;
     * MAX's two cascades of 1,048,576, the near at 64 blocks and the far at 256, a quarter a frame.
     */
    private static final double[] INDIRECT_MS = {0, 0.527, 3.465 / 4, (4.892 + 12.170) / 2 / 4};
    /**
     * Distant terrain, the reach group, measured 2026-11-20 at reach 512 at 1280 by 720 and 2560 by 1440 on the forest
     * tour (VERIFICATION, distant terrain's cost): over 145 report windows, each its frames' mean faces against their
     * mean time, 0.646 ms, 0.999 ms a million faces and 0.233 ms a million render pixels. The faces each tier's reach
     * draws once its tiles are stored: 1.51 million at 256 (30 windows) and 3.51 million at 512 (two); 384's is taken
     * between them and 768's past them, by the 2 million each 256 blocks of reach added, until they are measured.
     */
    private static final double[] DISTANT_FACES_MILLIONS = {1.51, 2.51, 3.51, 5.51};
    private static final double[] DISTANT_LINE = {0.646, 0.999, 0.233};
    /** MAX's far shadows: 2,097,152 rays toward a low sun at 256 blocks, 2.59 ms a million; an upper bound. */
    private static final double FAR_SHADOWS_MS = 2.59 * 2.097152;

    /**
     * The trace probe's rate on the reference card, in billions of rays a second: 0.376 over the 128 runs, measured on
     * the walk that read the bricks' coarser bits, scaled to the walk as shipped by the trace's own times of the probe's
     * batch, MEDIUM's at 64 blocks: 0.180 ms with those bits against 0.222 without (VERIFICATION, rays through the
     * scene).
     */
    private static final double TRACE_GIGA_RAYS = 0.376 * 0.180 / 0.222;

    private TierCalibrationTable() {
    }

    static TierCalibration current() {
        Reference reference = new Reference("RTX 3060 12 GB, driver 610.88", 347.9, 350.0, List.of(
            new TerrainLine(0.9216, 0.0621, 0.2629),
            new TerrainLine(2.0736, 0.0969, 0.2886),
            new TerrainLine(3.6864, 0.1353, 0.3301),
            new TerrainLine(8.2944, 0.1868, 0.8668)),
            -0.2486, 0.1257, 0.0528, TRACE_GIGA_RAYS);
        List<List<Cost>> costs = List.of(tier(Tier.LOW, false), tier(Tier.MEDIUM, false), tier(Tier.HIGH, false),
            tier(Tier.MAX, false));
        List<List<Cost>> nativeCosts = List.of(tier(Tier.LOW, true), tier(Tier.MEDIUM, true), tier(Tier.HIGH, true),
            tier(Tier.MAX, true));
        // Near terrain's arenas at the tier's radius, distant terrain's reach, the light's layers with their
        // scratch, the light scene's lookup and bricks (and for MAX its states within radius 12, sized); the
        // targets for shading and a stage's own at the render resolution, a history at the output's.
        List<Memory> memory = List.of(
            new Memory(128 + 40 + 32, 30.5, 15.3),
            new Memory(320 + 80 + 128 + 8.6, 30.5, 15.3),
            new Memory(448 + 141 + 256 + 15.0, 30.5, 15.3),
            new Memory(704 + 250 + 256 + 23.1 + 39, 30.5, 15.3));
        return new TierCalibration(true, reference, 7.0, 0.15, 0.05, costs, memory, DLSS, nativeCosts);
    }

    /**
     * Own temporal anti-aliasing and the scaler on the reference card at P0, measured at 2560 by 1440 and 3840 by
     * 2160 output on one instance (VERIFICATION, the scaler's costs): its two depth copies, motion, the temporal
     * pass, the scale (at the output's size, the copy), the barrier and the depth's stretch, and the 0.01 ms
     * between them. LOW at 0.67 takes 0.982 and 1.520 ms, MEDIUM at 0.77 0.972 and 1.781 ms, and the temporal pass
     * alone at the output's size, HIGH and MAX, 0.957 and 2.010 ms. For two and a quarter times the pixels they take
     * 1.55, 1.83 and 2.10 times as long, not in proportion, so each is a line through its two points: a fixed part and
     * one a million output pixels.
     */
    private static final double[][] SCALER_POINTS_MS = {{0.982, 1.520}, {0.972, 1.781}};
    private static final double[] TEMPORAL_POINTS_MS = {0.957, 2.010};
    private static final double MEASURED_LOW_MP = 3.6864;
    private static final double MEASURED_HIGH_MP = 8.2944;

    /** A line through a cost's two measured points: {fixed ms, ms a million output pixels}. */
    static double[] line(double[] points) {
        double slope = (points[1] - points[0]) / (MEASURED_HIGH_MP - MEASURED_LOW_MP);
        return new double[]{points[0] - slope * MEASURED_LOW_MP, slope};
    }

    private static Cost scaled(String name, double[] points) {
        double[] line = line(points);
        return new Cost(name, Probe.BANDWIDTH, line[0], 0, 0, line[1]);
    }

    /** @param drawnNatively the tier's groups drawn at the output's resolution, with the temporal pass alone */
    private static List<Cost> tier(Tier tier, boolean drawnNatively) {
        int t = tier.ordinal();
        double[] terrain = TERRAIN[t];
        List<Cost> costs = new java.util.ArrayList<>(List.of(
            new Cost("opaque terrain drawn forward", Probe.TERRAIN, terrain[0], terrain[1], terrain[2], 0),
            new Cost("the targets for shading", Probe.TERRAIN, TARGETS * terrain[0], TARGETS * terrain[1], TARGETS * terrain[2], 0),
            new Cost("the light's depth", Probe.LIGHT, LIGHT_MS[t], 0, 0),
            // Its cost a primitive is about three times opaque terrain's, but the terrain probe is the nearest stand-in
            // for how much faster a machine draws it.
            new Cost("distant terrain", Probe.TERRAIN, DISTANT_LINE[0] + DISTANT_LINE[1] * DISTANT_FACES_MILLIONS[t],
                DISTANT_LINE[2], 0),
            new Cost("shading", Probe.BANDWIDTH, 0, 0.19, 0),
            new Cost("the game's other drawing", Probe.BANDWIDTH, 0, 0.33, 0),
            TierBenchmark.renderScale(tier) < 1 && !drawnNatively
                ? scaled("own temporal anti-aliasing and the scaler", SCALER_POINTS_MS[t])
                : scaled("own temporal anti-aliasing", TEMPORAL_POINTS_MS)));
        if (INDIRECT_MS[t] > 0) {
            costs.add(new Cost("indirect light's rays", Probe.TRACE, INDIRECT_MS[t], 0, 0));
        }
        if (tier == Tier.MAX) {
            costs.add(new Cost("far shadows' rays", Probe.TRACE, FAR_SHADOWS_MS, 0, 0));
        }
        return costs;
    }
}
