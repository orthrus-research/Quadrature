package cc.orthrus.quadrature.core.lod;

import java.util.Arrays;

/**
 * Which distant tiles a camera wants. Level k is drawn in ring k: the square of level-k tiles
 * within {@code reach << k} blocks of the camera, less the square of the ring below, which lies in
 * its middle. Every ring's square is moved to even tile positions, so the hole in ring k + 1 is a
 * whole number of its tiles and every tile of a ring has all four of its children, or none, in the
 * ring below. Each level therefore holds the same number of tiles, and doubling the view adds one
 * level.
 *
 * <p>Tiles outside the world's bounds are left out. The selection lists the coarsest level first
 * and, within a level, the tiles nearest the camera first, so there is always something to draw
 * and it sharpens outward; or, with {@link #nearFirst}, a coarse cover of the ground about the
 * camera and then every level from the finest. Levels above the first whose square holds the whole world are empty.
 *
 * <p>Not safe for use from more than one thread. Allocates only when it grows.
 */
public final class Rings {

    private final int reach;
    private final int levels;
    /** Tiles along a side of a ring's square. */
    private final int across;

    /** Whether each ring keeps the tiles just inside its hole, for a crossfade with the ring below. */
    private boolean overlap;
    /** Whether each ring above the lowest also takes the tiles inside its hole, to stand in for finer ones. */
    private boolean fill;
    /** Each level's square at the last selection: its lowest tile position along x and along z. */
    private final int[] squareX;
    private final int[] squareZ;
    private boolean selected;
    /** Whether the selection lists the coarsest fill tiles, then every level from the finest. */
    private boolean nearFirst;
    private long[] keys = new long[1024];
    private long[] order = new long[1024];
    private int count;

    /**
     * @param reach how far ring 0 reaches from the camera, in blocks; a multiple of 128
     * @param levels how many levels there are, counted from 0
     */
    public Rings(int reach, int levels) {
        if (reach < 128 || reach % 128 != 0) {
            throw new IllegalArgumentException("Reach " + reach + " is not a multiple of 128");
        }
        if (levels < 1 || levels > TileKey.LEVELS) {
            throw new IllegalArgumentException("Levels " + levels);
        }
        this.reach = reach;
        this.levels = levels;
        across = 2 * reach / RunTile.CELLS;
        squareX = new int[levels];
        squareZ = new int[levels];
    }

    /**
     * Whether each ring above the lowest also takes the tiles inside its hole, past any band. Those
     * tiles lie over ground the finer levels draw; they are there to be drawn until the finer tiles
     * are stored, so that a hole is never open, and are listed nearest first with their level, which
     * puts them before the ring's own. With a reach of 384 and bands they are 16 a level.
     */
    public void fill(boolean on) {
        fill = on;
    }

    public boolean fill() {
        return fill;
    }

    /**
     * The order of a selection. Off: the coarsest level first, each level nearest first. On: the
     * coarsest level's fill tiles, which cover the ground about the camera out to that level's
     * hole, then every level from the finest, each nearest first, and of the coarsest what is
     * left. So there is a coarse picture of everything near first, then full detail at the camera,
     * then outward; the finer levels' own fill tiles come after the levels that cover them and
     * are spared. Measured cold in a 32 km world: level 0 whole at 46 s against 113.
     */
    public void nearFirst(boolean on) {
        nearFirst = on;
    }

    /** A level's square as last selected: its lowest tile position along x. */
    public int squareX(int level) {
        return squareX[level];
    }

    /** A level's square as last selected: its lowest tile position along z. */
    public int squareZ(int level) {
        return squareZ[level];
    }

    /** Whether a tile lies in its level's square as last selected. */
    public boolean inSquare(long key) {
        int level = TileKey.level(key);
        if (!selected || level >= levels) {
            return false;
        }
        int x = TileKey.x(key) - squareX[level];
        int z = TileKey.z(key) - squareZ[level];
        return x >= 0 && x < across && z >= 0 && z < across;
    }

    /**
     * Whether a tile lies inside its level's hole as last selected, past any band: over ground that
     * finer levels draw. Such a tile is selected only with {@link #fill}.
     */
    public boolean filling(long key) {
        int level = TileKey.level(key);
        if (!selected || level == 0 || level >= levels) {
            return false;
        }
        int inset = overlap ? 1 : 0;
        int x = TileKey.x(key) - (squareX[level - 1] >> 1);
        int z = TileKey.z(key) - (squareZ[level - 1] >> 1);
        return x >= inset && x < across / 2 - inset && z >= inset && z < across / 2 - inset;
    }

    public int reach() {
        return reach;
    }

    /**
     * Whether each ring above the lowest also keeps the ring of its own tiles just inside its
     * hole. Over that band, one of its tiles wide, both levels then have tiles, and a draw can
     * fade from one to the other. With a reach of 384 it is 20 more tiles a level.
     */
    public void overlap(boolean on) {
        overlap = on;
    }

    public boolean overlap() {
        return overlap;
    }

    /** The lowest block of a level's hole along one axis, for a camera at {@code camera}: the square of the ring below. */
    public long holeStart(int level, long camera) {
        return (long) squareStart(level - 1, camera) * TileKey.side(level - 1);
    }

    /** The side of every hole of a level, in blocks. */
    public long holeSide(int level) {
        return (long) across * TileKey.side(level - 1);
    }

    public int levels() {
        return levels;
    }

    /** The lowest tile position of a level's square along one axis, for a camera at {@code camera}. */
    public int squareStart(int level, long camera) {
        long side = TileKey.side(level);
        // The nearest even tile boundary to the camera, less half the square.
        return (int) (2 * Math.floorDiv(camera + side, 2 * side)) - across / 2;
    }

    /** Tiles along a side of every level's square. */
    public int across() {
        return across;
    }

    /**
     * Selects the tiles for a camera in a world that holds the blocks from (minX, minZ) up to
     * (maxX, maxZ), exclusive. Read the result with {@link #count()} and {@link #tile(int)}.
     */
    public void select(long cameraX, long cameraZ, long minX, long minZ, long maxX, long maxZ) {
        count = 0;
        for (int level = 0; level < levels; level++) {
            squareX[level] = squareStart(level, cameraX);
            squareZ[level] = squareStart(level, cameraZ);
        }
        selected = true;
        for (int level = levels - 1; level >= 0; level--) {
            long side = TileKey.side(level);
            int startX = squareStart(level, cameraX);
            int startZ = squareStart(level, cameraZ);
            // The ring below's square in this level's tiles; its start is even, so it divides.
            int holeX = level > 0 ? squareStart(level - 1, cameraX) >> 1 : 0;
            int holeZ = level > 0 ? squareStart(level - 1, cameraZ) >> 1 : 0;
            int first = count;
            // With overlap the hole left out is a tile smaller on every side.
            int inset = overlap ? 1 : 0;
            for (int z = startZ; z < startZ + across; z++) {
                for (int x = startX; x < startX + across; x++) {
                    if (!fill && level > 0 && x >= holeX + inset && x < holeX + across / 2 - inset
                            && z >= holeZ + inset && z < holeZ + across / 2 - inset) {
                        continue;
                    }
                    if (x * side >= maxX || (x + 1) * side <= minX || z * side >= maxZ || (z + 1) * side <= minZ) {
                        continue;
                    }
                    if (count == keys.length) {
                        keys = Arrays.copyOf(keys, count * 2);
                        order = Arrays.copyOf(order, count * 2);
                    }
                    // Twice the distance from the camera to the tile's middle, along its far axis.
                    long far = Math.max(Math.abs((2L * x + 1) * side - 2 * cameraX),
                            Math.abs((2L * z + 1) * side - 2 * cameraZ));
                    order[count] = far << 20 | (count - first);
                    keys[count++] = TileKey.of(level, x, z);
                }
            }
            nearestFirst(first, count);
        }
        if (nearFirst) {
            reorder();
        }
    }

    /** Puts a selection listed coarsest first into the order of {@link #nearFirst}. */
    private void reorder() {
        if (count == 0) {
            return;
        }
        int top = TileKey.level(keys[0]);
        int at = 0;
        for (int i = 0; i < count && TileKey.level(keys[i]) == top; i++) {
            if (filling(keys[i])) {
                order[at++] = keys[i];
            }
        }
        // The levels lie coarsest first, each a run: walk the runs from the last.
        int end = count;
        while (end > 0) {
            int level = TileKey.level(keys[end - 1]);
            int start = end;
            while (start > 0 && TileKey.level(keys[start - 1]) == level) {
                start--;
            }
            for (int i = start; i < end; i++) {
                if (level != top || !filling(keys[i])) {
                    order[at++] = keys[i];
                }
            }
            end = start;
        }
        System.arraycopy(order, 0, keys, 0, count);
    }

    /** Orders one level's tiles by distance; {@code order} holds distance over index within it. */
    private void nearestFirst(int from, int to) {
        if (to - from > 1 << 20) {
            throw new IllegalStateException("A ring of " + (to - from) + " tiles");
        }
        Arrays.sort(order, from, to);
        for (int i = from; i < to; i++) {
            order[i] = keys[from + (int) (order[i] & 0xFFFFF)];
        }
        System.arraycopy(order, from, keys, from, to - from);
    }

    /** Selects the tiles for a camera in a world without bounds. */
    public void select(long cameraX, long cameraZ) {
        long far = Long.MAX_VALUE / 4;
        select(cameraX, cameraZ, -far, -far, far, far);
    }

    /** Tiles selected. */
    public int count() {
        return count;
    }

    /** A selected tile's {@link TileKey}, coarsest level and nearest first. */
    public long tile(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(index);
        }
        return keys[index];
    }

    /**
     * How far along an axis distant terrain is drawn from a camera in a world without bounds, in
     * blocks: the reach of the highest ring. That ring's square is moved to even tile positions, so
     * its edges lie within one of its tiles of this; its corners are farther by the square root of 2.
     */
    public long drawnTo() {
        return (long) reach << (levels - 1);
    }

    /**
     * How far along an axis distant terrain is drawn from a camera in a world that holds the blocks
     * from (minX, minZ) up to (maxX, maxZ): the reach of the highest ring that has tiles there, or
     * the distance to the world's farthest edge where that is nearer. 0 for a camera so far outside
     * the world that nothing of it is drawn along either axis.
     */
    public long drawnTo(long cameraX, long cameraZ, long minX, long minZ, long maxX, long maxZ) {
        long ring = (long) reach << coarsestNeeded(cameraX, cameraZ, minX, minZ, maxX, maxZ);
        long world = Math.max(Math.max(cameraX - minX, maxX - cameraX), Math.max(cameraZ - minZ, maxZ - cameraZ));
        return Math.max(0, Math.min(ring, world));
    }

    /**
     * The lowest level whose square holds every block of the world for this camera, or the highest
     * level there is when none does: no tile above it is ever selected.
     */
    public int coarsestNeeded(long cameraX, long cameraZ, long minX, long minZ, long maxX, long maxZ) {
        for (int level = 0; level < levels; level++) {
            long side = TileKey.side(level);
            long startX = squareStart(level, cameraX) * side;
            long startZ = squareStart(level, cameraZ) * side;
            if (startX <= minX && startZ <= minZ && startX + across * side >= maxX
                    && startZ + across * side >= maxZ) {
                return level;
            }
        }
        return levels - 1;
    }
}
