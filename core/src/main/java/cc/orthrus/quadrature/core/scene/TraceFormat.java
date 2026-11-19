package cc.orthrus.quadrature.core.scene;

/**
 * How a ray and its result lie in memory for the trace through the light scene's bricks, on the GPU
 * and in {@link BrickTrace} alike.
 *
 * <p><b>A ray</b> is 32 bytes, two vectors of four floats in std430: the origin in blocks relative to
 * the lowest corner of the box's origin cube, {@code originCube * 16}, and the length in blocks; then
 * the inverse of the direction and a float kept at zero. A direction of unit length is assumed, so
 * that distances are in blocks. Each component of the inverse is clamped to plus or minus 1e30 before
 * use, so a direction with a component of exactly zero gives an infinite inverse and is still traced.
 *
 * <p><b>A result</b> is 16 bytes, four unsigned integers: the bits of the distance at which the ray
 * entered the cell that ended it; that cell relative to the same corner, each coordinate plus 512 in
 * ten bits, x in the lowest; the outcome in the low four bits and the nodes visited above them; and a
 * word kept at zero.
 */
public final class TraceFormat {

    /** Bytes to a ray. */
    public static final int RAY_BYTES = 32;
    /** Bytes to a result. */
    public static final int RESULT_BYTES = 16;
    /** Floats to a ray. */
    public static final int RAY_FLOATS = RAY_BYTES / 4;
    /** Integers to a result. */
    public static final int RESULT_INTS = RESULT_BYTES / 4;

    /** The ray entered a cell that blocks light; the distance is where it entered, 0 when it began in one. */
    public static final int HIT = 0;
    /** The ray reached its length; the distance is the length and the cell is not meaningful. */
    public static final int MISS = 1;
    /** The ray left the box the lookup covers; the cell is the first outside it. */
    public static final int LEFT = 2;
    /** The ray entered a cube nothing is known of; the cell is the first of it the ray entered. */
    public static final int NOT_AVAILABLE = 3;
    /** The ray visited the most nodes allowed without ending. */
    public static final int STEPS = 4;
    /** Outcomes there are. */
    public static final int OUTCOMES = 5;

    /** A cell coordinate is stored plus this, in ten bits. */
    public static final int CELL_BIAS = 512;
    /** The farthest a box may reach, in cubes, so that every cell of it fits ten bits: 31 cubes. */
    public static final int MOST_REACH = (CELL_BIAS - 16) / 16;

    private TraceFormat() {
    }

    /** Packs a cell relative to the origin cube's corner. */
    public static int cell(int x, int y, int z) {
        return (x + CELL_BIAS & 1023) | (y + CELL_BIAS & 1023) << 10 | (z + CELL_BIAS & 1023) << 20;
    }

    public static int cellX(int packed) {
        return (packed & 1023) - CELL_BIAS;
    }

    public static int cellY(int packed) {
        return (packed >>> 10 & 1023) - CELL_BIAS;
    }

    public static int cellZ(int packed) {
        return (packed >>> 20 & 1023) - CELL_BIAS;
    }

    public static int outcome(int word) {
        return word & 15;
    }

    /** Nodes visited: cells, and cubes passed whole. */
    public static int visits(int word) {
        return word >>> 4;
    }

    public static String outcomeName(int outcome) {
        switch (outcome) {
            case HIT:
                return "hit";
            case MISS:
                return "missed";
            case LEFT:
                return "left the box";
            case NOT_AVAILABLE:
                return "not available";
            case STEPS:
                return "out of steps";
            default:
                return "outcome " + outcome;
        }
    }

    /**
     * Writes a ray at a float offset, the inverse of its direction taken here. The direction need not
     * be of unit length, but distances are then in its units.
     */
    public static void ray(float[] into, int at, float x, float y, float z, float length, float dx, float dy, float dz) {
        into[at] = x;
        into[at + 1] = y;
        into[at + 2] = z;
        into[at + 3] = length;
        into[at + 4] = 1.0f / dx;
        into[at + 5] = 1.0f / dy;
        into[at + 6] = 1.0f / dz;
        into[at + 7] = 0.0f;
    }

    /** The bits for a state that blocks light, as the trace reads them: a bit for each of 65,536 states. */
    public static int[] blockingStates(LightScene scene) {
        int[] bits = new int[LightScene.STATES / 32];
        for (int state = 0; state < LightScene.STATES; state++) {
            if (scene.blocksLight(state)) {
                bits[state >> 5] |= 1 << (state & 31);
            }
        }
        return bits;
    }

    /**
     * The bricks as the GPU holds them, unsigned 32-bit integers in little-endian order: each 64-bit
     * word of {@link SceneBricks#bricks()} becomes its low half and then its high half.
     */
    public static int[] brickInts(long[] words, int slots) {
        int[] ints = new int[slots * PublishedScene.BRICK_INTEGERS];
        for (int i = 0; i < slots * SceneBricks.BRICK_WORDS; i++) {
            ints[2 * i] = (int) words[i];
            ints[2 * i + 1] = (int) (words[i] >>> 32);
        }
        return ints;
    }
}
