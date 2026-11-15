package cc.orthrus.quadrature.core.lod;

/**
 * A distant tile's place as one long: its level and its position in tiles of that level. A tile of
 * level k is 64 cells of {@code 1 << k} blocks a side, so tile (x, z) starts at block
 * {@code x * 64 << k}. Four tiles of a level lie exactly under one of the next: the parent of
 * (x, z) is (x >> 1, z >> 1).
 */
public final class TileKey {

    /** Levels a key can name. */
    public static final int LEVELS = 16;

    private static final int BITS = 30;
    private static final int LIMIT = 1 << (BITS - 1);
    private static final long MASK = (1L << BITS) - 1;

    private TileKey() {
    }

    public static long of(int level, int x, int z) {
        if (level < 0 || level >= LEVELS || x < -LIMIT || x >= LIMIT || z < -LIMIT || z >= LIMIT) {
            throw new IllegalArgumentException("Tile " + level + " " + x + " " + z);
        }
        return (long) level << (2 * BITS) | (x & MASK) << BITS | (z & MASK);
    }

    public static int level(long key) {
        return (int) (key >>> (2 * BITS));
    }

    public static int x(long key) {
        return (int) (key << (64 - 2 * BITS) >> (64 - BITS));
    }

    public static int z(long key) {
        return (int) (key << (64 - BITS) >> (64 - BITS));
    }

    /** Blocks along a side of a tile of a level. */
    public static long side(int level) {
        return (long) RunTile.CELLS << level;
    }

    /** The block the tile starts at along x. */
    public static long originX(long key) {
        return x(key) * side(level(key));
    }

    /** The block the tile starts at along z. */
    public static long originZ(long key) {
        return z(key) * side(level(key));
    }

    /** The tile of the next level that this one lies under. */
    public static long parent(long key) {
        return of(level(key) + 1, x(key) >> 1, z(key) >> 1);
    }

    /** One of the four tiles of the level below that lie under this one: 0 to 3, x then z. */
    public static long child(long key, int index) {
        return of(level(key) - 1, x(key) * 2 + (index & 1), z(key) * 2 + (index >> 1));
    }

    /** Which of its parent's four tiles this one is: 0 to 3, x then z. */
    public static int childIndex(long key) {
        return x(key) & 1 | (z(key) & 1) << 1;
    }

    /** Whether {@code inner} is {@code outer} or lies under it. */
    public static boolean covers(long outer, long inner) {
        int rise = level(outer) - level(inner);
        return rise >= 0 && x(inner) >> rise == x(outer) && z(inner) >> rise == z(outer);
    }
}
