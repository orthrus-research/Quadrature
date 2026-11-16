package cc.orthrus.quadrature.core.frame;

import java.util.Arrays;

/**
 * The tail of the latest whole frame intervals, worked out as Cubature's client preview governor
 * works out the one it cuts and raises by: the 95th percentile of the last 120 intervals from one
 * frame's start to the next, taken as the element at {@code ceil(0.95 n) - 1} of them sorted. With
 * it, what the governor does not say: how many of those frames were over its two marks, the
 * worst, and whether the long ones held a client tick.
 *
 * <p>A diagnostic. A frame costs two array writes; a reading sorts 120 numbers in an array kept
 * for it. Nothing is allocated after construction.
 */
public final class FrameTail {

    /** Frames looked at, as the governor's. */
    public static final int FRAMES = 120;

    /** What a reading found. */
    public static final class Reading {
        /** Frames read: fewer than {@link #FRAMES} until that many have passed. */
        public int frames;
        public double tailMs;
        public double worstMs;
        /** Frames at or over the mark under which the governor may raise, and at or over its budget. */
        public int overRaise;
        public int overBudget;
        /** Of the frames at or over the budget, those in which a client tick ran, and the longest such tick. */
        public int overBudgetWithTick;
        public double longestTickOverBudgetMs;
        /** Frames in which a client tick ran, and the longest tick. */
        public int withTick;
        public double longestTickMs;
    }

    private final long[] intervals = new long[FRAMES];
    private final long[] ticks = new long[FRAMES];
    private final long[] sorted = new long[FRAMES];
    private long count;

    /**
     * A frame ended.
     *
     * @param intervalNanos from its start to the next frame's start
     * @param tickNanos     the time client ticks took inside it, 0 for none
     */
    public void frame(long intervalNanos, long tickNanos) {
        int slot = (int) (count++ % FRAMES);
        intervals[slot] = intervalNanos;
        ticks[slot] = tickNanos;
    }

    /** Forgets every frame. */
    public void clear() {
        count = 0;
    }

    /**
     * Reads the latest frames.
     *
     * @param raiseMs  the mark under which the governor may raise: four fifths of its budget
     * @param budgetMs its frame budget
     */
    public void read(Reading into, double raiseMs, double budgetMs) {
        int n = (int) Math.min(count, FRAMES);
        into.frames = n;
        into.tailMs = 0;
        into.worstMs = 0;
        into.overRaise = 0;
        into.overBudget = 0;
        into.overBudgetWithTick = 0;
        into.longestTickOverBudgetMs = 0;
        into.withTick = 0;
        into.longestTickMs = 0;
        if (n == 0) {
            return;
        }
        for (int i = 0; i < n; i++) {
            double interval = intervals[i] / 1e6;
            double tick = ticks[i] / 1e6;
            sorted[i] = intervals[i];
            into.worstMs = Math.max(into.worstMs, interval);
            if (ticks[i] > 0) {
                into.withTick++;
                into.longestTickMs = Math.max(into.longestTickMs, tick);
            }
            if (interval >= raiseMs) {
                into.overRaise++;
            }
            if (interval >= budgetMs) {
                into.overBudget++;
                if (ticks[i] > 0) {
                    into.overBudgetWithTick++;
                    into.longestTickOverBudgetMs = Math.max(into.longestTickOverBudgetMs, tick);
                }
            }
        }
        Arrays.sort(sorted, 0, n);
        into.tailMs = sorted[(int) Math.ceil(.95 * n) - 1] / 1e6;
    }
}
