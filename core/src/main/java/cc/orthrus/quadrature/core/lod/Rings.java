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
 * and it sharpens outward. Levels above the first whose square holds the whole world are empty.
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
                    if (level > 0 && x >= holeX + inset && x < holeX + across / 2 - inset && z >= holeZ + inset
                            && z < holeZ + across / 2 - inset) {
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
