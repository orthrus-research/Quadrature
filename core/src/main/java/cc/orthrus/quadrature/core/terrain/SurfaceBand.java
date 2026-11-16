package cc.orthrus.quadrature.core.terrain;

/**
 * A chunk column's surface band from the heights a cube source gives for its 256 block columns: the
 * cubes from the lowest to the highest that hold any of the column's ground or the surface of liquid
 * over it. Only heights known from loaded cubes are used; a height that is a guess decides nothing.
 */
public final class SurfaceBand {

    /** No band: nothing in the column is known from loaded cubes. */
    public static final long NONE = Long.MIN_VALUE;

    private SurfaceBand() {
    }

    /**
     * @param ground     for each block column, the height of the top face of the highest ground, or
     *                   {@code unknown}
     * @param liquid     likewise the surface of liquid above the ground, or {@code unknown} where there is none
     * @param fromLoaded whether each block column's answer came from loaded cubes
     * @param unknown    the value that stands for no height
     * @return the band, read with {@link #lowest} and {@link #highest}, or {@link #NONE}
     */
    public static long of(int[] ground, int[] liquid, boolean[] fromLoaded, int unknown) {
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int i = 0; i < 256; i++) {
            if (!fromLoaded[i] || ground[i] == unknown) {
                continue;
            }
            // A height is the top face of a block: the block itself is one lower.
            int cube = ground[i] - 1 >> 4;
            low = Math.min(low, cube);
            high = Math.max(high, cube);
            if (liquid[i] != unknown) {
                high = Math.max(high, liquid[i] - 1 >> 4);
            }
        }
        return high == Integer.MIN_VALUE ? NONE : (long) high << 32 | low & 0xFFFFFFFFL;
    }

    /** The lowest cube of a band. */
    public static int lowest(long band) {
        return (int) band;
    }

    /** The highest cube of a band. */
    public static int highest(long band) {
        return (int) (band >> 32);
    }
}
