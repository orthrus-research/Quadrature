package cc.orthrus.quadrature.core.terrain;

/**
 * Which faces of a cube see each other through its blocks: for each of the fifteen pairs of the six
 * faces, whether some path through blocks that are not opaque joins them. A search for what a view
 * can see passes from one face of a cube to another only where this says it can, which is what keeps
 * sealed caves from being drawn.
 *
 * <p>Found by filling outward from every block that is not opaque and noting the faces each filled
 * region touches. One instance serves one thread and allocates nothing after construction.
 */
public final class CubeConnectivity {

    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;
    public static final int FACES = 6;

    /** Every pair of faces joined: a cube with nothing opaque in it. */
    public static final short ALL = 0x7FFF;
    /** No pair of faces joined: a cube that is solid, or sealed off from all its faces. */
    public static final short NONE = 0;

    private static final int CELLS = 4096;

    private final int[] pending = new int[CELLS];
    private final long[] seen = new long[CELLS / 64];

    /** The face across the cube from a face. */
    public static int opposite(int face) {
        return face ^ 1;
    }

    /** The step along x that leaving through a face takes: -1, 0 or 1. */
    public static int stepX(int face) {
        return face == WEST ? -1 : face == EAST ? 1 : 0;
    }

    public static int stepY(int face) {
        return face == DOWN ? -1 : face == UP ? 1 : 0;
    }

    public static int stepZ(int face) {
        return face == NORTH ? -1 : face == SOUTH ? 1 : 0;
    }

    /** Whether two different faces are joined. */
    public static boolean connected(int links, int a, int b) {
        return (links & 1 << pair(a, b)) != 0;
    }

    private static int pair(int a, int b) {
        int low = Math.min(a, b);
        int high = Math.max(a, b);
        return low * 5 - low * (low - 1) / 2 + high - low - 1;
    }

    /** The index of a block in the bit set {@link #of} reads: x, y and z each from 0 to 15. */
    public static int cell(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }

    /**
     * @param opaque one bit per block, at {@link #cell}, set where the block is opaque: 64 words
     * @return the joined pairs of faces, as {@link #connected} reads them
     */
    public short of(long[] opaque) {
        java.util.Arrays.fill(seen, 0);
        int links = 0;
        for (int start = 0; start < CELLS && links != ALL; start++) {
            if (bit(opaque, start) || bit(seen, start)) {
                continue;
            }
            int touched = 0;
            int count = 0;
            pending[count++] = start;
            seen[start >> 6] |= 1L << start;
            while (count > 0) {
                int cell = pending[--count];
                int x = cell & 15;
                int z = cell >> 4 & 15;
                int y = cell >> 8;
                if (x == 0) {
                    touched |= 1 << WEST;
                } else {
                    count = visit(opaque, cell - 1, count);
                }
                if (x == 15) {
                    touched |= 1 << EAST;
                } else {
                    count = visit(opaque, cell + 1, count);
                }
                if (z == 0) {
                    touched |= 1 << NORTH;
                } else {
                    count = visit(opaque, cell - 16, count);
                }
                if (z == 15) {
                    touched |= 1 << SOUTH;
                } else {
                    count = visit(opaque, cell + 16, count);
                }
                if (y == 0) {
                    touched |= 1 << DOWN;
                } else {
                    count = visit(opaque, cell - 256, count);
                }
                if (y == 15) {
                    touched |= 1 << UP;
                } else {
                    count = visit(opaque, cell + 256, count);
                }
            }
            for (int a = 0; a < FACES; a++) {
                if ((touched & 1 << a) == 0) {
                    continue;
                }
                for (int b = a + 1; b < FACES; b++) {
                    if ((touched & 1 << b) != 0) {
                        links |= 1 << pair(a, b);
                    }
                }
            }
        }
        return (short) links;
    }

    private int visit(long[] opaque, int cell, int count) {
        if (bit(opaque, cell) || bit(seen, cell)) {
            return count;
        }
        seen[cell >> 6] |= 1L << cell;
        pending[count] = cell;
        return count + 1;
    }

    private static boolean bit(long[] bits, int index) {
        return (bits[index >> 6] & 1L << index) != 0;
    }
}
