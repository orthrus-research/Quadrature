package cc.orthrus.quadrature.core.tier;

import java.util.ArrayList;
import java.util.List;

/**
 * What one run of the benchmark measured, sample by sample, as the host read it from its queries.
 * Only kept repetitions are added; warm-up repetitions never reach this. {@link BenchmarkResult#of}
 * reduces it.
 */
public final class BenchmarkRun {

    /** One timed pass of the bandwidth kernel: {@code bytes} read from a working set of {@code workingSet}. */
    public record Bandwidth(long workingSet, long bytes, long nanos) {
    }

    /**
     * One repetition of the opaque terrain probe: the frame's opaque layers drawn again into a target of
     * {@code width} by {@code height}, with the primitives the draws produced and the samples that passed.
     */
    public record Terrain(int width, int height, long nanos, long primitives, long fragments) {
    }

    /** One repetition of the light view's box drawn whole, with the primitives it produced and the samples that passed. */
    public record Light(long nanos, long primitives, long fragments) {
    }

    /** One repetition of the indirect trace at a render resolution: {@code rays} traced in {@code nanos}. */
    public record Trace(int width, int height, long nanos, long rays) {
    }

    private final int outputWidth;
    private final int outputHeight;
    private final List<Bandwidth> bandwidth = new ArrayList<>();
    private final List<Terrain> terrain = new ArrayList<>();
    private final List<Light> light = new ArrayList<>();
    private final List<Trace> trace = new ArrayList<>();

    /** A run for a player whose picture is {@code outputWidth} by {@code outputHeight}. */
    public BenchmarkRun(int outputWidth, int outputHeight) {
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
    }

    public void add(Bandwidth sample) {
        bandwidth.add(sample);
    }

    public void add(Terrain sample) {
        terrain.add(sample);
    }

    public void add(Light sample) {
        light.add(sample);
    }

    public void add(Trace sample) {
        trace.add(sample);
    }

    public int outputWidth() {
        return outputWidth;
    }

    public int outputHeight() {
        return outputHeight;
    }

    public List<Bandwidth> bandwidth() {
        return List.copyOf(bandwidth);
    }

    public List<Terrain> terrain() {
        return List.copyOf(terrain);
    }

    public List<Light> light() {
        return List.copyOf(light);
    }

    public List<Trace> trace() {
        return List.copyOf(trace);
    }
}
