package cc.orthrus.quadrature.core.material;

/**
 * A count of what the targets for shading are given as the game's occlusion, by the way a face is
 * turned and by what stands in the cell in front of it, with the arithmetic that takes the occlusion
 * out of the game's vertex color.
 *
 * <p>The game's smooth lighting multiplies a vertex's color by its tint, by an occlusion and, where
 * the quad asks for it, by a shading for the way its face is turned. The occlusion is what is left
 * when the color's greatest channel is divided by the tint's greatest channel and by that shading. On
 * a whole face of a full cube it is the mean of four cells in the layer in front of the face, each 1
 * where open and 0.2 where closed, so with the cell straight in front open it is never below 0.4. A
 * value below that on a face whose front cell is open would be a fault, and is counted apart.
 */
public final class OcclusionAudit {

    /** The cell in front of a face is open: it does not darken its neighbors. */
    public static final int FRONT_OPEN = 0;
    /** It is foliage that the game counts as closed. */
    public static final int FRONT_FOLIAGE = 1;
    /** It is closed and no foliage. */
    public static final int FRONT_OTHER = 2;

    /** The least the game gives a full cube's face corner whose front cell is open. */
    public static final float LEAST_WITH_OPEN_FRONT = 0.4f;

    private static final int DIRECTIONS = 6;
    private static final int FRONTS = 3;
    private static final int BINS = 11;
    private static final String[] DIRECTION_NAMES = {"down", "up", "north", "south", "west", "east"};
    private static final String[] FRONT_NAMES = {"open", "foliage", "other"};

    private final long[][][] counts = new long[DIRECTIONS][FRONTS][BINS];
    private final float[][] least = new float[DIRECTIONS][FRONTS];
    private long corners;
    private long faulty;

    public OcclusionAudit() {
        for (float[] row : least) {
            java.util.Arrays.fill(row, Float.POSITIVE_INFINITY);
        }
    }

    /**
     * The shading the game gives a face by the way it is turned, from its normal: half for one that
     * faces down, whole for one that faces up, 0.8 north and south, 0.6 west and east.
     */
    public static float shade(float x, float y, float z) {
        return Math.min(x * x * 0.6f + y * y * ((3f + y) / 4f) + z * z * 0.8f, 1f);
    }

    /**
     * The occlusion left in a vertex color.
     *
     * @param color    the color's greatest channel, 0 to 1
     * @param tint     the tint's greatest channel, 0 to 1
     * @param shade    the shading for the way the face is turned, as {@link #shade} gives it
     * @param occluded whether the game lit the quad smoothly: where not, nothing was multiplied in
     * @param shaded   whether the game shaded the quad by the way it is turned
     * @return the occlusion, 0 to 1; 1 where the quad was not lit smoothly or the tint is black
     */
    public static float occlusion(float color, float tint, float shade, boolean occluded, boolean shaded) {
        float most = tint * (shaded ? shade : 1f);
        if (!occluded || !(most > 0f)) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, color / most));
    }

    /**
     * Counts one corner of a whole face of a full cube.
     *
     * @param direction the way the face is turned: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east
     * @param front     what stands in the cell in front of it: one of the FRONT values
     * @param occlusion the occlusion left in the corner's color
     */
    public void corner(int direction, int front, float occlusion) {
        corners++;
        counts[direction][front][Math.max(0, Math.min(BINS - 1, Math.round(occlusion * 10)))]++;
        least[direction][front] = Math.min(least[direction][front], occlusion);
        // A color holds 255 levels: a corner within one level of the least is at the least.
        if (front == FRONT_OPEN && occlusion < LEAST_WITH_OPEN_FRONT - 1f / 255f) {
            faulty++;
        }
    }

    public long corners() {
        return corners;
    }

    /** The corners below the least the game gives with the front cell open. None is expected. */
    public long faulty() {
        return faulty;
    }

    /** The least occlusion counted for a direction and a front, or infinity where none was counted. */
    public float least(int direction, int front) {
        return least[direction][front];
    }

    /** How many corners of a direction and a front fell nearest a tenth, 0 to 10. */
    public long count(int direction, int front, int tenth) {
        return counts[direction][front][tenth];
    }

    /** One line: the count, the faulty, and for each front the least over every direction. */
    public String summary() {
        StringBuilder line = new StringBuilder(String.format("occlusion of %d corners of whole faces: %d below %.1f with the "
            + "front cell open", corners, faulty, LEAST_WITH_OPEN_FRONT));
        for (int front = 0; front < FRONTS; front++) {
            float lowest = Float.POSITIVE_INFINITY;
            long total = 0;
            for (int direction = 0; direction < DIRECTIONS; direction++) {
                lowest = Math.min(lowest, least[direction][front]);
                for (long count : counts[direction][front]) {
                    total += count;
                }
            }
            line.append(String.format("; front %s: %d, least %s", FRONT_NAMES[front], total,
                total == 0 ? "none" : String.format("%.3f", lowest)));
        }
        return line.toString();
    }

    /** A line for each direction: for each front, how many corners fell nearest each tenth from 0 to 1. */
    public String table() {
        StringBuilder table = new StringBuilder();
        for (int direction = 0; direction < DIRECTIONS; direction++) {
            table.append(String.format("%n  %-5s", DIRECTION_NAMES[direction]));
            for (int front = 0; front < FRONTS; front++) {
                table.append(' ').append(FRONT_NAMES[front]).append(' ').append(java.util.Arrays.toString(counts[direction][front]));
            }
        }
        return table.toString();
    }
}
