package cc.orthrus.quadrature.core.handoff;

import java.util.Arrays;

/**
 * A growing list of measurements in nanoseconds, summarized on demand. Percentiles are taken by nearest
 * rank, the element at {@code ceil(p n) - 1} of the values sorted, as the frame tail takes its 95th.
 */
public final class Samples {

    /** A summary of the values; every time in nanoseconds. */
    public record Summary(int count, double mean, long min, long median, long p95, long p99, long max) {

        /** No values. */
        public static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0, 0);

        /** The summary in milliseconds, for a log line. */
        public String millis() {
            if (count == 0) {
                return "no samples";
            }
            return String.format(java.util.Locale.ROOT, "mean %.3f, median %.3f, p95 %.3f, p99 %.3f, max %.3f ms (n=%d)",
                mean / 1e6, median / 1e6, p95 / 1e6, p99 / 1e6, max / 1e6, count);
        }
    }

    private long[] values = new long[256];
    private int count;

    public void add(long value) {
        if (count == values.length) {
            values = Arrays.copyOf(values, count * 2);
        }
        values[count++] = value;
    }

    public int count() {
        return count;
    }

    public void clear() {
        count = 0;
    }

    public Summary summary() {
        if (count == 0) {
            return Summary.EMPTY;
        }
        long[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        double sum = 0;
        for (long value : sorted) {
            sum += value;
        }
        return new Summary(count, sum / count, sorted[0], rank(sorted, 0.5), rank(sorted, 0.95), rank(sorted, 0.99),
            sorted[count - 1]);
    }

    /** The nearest-rank percentile of sorted values. */
    static long rank(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
