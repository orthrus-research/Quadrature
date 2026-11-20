package cc.orthrus.quadrature.core.tier;

import cc.orthrus.quadrature.core.tier.TierCalibration.Cost;
import cc.orthrus.quadrature.core.tier.TierCalibration.Probe;
import cc.orthrus.quadrature.core.tier.TierCalibration.Reference;

import java.util.Arrays;

/**
 * Chooses the speed tier and the memory tier from a benchmark result (D22). For each tier a fit is
 * worked out, budget over predicted cost for speed and memory available over memory held for memory, and
 * the tier is the highest that fits, with a band at each boundary: a tier above the last one chosen is
 * taken only once it fits by the band, and the last one is left only once it misses by the band.
 */
public final class TierSelection {

    /**
     * How far the trace's ratio may stand from the geometric mean of the other probes', as a factor either way,
     * before the run chooses nothing. Over the calibration's 128 runs a sound trace stood within 1.29 times of the
     * others (a standard deviation of 1.14 times, in the logarithm), so two is past five of those; the runs that
     * timed the light scene's copy before it caught up stood 10.6 to 12.7 times above, and one 3.5 times below.
     */
    public static final double TRACE_LEEWAY = 2;

    private TierSelection() {
    }

    /**
     * How many times faster than the reference card the machine ran each probe; not a number where a
     * probe did not run. {@code fallback} is the geometric mean of those that did, which stands in for a
     * missing one.
     */
    public record Ratios(double terrain, double light, double trace, double bandwidth, double fallback) {

        /** The ratio that scales a group, or the fallback where its own probe did not run. */
        public double of(Probe probe) {
            double own = switch (probe) {
                case TERRAIN -> terrain;
                case LIGHT -> light;
                case TRACE -> trace;
                case BANDWIDTH -> bandwidth;
            };
            return own > 0 && Double.isFinite(own) ? own : fallback;
        }

        /**
         * How far the trace's ratio stands from the geometric mean of the other probes', as a factor of one or more;
         * not a number where the trace or every other probe did not run.
         */
        public double traceApart() {
            double others = Samples.geometricMean(new double[] {terrain, light, bandwidth}, new double[] {1, 1, 1});
            if (!(trace > 0 && Double.isFinite(trace) && others > 0 && Double.isFinite(others))) {
                return Double.NaN;
            }
            return Math.max(trace / others, others / trace);
        }
    }

    /**
     * A choice and everything it was made from.
     *
     * @param score the machine's speed against the reference card's, weighted by each probe's share of the
     *     reference's HIGH tier at the result's output
     * @param predictedMs each tier's groups on this machine at the result's output, lowest tier first
     * @param speedFits the budget over each predicted cost
     * @param plainSpeed the speed tier without the band
     * @param needMiB each tier's memory at the result's output
     * @param memoryFits the memory available over each need
     * @param dlssMs DLSS's cost on this machine at the output: the reference's over the fallback ratio, since no
     *     probe measures what DLSS runs on
     * @param dlssFit the budget over the speed tier's cost with DLSS added
     * @param scales each tier's render scale: its own, or 1 where drawing natively costs no more (D23)
     */
    public record Outcome(Ratios ratios, double score, double[] predictedMs, double[] speedFits, Tier plainSpeed,
                          Tier speed, double[] needMiB, double availableMiB, double[] memoryFits, Tier plainMemory,
                          Tier memory, double dlssMs, double dlssFit, double[] scales) {

        /** Whether the speed tier leaves room for DLSS above it (D21). */
        public boolean dlssOffered() {
            return dlssFit >= 1;
        }

        /**
         * Whether the trace agrees with the other probes within {@link #TRACE_LEEWAY}, or did not run. A run whose
         * trace stands further off measured something other than the card, and chooses nothing.
         */
        public boolean traceAgrees() {
            return !(ratios.traceApart() > TRACE_LEEWAY);
        }
    }

    /**
     * Each probe's ratio. Terrain's is what the reference card's model says it would have taken at the run's
     * resolutions, summed, over what this card took; the light's the same for its one box.
     */
    public static Ratios ratios(BenchmarkResult result, Reference reference) {
        double terrain = Double.NaN;
        double predicted = 0;
        double measured = 0;
        for (BenchmarkResult.TerrainPoint point : result.terrain()) {
            if (point.primitives() > 0 && point.medianMs() > 0) {
                predicted += reference.terrainMs(point.primitives(), point.width(), point.height());
                measured += point.medianMs();
            }
        }
        if (measured > 0) {
            terrain = predicted / measured;
        }
        // The light's model has a constant below zero, so a box far smaller than any the calibration met says nothing.
        double lightReference = result.lightPrimitives() > 0 && result.lightMs() > 0
            ? reference.lightMs(result.lightPrimitives(), result.lightFragments()) : Double.NaN;
        double light = lightReference > 0.1 * result.lightMs() ? lightReference / result.lightMs() : Double.NaN;
        double trace = result.traceGigaRays() / reference.traceGigaRays();
        double bandwidth = Samples.geometricMean(
            new double[] {result.smallGBs() / reference.smallGBs(), result.largeGBs() / reference.largeGBs()},
            new double[] {1, 1});
        // The trace is kept as measured: one far from the others turns the run away (Outcome.traceAgrees).
        double fallback = Samples.geometricMean(new double[] {terrain, light, trace, bandwidth},
            new double[] {1, 1, 1, 1});
        return new Ratios(terrain, light, trace, bandwidth, fallback);
    }

    /**
     * Each tier's groups on the machine at an output resolution, in ms, and the render scale they are drawn at. A tier
     * whose own scale is below 1 is priced at that scale, the scaler included, and drawn natively, the temporal pass
     * alone; it renders below the output only where that costs less (D23), which it does once the work that follows the
     * pixels outweighs the scaler's own cost.
     */
    public record Priced(double[] ms, double[] scales) {
    }

    public static Priced priced(Ratios ratios, TierCalibration calibration, int outputWidth, int outputHeight) {
        double output = (double) outputWidth * outputHeight / 1e6;
        double[] predicted = new double[Tier.values().length];
        double[] scales = new double[Tier.values().length];
        for (Tier tier : Tier.values()) {
            double scale = TierBenchmark.renderScale(tier);
            double below = cost(ratios, calibration.costs().get(tier.ordinal()), output * scale * scale, output);
            predicted[tier.ordinal()] = below;
            scales[tier.ordinal()] = scale;
            if (scale < 1) {
                double natively = cost(ratios, calibration.nativeCosts().get(tier.ordinal()), output, output);
                if (natively <= below) {
                    predicted[tier.ordinal()] = natively;
                    scales[tier.ordinal()] = 1;
                }
            }
        }
        return new Priced(predicted, scales);
    }

    private static double cost(Ratios ratios, java.util.List<Cost> costs, double render, double output) {
        double total = 0;
        for (Cost cost : costs) {
            total += cost.ms(render, output) / ratios.of(cost.probe());
        }
        return total;
    }

    /** Each tier's groups on the machine at an output resolution, in ms, at the scale {@link #priced} chooses. */
    public static double[] predictedMs(Ratios ratios, TierCalibration calibration, int outputWidth, int outputHeight) {
        return priced(ratios, calibration, outputWidth, outputHeight).ms();
    }

    /**
     * The machine's speed as one number: the geometric mean of the ratios, each weighted by its probe's share
     * of the reference card's HIGH tier at this output. A probe that did not run gives its weight to the rest.
     */
    public static double score(Ratios ratios, TierCalibration calibration, int outputWidth, int outputHeight) {
        double output = (double) outputWidth * outputHeight / 1e6;
        double[] weights = new double[Probe.values().length];
        for (Cost cost : calibration.costs().get(Tier.HIGH.ordinal())) {
            weights[cost.probe().ordinal()] += cost.ms(output, output);
        }
        double[] values = {ratios.terrain(), ratios.light(), ratios.trace(), ratios.bandwidth()};
        return Samples.geometricMean(values, weights);
    }

    /**
     * The highest tier whose fit is at least 1, with the band: from {@code previous}, a higher tier is taken
     * only where its fit is at least {@code 1 + band}, and {@code previous} is left for a lower one only where
     * its own fit is below {@code 1 - band}. Without a previous tier, or where none fits, the plain choice;
     * the lowest tier where nothing fits at all.
     */
    public static Tier withBand(double[] fits, Tier previous, double band) {
        Tier plain = highestFitting(fits, 1.0, Tier.MAX);
        if (previous == null) {
            return plain;
        }
        for (int t = fits.length - 1; t > previous.ordinal(); t--) {
            if (fits[t] >= 1 + band) {
                return Tier.values()[t];
            }
        }
        if (fits[previous.ordinal()] < 1 - band) {
            return highestFitting(fits, 1.0, previous.down());
        }
        return previous;
    }

    private static Tier highestFitting(double[] fits, double least, Tier highest) {
        for (int t = highest.ordinal(); t > 0; t--) {
            if (fits[t] >= least) {
                return Tier.values()[t];
            }
        }
        return Tier.LOW;
    }

    /**
     * Chooses both tiers.
     *
     * @param availableMiB the GPU memory Quadrature may hold, as the host works it out from the driver's figures
     * @param previousSpeed the speed tier chosen last on this GPU, or null
     * @param previousMemory the memory tier chosen last on this GPU, or null
     */
    public static Outcome choose(BenchmarkResult result, TierCalibration calibration, double availableMiB,
                                 Tier previousSpeed, Tier previousMemory) {
        int width = result.outputWidth();
        int height = result.outputHeight();
        Ratios ratios = ratios(result, calibration.reference());
        Priced priced = priced(ratios, calibration, width, height);
        double[] predicted = priced.ms();
        double[] speedFits = Arrays.stream(predicted).map(ms -> calibration.budgetMs() / ms).toArray();
        double[] need = new double[Tier.values().length];
        double[] memoryFits = new double[need.length];
        for (Tier tier : Tier.values()) {
            need[tier.ordinal()] = calibration.needMiB(tier, width, height, priced.scales()[tier.ordinal()]);
            memoryFits[tier.ordinal()] = availableMiB / need[tier.ordinal()];
        }
        Tier speed = withBand(speedFits, previousSpeed, calibration.band());
        double dlss = calibration.dlss().ms((double) width * height / 1e6) / ratios.fallback();
        double dlssFit = calibration.budgetMs() / (predicted[speed.ordinal()] + dlss);
        return new Outcome(ratios, score(ratios, calibration, width, height), predicted, speedFits,
            withBand(speedFits, null, 0), speed, need, availableMiB, memoryFits, withBand(memoryFits, null, 0),
            withBand(memoryFits, previousMemory, calibration.memoryBand()), dlss, dlssFit, priced.scales());
    }
}
