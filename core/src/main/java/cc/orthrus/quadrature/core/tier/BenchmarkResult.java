package cc.orthrus.quadrature.core.tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One run of the benchmark reduced to the figures tiers are chosen by: medians over the kept
 * repetitions, with the spread of each so a run can be judged by itself. A probe that did not run is
 * not a number, or an empty list.
 *
 * @param smallGBs the bandwidth kernel over the small working set, in GB a second
 * @param largeGBs over the large working set
 * @param smallSpread the range of the small set's repetitions as a share of their median
 * @param largeSpread the same for the large set
 * @param terrain the opaque terrain probe at each resolution, fewest pixels first
 * @param terrainFit its time split between the geometry and the fragments
 * @param lightMs the light view's box drawn whole, median
 * @param lightSpread its range as a share of the median
 * @param lightPrimitives the primitives it produced
 * @param lightFragments the samples that passed, median
 * @param trace the indirect trace at each resolution, empty where the trace probe is absent
 * @param traceGigaRays the trace's rays a nanosecond, which is billions a second, over every repetition
 */
public record BenchmarkResult(int version, int outputWidth, int outputHeight, double smallGBs, double largeGBs,
                              double smallSpread, double largeSpread, List<TerrainPoint> terrain, TerrainFit terrainFit,
                              double lightMs, double lightSpread, long lightPrimitives, double lightFragments,
                              List<TracePoint> trace, double traceGigaRays) {

    /** The terrain probe at one resolution. */
    public record TerrainPoint(int width, int height, double medianMs, double spread, double fragments, long primitives) {
    }

    /** The trace probe at one resolution. */
    public record TracePoint(int width, int height, double medianMs, double spread, long rays) {
    }

    public BenchmarkResult {
        terrain = List.copyOf(terrain);
        trace = List.copyOf(trace);
    }

    /** Whether the opaque terrain probe drew enough for the run to measure the card ({@link TierBenchmark#LEAST_PRIMITIVES}). */
    public boolean drewEnough() {
        return !terrain.isEmpty() && terrain.stream().mapToLong(TerrainPoint::primitives).min().orElse(0)
            >= TierBenchmark.LEAST_PRIMITIVES;
    }

    /** Nanoseconds a primitive of the light view's depth-only draw; not a number where it did not run. */
    public double lightNsPerPrimitive() {
        return lightPrimitives > 0 && Double.isFinite(lightMs) ? lightMs * 1e6 / lightPrimitives : Double.NaN;
    }

    /** Reduces a run. */
    public static BenchmarkResult of(BenchmarkRun run) {
        double[] small = gigabytes(run.bandwidth(), TierBenchmark.SMALL_BYTES);
        double[] large = gigabytes(run.bandwidth(), TierBenchmark.LARGE_BYTES);

        Map<Long, List<BenchmarkRun.Terrain>> byResolution = new TreeMap<>();
        for (BenchmarkRun.Terrain sample : run.terrain()) {
            byResolution.computeIfAbsent((long) sample.width() * sample.height() << 16 | sample.width(),
                key -> new ArrayList<>()).add(sample);
        }
        List<TerrainPoint> points = new ArrayList<>();
        List<Double> primitives = new ArrayList<>();
        for (List<BenchmarkRun.Terrain> samples : byResolution.values()) {
            double[] millis = new double[samples.size()];
            double[] fragments = new double[samples.size()];
            double[] produced = new double[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                millis[i] = samples.get(i).nanos() / 1e6;
                fragments[i] = samples.get(i).fragments();
                produced[i] = samples.get(i).primitives();
                primitives.add(produced[i]);
            }
            BenchmarkRun.Terrain first = samples.getFirst();
            points.add(new TerrainPoint(first.width(), first.height(), Samples.median(millis), Samples.spread(millis),
                Samples.median(fragments), Math.round(Samples.median(produced))));
        }
        double[] fitFragments = points.stream().mapToDouble(TerrainPoint::fragments).toArray();
        double[] fitMillis = points.stream().mapToDouble(TerrainPoint::medianMs).toArray();
        long drawn = primitives.isEmpty() ? 0
            : Math.round(Samples.median(primitives.stream().mapToDouble(Double::doubleValue).toArray()));
        TerrainFit fit = points.isEmpty() ? null : TerrainFit.fit(fitFragments, fitMillis, drawn);

        double[] lightMillis = run.light().stream().mapToDouble(sample -> sample.nanos() / 1e6).toArray();
        double[] lightProduced = run.light().stream().mapToDouble(BenchmarkRun.Light::primitives).toArray();
        double[] lightPassed = run.light().stream().mapToDouble(BenchmarkRun.Light::fragments).toArray();

        Map<Long, List<BenchmarkRun.Trace>> traceByResolution = new TreeMap<>();
        for (BenchmarkRun.Trace sample : run.trace()) {
            traceByResolution.computeIfAbsent((long) sample.width() * sample.height() << 16 | sample.width(),
                key -> new ArrayList<>()).add(sample);
        }
        List<TracePoint> tracePoints = new ArrayList<>();
        for (List<BenchmarkRun.Trace> samples : traceByResolution.values()) {
            double[] millis = samples.stream().mapToDouble(sample -> sample.nanos() / 1e6).toArray();
            BenchmarkRun.Trace first = samples.getFirst();
            tracePoints.add(new TracePoint(first.width(), first.height(), Samples.median(millis), Samples.spread(millis),
                first.rays()));
        }
        double[] rates = run.trace().stream().filter(sample -> sample.nanos() > 0)
            .mapToDouble(sample -> (double) sample.rays() / sample.nanos()).toArray();

        return new BenchmarkResult(TierBenchmark.VERSION, run.outputWidth(), run.outputHeight(), Samples.median(small),
            Samples.median(large), Samples.spread(small), Samples.spread(large), points, fit, Samples.median(lightMillis),
            Samples.spread(lightMillis), lightProduced.length == 0 ? 0 : Math.round(Samples.median(lightProduced)),
            lightPassed.length == 0 ? 0 : Samples.median(lightPassed),
            tracePoints, Samples.median(rates));
    }

    private static double[] gigabytes(List<BenchmarkRun.Bandwidth> samples, long workingSet) {
        return samples.stream().filter(sample -> sample.workingSet() == workingSet && sample.nanos() > 0)
            .mapToDouble(sample -> (double) sample.bytes() / sample.nanos()).toArray();
    }

    /** The result in lines for the log: every figure, its spread where it has one. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "benchmark %d, output %d by %d", version, outputWidth, outputHeight));
        lines.add(String.format(Locale.ROOT, "bandwidth: %.1f GB/s over %d MiB (spread %.1f%%), %.1f GB/s over %d MiB "
                + "(spread %.1f%%)", smallGBs, TierBenchmark.SMALL_BYTES >> 20, 100 * smallSpread, largeGBs,
            TierBenchmark.LARGE_BYTES >> 20, 100 * largeSpread));
        for (TerrainPoint point : terrain) {
            lines.add(String.format(Locale.ROOT, "opaque terrain at %d by %d: %.3f ms (spread %.1f%%), %d primitives, "
                    + "%.0f fragments", point.width, point.height, point.medianMs, 100 * point.spread, point.primitives,
                point.fragments));
        }
        if (terrainFit != null) {
            lines.add(String.format(Locale.ROOT, "opaque terrain fit over %d resolutions: %.3f ms for the geometry, "
                    + "%.4f ns a primitive; %.3f ms a million fragments", terrainFit.points(), terrainFit.interceptMs(),
                terrainFit.nsPerPrimitive(), terrainFit.msPerMegaFragment()));
        }
        lines.add(Double.isFinite(lightMs)
            ? String.format(Locale.ROOT, "light view, box drawn whole: %.3f ms (spread %.1f%%), %d primitives, %.0f fragments, "
                + "%.4f ns a primitive", lightMs, 100 * lightSpread, lightPrimitives, lightFragments, lightNsPerPrimitive())
            : "light view: not measured");
        for (TracePoint point : trace) {
            lines.add(String.format(Locale.ROOT, "indirect trace at %d by %d: %.3f ms (spread %.1f%%), %d rays",
                point.width, point.height, point.medianMs, 100 * point.spread, point.rays));
        }
        lines.add(Double.isFinite(traceGigaRays)
            ? String.format(Locale.ROOT, "indirect trace: %.3f billion rays a second", traceGigaRays)
            : "indirect trace: no probe");
        return lines;
    }
}
