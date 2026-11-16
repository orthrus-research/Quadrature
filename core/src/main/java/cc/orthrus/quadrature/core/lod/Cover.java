package cc.orthrus.quadrature.core.lod;

/**
 * Which stored tile draws each piece of ground while distant terrain arrives, so that no hole is
 * open and nothing is drawn twice.
 *
 * <p>With {@link Rings#fill} a level takes the tiles inside its own hole too. Such a fill tile lies
 * over ground the finer levels draw, and is drawn only where they do not yet.
 *
 * <p><b>The rule is by place, not by tile: the finest shown tile over a place draws it.</b> A
 * coarser tile draws a place only if no finer level has a shown tile there. The one exception is
 * a band: where a level's band tile lies over a shown tile of the next finer level, both are drawn
 * and fade into each other. A tile is shown when it is stored, wanted and past its fading in. So
 * tiles may be stored in any order: a finer one is seen as soon as it is shown, and the coarser
 * one under it goes on drawing the ground beside it.
 *
 * <p>The draw applies the rule to each fragment from one bit a tile ({@link #masks}); this class
 * states it for a place ({@link #draws}), which the tests check, and answers what follows from it
 * for a whole tile: a fill tile all of whose ground finer tiles draw is of no use ({@link #spare}),
 * so it need not be built and can be let go.
 *
 * <p>Tiles kept past their ring are outside this rule: they are drawn whole until what replaces
 * them is stored.
 *
 * <p>Holds nothing; asks the rings last selected and the caller's tiles. Not safe for use while
 * the rings are being selected.
 */
public final class Cover {

    /** What the caller knows of its tiles. */
    public interface Tiles {
        /** Whether the present selection holds the tile. */
        boolean wanted(long key);

        /** Whether the tile is stored, wanted, and past its fading in. */
        boolean shown(long key);
    }

    /** {@link #draws}: the tile does not draw the place, draws it, or draws it fading with the finer tile there. */
    public static final int NOT = 0, WHOLE = 1, FADING = 2;

    /** Words of bits a level has in {@link #masks}: one bit a tile of its square, 32 to a word. */
    public static int maskWords(int across) {
        return (across * across + 31) / 32;
    }

    private final Rings rings;
    private final Tiles tiles;

    public Cover(Rings rings, Tiles tiles) {
        this.rings = rings;
        this.tiles = tiles;
    }

    /**
     * Whether a shown tile draws a place in its ground, given as the level 0 tile position the
     * place lies in (blocks divided by a tile's 64): {@link #NOT}, {@link #WHOLE} or
     * {@link #FADING}.
     */
    public int draws(long key, long cellX, long cellZ) {
        int level = TileKey.level(key);
        for (int finer = level - 1; finer >= 0; finer--) {
            long under = TileKey.of(finer, (int) (cellX >> finer), (int) (cellZ >> finer));
            if (tiles.shown(under)) {
                // A band: this level's tile just inside its hole, over the next finer level's.
                return finer == level - 1 && !rings.filling(key) ? FADING : NOT;
            }
        }
        return WHOLE;
    }

    /**
     * Whether finer shown tiles draw all of a tile's ground. A tile of level 0 has none under it.
     * Ground outside the world, where no tile is wanted, counts as drawn.
     */
    public boolean covered(long key) {
        if (TileKey.level(key) == 0) {
            return false;
        }
        for (int quarter = 0; quarter < 4; quarter++) {
            long child = TileKey.child(key, quarter);
            if (tiles.shown(child)) {
                continue;
            }
            if (!rings.inSquare(child)) {
                // No finer level reaches this quarter: it is this tile's own.
                return false;
            }
            if (!tiles.wanted(child)) {
                // In its level's square and not wanted: outside the world.
                continue;
            }
            if (!covered(child)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a fill tile is of no use now: it need not be built, or can be let go. */
    public boolean spare(long key) {
        return rings.filling(key) && covered(key);
    }

    /**
     * Writes one bit for each tile of each level's square as last selected: whether it is shown.
     * A level's bits start at {@code level * maskWords(across)}; a tile's bit is its place in the
     * square, x fastest.
     */
    public void masks(int[] into) {
        int across = rings.across();
        int words = maskWords(across);
        java.util.Arrays.fill(into, 0, rings.levels() * words, 0);
        for (int level = 0; level < rings.levels(); level++) {
            int startX = rings.squareX(level);
            int startZ = rings.squareZ(level);
            for (int z = 0; z < across; z++) {
                for (int x = 0; x < across; x++) {
                    if (tiles.shown(TileKey.of(level, startX + x, startZ + z))) {
                        int bit = z * across + x;
                        into[level * words + (bit >> 5)] |= 1 << (bit & 31);
                    }
                }
            }
        }
    }

    /** Reads {@link #masks} as the draw does: whether a level has a shown tile over a block. */
    public static boolean shownAt(int[] masks, int across, int level, int squareX, int squareZ, long blockX, long blockZ) {
        long side = TileKey.side(level);
        long x = Math.floorDiv(blockX, side) - squareX;
        long z = Math.floorDiv(blockZ, side) - squareZ;
        if (x < 0 || x >= across || z < 0 || z >= across) {
            return false;
        }
        int bit = (int) (z * across + x);
        return (masks[level * maskWords(across) + (bit >> 5)] >>> (bit & 31) & 1) != 0;
    }
}
