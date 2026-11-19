package cc.orthrus.quadrature.core.tier;

import java.util.Arrays;

/** Order statistics over a probe's repetitions. Nothing here allocates more than a sorted copy. */
public final class Samples {

    private Samples() {
    }

    /** The median: the mean of the two middle values for an even count; not a number for none. */
    public static double median(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    public static double min(double[] values) {
        return values.length == 0 ? Double.NaN : Arrays.stream(values).min().getAsDouble();
    }

    public static double max(double[] values) {
        return values.length == 0 ? Double.NaN : Arrays.stream(values).max().getAsDouble();
    }

    /** The range as a share of the median: (max - min) / median. Not a number for none or a median of 0. */
    public static double spread(double[] values) {
        double median = median(values);
        if (values.length == 0 || median == 0) {
            return Double.NaN;
        }
        return (max(values) - min(values)) / median;
    }

    /** The geometric mean of the values that are positive and finite, weighted; not a number if none is. */
    public static double geometricMean(double[] values, double[] weights) {
        double logs = 0;
        double total = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > 0 && Double.isFinite(values[i]) && weights[i] > 0) {
                logs += weights[i] * Math.log(values[i]);
                total += weights[i];
            }
        }
        return total == 0 ? Double.NaN : Math.exp(logs / total);
    }
}
