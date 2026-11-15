package cc.orthrus.quadrature.core.lod;

import java.util.Arrays;

/**
 * One tile of distant terrain as columns of vertical runs: 64 by 64 columns and a ring of one more
 * column all round (the apron, 66 by 66 in all), for cells {@code 1 << level} blocks wide. Height
 * stays at one block at every level. A run is a top, a material and a kind; a column's runs are
 * contiguous from the tile's floor to its ceiling, each starting where the one below ends.
 *
 * <p>Columns are filled in index order, {@link #column(int, int)} for x and z from -1 to 64, each
 * with {@link #beginColumn(int)} and its runs from the floor up. Neighboring runs of one material
 * join as they are added. The apron only tells the mesher what stands beside the tile.
 *
 * <p>Not safe for use from more than one thread. Allocates only when it grows.
 */
public final class RunTile {

    /** Columns along a side of the tile, and of the tile with its apron. */
    public static final int CELLS = 64, SIDE = CELLS + 2, COLUMNS = SIDE * SIDE;

    /** What the mesher needs to know of a material. */
    public static final byte AIR = 0, OPAQUE = 1, LEAVES = 2, WATER = 3, DETAIL = 4;

    private final int floorY;
    private final int ceilingY;
    private int level;
    private int originX;
    private int originZ;

    /** Each column's first run; the last entry is the number of runs. */
    final int[] start = new int[COLUMNS + 1];
    int[] top = new int[COLUMNS * 6];
    int[] material = new int[COLUMNS * 6];
    byte[] kind = new byte[COLUMNS * 6];
    final int[] tint = new int[COLUMNS];

    private final int[] lowest = new int[COLUMNS];
    private int column = -1;
    private int runs;
    private int cursorY;
    private boolean finished;

    /** A tile whose columns run from {@code floorY} up to {@code ceilingY}, exclusive. */
    public RunTile(int floorY, int ceilingY) {
        if (ceilingY <= floorY || ceilingY - floorY > FaceRecord.MAXIMUM_HEIGHT) {
            throw new IllegalArgumentException("A tile is 1 to " + FaceRecord.MAXIMUM_HEIGHT
                    + " blocks tall, not " + floorY + " to " + ceilingY);
        }
        this.floorY = floorY;
        this.ceilingY = ceilingY;
    }

    /** Empties the tile for a level and the block position of its column (0, 0). */
    public void reset(int level, int originX, int originZ) {
        this.level = level;
        this.originX = originX;
        this.originZ = originZ;
        column = -1;
        runs = 0;
        finished = false;
    }

    /** Starts the next column; {@code tint} is what its biome tints with, opaque to the tile. */
    public void beginColumn(int tint) {
        if (finished || column >= COLUMNS - 1) {
            throw new IllegalStateException("The tile has all its columns");
        }
        if (column >= 0 && cursorY != ceilingY) {
            throw new IllegalStateException("Column " + column + " ends at " + cursorY);
        }
        column++;
        start[column] = runs;
        this.tint[column] = tint;
        cursorY = floorY;
    }

    /** Adds the current column's next run, from {@code bottom} up to {@code topExclusive}. */
    public void addRun(int bottom, int topExclusive, int materialIndex, byte runKind) {
        if (column < 0 || bottom != cursorY || topExclusive <= bottom || topExclusive > ceilingY) {
            throw new IllegalStateException("Run " + bottom + " to " + topExclusive + " after " + cursorY);
        }
        if (materialIndex < 0 || materialIndex >= FaceRecord.MATERIALS) {
            throw new IllegalArgumentException("Material " + materialIndex);
        }
        cursorY = topExclusive;
        if (runs > start[column] && material[runs - 1] == materialIndex && kind[runs - 1] == runKind) {
            top[runs - 1] = topExclusive;
            return;
        }
        if (runs == top.length) {
            top = Arrays.copyOf(top, runs * 2);
            material = Arrays.copyOf(material, runs * 2);
            kind = Arrays.copyOf(kind, runs * 2);
        }
        top[runs] = topExclusive;
        material[runs] = materialIndex;
        kind[runs] = runKind;
        runs++;
    }

    /** Closes the tile once every column is filled. */
    public void finish() {
        if (column != COLUMNS - 1 || cursorY != ceilingY) {
            throw new IllegalStateException("The tile is incomplete at column " + column);
        }
        start[COLUMNS] = runs;
        finished = true;
    }

    /**
     * Joins each column's buried strata into one run: the runs that end at or below the lowest
     * space that is not opaque in the column and in the four beside it. No face can stand on them,
     * so the mesh is unchanged, and a tile kept for meshing again takes about a fifth of the
     * memory. The joined run keeps the material of the highest run it replaces.
     *
     * @return the runs removed
     */
    public int collapseBuried() {
        requireFinished();
        for (int c = 0; c < COLUMNS; c++) {
            lowest[c] = ceilingY;
            for (int r = start[c]; r < start[c + 1]; r++) {
                if (kind[r] != OPAQUE) {
                    lowest[c] = bottom(c, r);
                    break;
                }
            }
        }
        int write = 0;
        for (int c = 0; c < COLUMNS; c++) {
            int x = c % SIDE;
            int z = c / SIDE;
            int limit = lowest[c];
            if (x > 0) {
                limit = Math.min(limit, lowest[c - 1]);
            }
            if (x < SIDE - 1) {
                limit = Math.min(limit, lowest[c + 1]);
            }
            if (z > 0) {
                limit = Math.min(limit, lowest[c - SIDE]);
            }
            if (z < SIDE - 1) {
                limit = Math.min(limit, lowest[c + SIDE]);
            }
            int first = start[c];
            int end = start[c + 1];
            start[c] = write;
            int r = first;
            // Every run that ends at or below the limit is opaque: the column's own lowest space
            // is at or above it.
            while (r + 1 < end && top[r + 1] <= limit) {
                r++;
            }
            for (; r < end; r++) {
                top[write] = top[r];
                material[write] = material[r];
                kind[write] = kind[r];
                write++;
            }
        }
        int removed = runs - write;
        runs = write;
        start[COLUMNS] = runs;
        return removed;
    }

    /** The index of column (x, z), each from -1 to 64. */
    public static int column(int x, int z) {
        return (z + 1) * SIDE + x + 1;
    }

    public int floorY() {
        return floorY;
    }

    public int ceilingY() {
        return ceilingY;
    }

    public int level() {
        return level;
    }

    public int originX() {
        return originX;
    }

    public int originZ() {
        return originZ;
    }

    /** Runs held, apron included. */
    public int runs() {
        requireFinished();
        return runs;
    }

    /** Runs of one column. */
    public int runs(int column) {
        requireFinished();
        return start[column + 1] - start[column];
    }

    /** Where run {@code run} of a column starts; runs are numbered across the whole tile. */
    int bottom(int column, int run) {
        return run == start[column] ? floorY : top[run - 1];
    }

    void requireFinished() {
        if (!finished) {
            throw new IllegalStateException("The tile is not finished");
        }
    }
}
