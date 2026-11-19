package cc.orthrus.quadrature.core.tier;

/**
 * Whether the GPU's clock has settled before the benchmark times anything. A GPU sets its clock by its
 * load: under a light frame an RTX 3060 runs at about 1,300 MHz and reaches 1,950 only once loaded, and a
 * probe timed during the climb reads up to a quarter slow. The settling load's time follows the shader
 * clock alone, so the clock has settled once the load's last few times agree.
 *
 * <p>Fed the load's times in the order they were issued, as the driver makes them ready.
 */
public final class ClockSettle {

    /** How many of the latest times must agree. */
    public static final int AGREEING = 4;
    /** By how much at most, as a share of the shortest. */
    public static final double AGREEMENT = 0.02;
    /** The fewest times before any may count as settled: the climb takes over a second. */
    public static final int LEAST = 12;

    private final double[] latest = new double[AGREEING];
    private int count;

    /** Takes one time of the settling load, in ms. */
    public void time(double millis) {
        latest[count % AGREEING] = millis;
        count++;
    }

    /** Whether the latest times agree, and enough have been taken. */
    public boolean settled() {
        if (count < Math.max(LEAST, AGREEING)) {
            return false;
        }
        double least = Double.POSITIVE_INFINITY;
        double most = 0;
        for (double millis : latest) {
            least = Math.min(least, millis);
            most = Math.max(most, millis);
        }
        return least > 0 && (most - least) / least <= AGREEMENT;
    }

    /** How many times were taken. */
    public int count() {
        return count;
    }

    /** The median of the latest times, or not a number before any. */
    public double latestMedian() {
        if (count == 0) {
            return Double.NaN;
        }
        double[] kept = new double[Math.min(count, AGREEING)];
        System.arraycopy(latest, 0, kept, 0, kept.length);
        return Samples.median(kept);
    }
}
