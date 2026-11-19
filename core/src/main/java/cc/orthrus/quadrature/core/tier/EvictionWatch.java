package cc.orthrus.quadrature.core.tier;

/**
 * The run-time rule of D22: where the driver keeps moving memory off the card while the frame's 1% lows
 * fall, the memory tier goes down one step. Fed one window at a time, a few seconds of frames each:
 * the driver's eviction count at the window's end and the window's 1% low in frames a second.
 *
 * <p>A window in which nothing was evicted sets the baseline, the 1% low the frame holds when memory is
 * not short. A step down is called for where evictions rose in each of the last {@code risingWindows}
 * windows and the latest 1% low is below the baseline by at least {@code fall}, as a share of it. After
 * a step the rule waits {@code settleWindows} windows, and the baseline is found again, since a lower
 * tier changes what the frame costs.
 */
public final class EvictionWatch {

    private final double fall;
    private final int risingWindows;
    private final int settleWindows;

    private long lastEvictions = -1;
    private int rising;
    private double baseline = Double.NaN;
    private int waiting;
    private int steps;

    /**
     * @param fall how far below the baseline the 1% low must be, as a share of the baseline
     * @param risingWindows how many windows in a row evictions must rise
     * @param settleWindows how many windows to wait after a step
     */
    public EvictionWatch(double fall, int risingWindows, int settleWindows) {
        this.fall = fall;
        this.risingWindows = risingWindows;
        this.settleWindows = settleWindows;
    }

    /**
     * Takes one window.
     *
     * @param evictions the driver's count of evictions since the system started, at the window's end
     * @param onePercentLow the window's 1% low in frames a second
     * @return whether the memory tier is to go down one step now
     */
    public boolean window(long evictions, double onePercentLow) {
        boolean rose = lastEvictions >= 0 && evictions > lastEvictions;
        boolean first = lastEvictions < 0;
        lastEvictions = evictions;
        if (waiting > 0) {
            waiting--;
            rising = 0;
            return false;
        }
        if (first) {
            return false;
        }
        if (!rose) {
            rising = 0;
            if (Double.isFinite(onePercentLow)) {
                // The best of the quiet windows: a quiet window that runs slower for another reason must not
                // lower the bar a shortage is judged by.
                baseline = Double.isNaN(baseline) ? onePercentLow : Math.max(baseline, onePercentLow);
            }
            return false;
        }
        rising++;
        if (rising >= risingWindows && Double.isFinite(baseline) && onePercentLow <= baseline * (1 - fall)) {
            steps++;
            rising = 0;
            waiting = settleWindows;
            baseline = Double.NaN;
            return true;
        }
        return false;
    }

    /** How many steps down the rule has called for. */
    public int steps() {
        return steps;
    }

    /** The 1% low quiet windows have held, or not a number before the first. */
    public double baseline() {
        return baseline;
    }
}
