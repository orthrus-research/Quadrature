package cc.orthrus.quadrature.core.frame;

import java.util.Arrays;

/**
 * The most recent CPU and GPU time samples for each stage. Fixed-size and allocation-free while
 * recording; a summary copies and sorts, so it is for reporting, not for every frame.
 */
public final class StageStatistics {

    /** About two seconds of frames at the frame cap. */
    public static final int WINDOW = 256;

    private final long[][] cpu;
    private final long[][] gpu;
    private final int[] cpuCount;
    private final int[] gpuCount;

    public StageStatistics(int stageCount) {
        cpu = new long[stageCount][WINDOW];
        gpu = new long[stageCount][WINDOW];
        cpuCount = new int[stageCount];
        gpuCount = new int[stageCount];
    }

    public void cpuNanos(int stageIndex, long nanos) {
        cpu[stageIndex][cpuCount[stageIndex]++ % WINDOW] = nanos;
    }

    public void gpuNanos(int stageIndex, long nanos) {
        gpu[stageIndex][gpuCount[stageIndex]++ % WINDOW] = nanos;
    }

    public Summary cpu(int stageIndex) {
        return summarize(cpu[stageIndex], cpuCount[stageIndex]);
    }

    public Summary gpu(int stageIndex) {
        return summarize(gpu[stageIndex], gpuCount[stageIndex]);
    }

    private static Summary summarize(long[] samples, int recorded) {
        int count = Math.min(recorded, WINDOW);
        if (count == 0) {
            return Summary.EMPTY;
        }
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        long total = 0;
        for (long sample : sorted) {
            total += sample;
        }
        return new Summary(count, total / count, sorted[(count - 1) / 2], sorted[(count - 1) * 95 / 100],
            sorted[count - 1]);
    }

    /** Times in nanoseconds over the samples in the window. */
    public record Summary(int samples, long mean, long median, long percentile95, long max) {
        static final Summary EMPTY = new Summary(0, 0, 0, 0, 0);
    }
}
