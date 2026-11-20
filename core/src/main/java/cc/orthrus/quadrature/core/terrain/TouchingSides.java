package cc.orthrus.quadrature.core.terrain;

/**
 * The 26 cubes touching a cube at a face, an edge or a corner, as bits of one int: the cube at offset
 * {@code (dx, dy, dz)}, each -1 to 1, is bit {@code (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)}, and the cube itself,
 * bit 13, is never one of them.
 *
 * <p>A change within a cube reaches a touching cube only where its bounds meet their shared face, edge or corner:
 * along each axis the offset is 0, or the bounds reach the face on that side. This is the rule the cube source
 * raises its changes of a touching cube by; a renderer that follows it from the changed cube's own event knows
 * which side each change came from.
 */
public final class TouchingSides {

    /** The cube's own bit. */
    public static final int CENTER = 13;
    /** All 26 touching cubes. */
    public static final int ALL = ((1 << 27) - 1) & ~(1 << CENTER);

    private TouchingSides() {
    }

    /** The bit of the cube at an offset, each -1 to 1. */
    public static int bit(int dx, int dy, int dz) {
        return 1 << ((dx + 1) * 9 + (dy + 1) * 3 + dz + 1);
    }

    /**
     * Along one axis, the offset of the cube a block lies in from the cube whose lowest block is {@code min}, -1 to
     * 1: a block farther away counts as in the touching cube on its side.
     */
    public static int offset(int block, int min) {
        return Math.max(-1, Math.min(1, (block - min) >> 4));
    }

    /** Whether bounds along one axis, 0 to 15, reach the cube's face in a direction, -1 to 1; 0 always does. */
    public static boolean touches(int direction, int min, int max) {
        return direction == 0 || (direction < 0 ? min == 0 : max == 15);
    }

    /** The touching cubes a change within the bounds reaches, as bits of their offsets from the changed cube. */
    public static int reached(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int bits = 0;
        for (int dx = -1; dx <= 1; dx++) {
            if (!touches(dx, minX, maxX)) {
                continue;
            }
            for (int dy = -1; dy <= 1; dy++) {
                if (!touches(dy, minY, maxY)) {
                    continue;
                }
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx | dy | dz) != 0 && touches(dz, minZ, maxZ)) {
                        bits |= bit(dx, dy, dz);
                    }
                }
            }
        }
        return bits;
    }
}
