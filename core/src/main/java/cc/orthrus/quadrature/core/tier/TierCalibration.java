package cc.orthrus.quadrature.core.tier;

import java.util.List;

/**
 * What a benchmark result is scored against: the reference card's own figures, what each tier's
 * groups cost on it, the frame budget a tier must fit, and the memory each tier holds. A machine's
 * cost for a tier is the reference's, group by group, divided by how much faster than the reference the
 * probe standing for that group ran. The figures are measured on the development card; see
 * ARCHITECTURE section 12.1.1.
 *
 * @param calibrated whether the figures are measured; the first-launch path stays off until they are
 * @param reference the reference card's probe figures
 * @param budgetMs the GPU time a tier's groups may take a frame on the machine
 * @param band the hysteresis band at each speed boundary, as a share of the budget
 * @param memoryBand the band at each memory boundary, as a share of the memory available
 * @param costs each tier's groups on the reference card, lowest tier first
 * @param memory the GPU memory each tier holds, lowest tier first
 * @param dlss what DLSS costs on the reference card, for the room D21 asks the benchmark to find
 */
public record TierCalibration(boolean calibrated, Reference reference, double budgetMs, double band, double memoryBand,
                              List<List<Cost>> costs, List<Memory> memory, Dlss dlss) {

    /** Which probe's speed scales a group's cost from the reference card to a machine. */
    public enum Probe { TERRAIN, LIGHT, TRACE, BANDWIDTH }

    /**
     * DLSS's cost on the reference card above the tier it is offered on (D21): its evaluation, which follows
     * the output's pixels, and the handoff of the frame's images to Vulkan and back, which is not a number
     * until it is measured and is then counted.
     */
    public record Dlss(String what, double msPerOutputMegapixel, double handoffMs) {

        public double ms(double outputMegapixels) {
            return msPerOutputMegapixel * outputMegapixels + (Double.isFinite(handoffMs) ? handoffMs : 0);
        }
    }

    /**
     * One group's GPU time on the reference card at a tier's settings: a part fixed by the settings, parts
     * that follow the pixels drawn at the render resolution and their square, since opaque terrain's time
     * rises faster than its pixels toward 3840 by 2160, and one that follows the output's.
     */
    public record Cost(String name, Probe probe, double fixedMs, double msPerRenderMegapixel,
                       double msPerRenderMegapixelSquared, double msPerOutputMegapixel) {

        /** A cost that follows the pixels in proportion. */
        public Cost(String name, Probe probe, double fixedMs, double msPerRenderMegapixel, double msPerOutputMegapixel) {
            this(name, probe, fixedMs, msPerRenderMegapixel, 0, msPerOutputMegapixel);
        }

        public double ms(double renderMegapixels, double outputMegapixels) {
            return fixedMs + msPerRenderMegapixel * renderMegapixels
                + msPerRenderMegapixelSquared * renderMegapixels * renderMegapixels + msPerOutputMegapixel * outputMegapixels;
        }
    }

    /** The GPU memory a tier holds, in MiB: fixed, and following the render and output pixels. */
    public record Memory(double fixedMiB, double perRenderMegapixelMiB, double perOutputMegapixelMiB) {

        public double mib(double renderMegapixels, double outputMegapixels) {
            return fixedMiB + perRenderMegapixelMiB * renderMegapixels + perOutputMegapixelMiB * outputMegapixels;
        }
    }

    /**
     * The reference card's figures. The terrain and light probes draw whatever the player's view holds, so
     * each is a model of the reference card fitted over the calibration's places, which says what the
     * reference would have taken for any view: a machine's ratio is that over what it took.
     *
     * @param smallGBs the bandwidth kernel over the small working set, median
     * @param largeGBs over the large set
     * @param terrainMsPerMegaPrimitive opaque terrain's time a million primitives drawn
     * @param terrainMsPerMegapixel and a million pixels of the target
     * @param lightMsPerMegaPrimitive the light view's box drawn whole, a million primitives
     * @param lightMsPerMegaFragment and a million fragments that passed
     * @param traceGigaRays the indirect trace's billions of rays a second, or not a number before it is measured
     */
    public record Reference(String card, double smallGBs, double largeGBs, List<TerrainLine> terrain,
                            double lightFixedMs, double lightMsPerMegaPrimitive, double lightMsPerMegaFragment,
                            double traceGigaRays) {

        public Reference {
            terrain = List.copyOf(terrain);
        }

        /**
         * What the reference card would take to draw a view's terrain at a resolution, in ms: its lines at the
         * resolutions either side, mixed by pixels; below the first line its fixed part shrinks with the pixels,
         * and above the last the last two lines go on as they run. Not a number without lines.
         */
        public double terrainMs(long primitives, int width, int height) {
            if (terrain.isEmpty()) {
                return Double.NaN;
            }
            double megapixels = width * (double) height / 1e6;
            double megaPrimitives = primitives / 1e6;
            TerrainLine first = terrain.getFirst();
            if (terrain.size() == 1 || megapixels <= first.megapixels()) {
                return first.fixedMs() * Math.min(1, megapixels / first.megapixels()) + first.msPerMegaPrimitive() * megaPrimitives;
            }
            int upper = 1;
            while (upper < terrain.size() - 1 && terrain.get(upper).megapixels() < megapixels) {
                upper++;
            }
            TerrainLine low = terrain.get(upper - 1);
            TerrainLine high = terrain.get(upper);
            double share = (megapixels - low.megapixels()) / (high.megapixels() - low.megapixels());
            return low.ms(megaPrimitives) + share * (high.ms(megaPrimitives) - low.ms(megaPrimitives));
        }

        /** What the reference card would take to draw a light box whole, in ms. */
        public double lightMs(long primitives, double fragments) {
            return lightFixedMs + lightMsPerMegaPrimitive * primitives / 1e6 + lightMsPerMegaFragment * fragments / 1e6;
        }
    }

    /**
     * The reference card's opaque terrain at one resolution, fitted over the calibration's places: a part
     * that the resolution fixes and a part a million primitives.
     */
    public record TerrainLine(double megapixels, double fixedMs, double msPerMegaPrimitive) {

        public double ms(double megaPrimitives) {
            return fixedMs + msPerMegaPrimitive * megaPrimitives;
        }
    }

    public TierCalibration {
        if (costs.size() != Tier.values().length || memory.size() != Tier.values().length) {
            throw new IllegalArgumentException("a cost list and a memory figure for every tier");
        }
        costs = costs.stream().map(List::copyOf).toList();
        memory = List.copyOf(memory);
    }

    /** A tier's groups on the reference card at an output resolution, in ms. */
    public double referenceMs(Tier tier, int outputWidth, int outputHeight) {
        double output = (double) outputWidth * outputHeight / 1e6;
        double scale = TierBenchmark.renderScale(tier);
        double render = output * scale * scale;
        double total = 0;
        for (Cost cost : costs.get(tier.ordinal())) {
            total += cost.ms(render, output);
        }
        return total;
    }

    /** The memory a tier holds at an output resolution, in MiB. */
    public double needMiB(Tier tier, int outputWidth, int outputHeight) {
        double output = (double) outputWidth * outputHeight / 1e6;
        double scale = TierBenchmark.renderScale(tier);
        return memory.get(tier.ordinal()).mib(output * scale * scale, output);
    }

    /**
     * The calibration in force. Measured on an RTX 3060 12 GB, driver 610.88, 2026-11-18 to 19; the
     * runs and the derivation of every figure are in ARCHITECTURE 12.1.1 and VERIFICATION, the tier
     * benchmark.
     */
    public static final TierCalibration CURRENT = TierCalibrationTable.current();
}
