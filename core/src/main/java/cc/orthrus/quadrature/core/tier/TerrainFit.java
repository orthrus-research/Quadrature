package cc.orthrus.quadrature.core.tier;

/**
 * Opaque terrain's time on one machine as a part that follows the geometry and a part that follows
 * the fragments: {@code ms = intercept + perMegaFragment * fragments / 1e6}, fitted over the probe's
 * resolutions in one view. The view fixes the geometry, so the intercept is the geometry's cost, and
 * divided by the primitives drawn it is a figure of the machine rather than of the view.
 *
 * @param interceptMs the time the fit gives for no fragments
 * @param msPerMegaFragment the time a million fragments that pass the depth test add
 * @param primitives the primitives the probe's draws produced, the same at every resolution
 * @param points how many resolutions the fit was made over
 */
public record TerrainFit(double interceptMs, double msPerMegaFragment, long primitives, int points) {

    /** Nanoseconds a primitive: the intercept over the primitives drawn; not a number where none were. */
    public double nsPerPrimitive() {
        return primitives > 0 ? interceptMs * 1e6 / primitives : Double.NaN;
    }

    /** Nanoseconds a fragment that passed. */
    public double nsPerFragment() {
        return msPerMegaFragment;
    }

    /** The time this machine would take for a view of {@code primitives} and {@code fragments}, in ms. */
    public double predictMs(double primitives, double fragments) {
        return nsPerPrimitive() * primitives / 1e6 + msPerMegaFragment * fragments / 1e6;
    }

    /**
     * Fits a line by least squares through one point per resolution: the median fragments and the
     * median time of its repetitions. Neither part may come out negative: where one would, the other
     * takes all the time (a line through the origin, or a flat one at the mean). With one point there is
     * no slope to find, and the time is put to the fragments.
     *
     * @param fragments the median fragments at each resolution
     * @param millis the median time at each resolution
     */
    public static TerrainFit fit(double[] fragments, double[] millis, long primitives) {
        int n = fragments.length;
        if (n == 0) {
            return new TerrainFit(Double.NaN, Double.NaN, primitives, 0);
        }
        double meanX = 0;
        double meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += fragments[i] / 1e6;
            meanY += millis[i];
        }
        meanX /= n;
        meanY /= n;
        double sxx = 0;
        double sxy = 0;
        double sx2 = 0;
        double sxyOrigin = 0;
        for (int i = 0; i < n; i++) {
            double x = fragments[i] / 1e6;
            sxx += (x - meanX) * (x - meanX);
            sxy += (x - meanX) * (millis[i] - meanY);
            sx2 += x * x;
            sxyOrigin += x * millis[i];
        }
        if (n == 1 || sxx == 0) {
            return new TerrainFit(0, sx2 == 0 ? 0 : sxyOrigin / sx2, primitives, n);
        }
        double slope = sxy / sxx;
        double intercept = meanY - slope * meanX;
        if (slope < 0) {
            return new TerrainFit(meanY, 0, primitives, n);
        }
        if (intercept < 0) {
            return new TerrainFit(0, sxyOrigin / sx2, primitives, n);
        }
        return new TerrainFit(intercept, slope, primitives, n);
    }
}
