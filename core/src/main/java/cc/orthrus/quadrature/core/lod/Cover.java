package cc.orthrus.quadrature.core.lod;

/**
 * Which stored tile draws each piece of ground while distant terrain arrives, so that no hole is
 * open and nothing is drawn twice.
 *
 * <p>With {@link Rings#fill} a level takes the tiles inside its own hole too. Such a fill tile lies
 * over ground the finer levels draw, and is drawn only until they do: it gives its ground up a
 * quarter at a time, each quarter when the finer tile under it is shown, or when that tile's own
 * four quarters are all given up. Once all four are, it is not needed and can be let go; one whose
 * quarters are given up before it is built need not be built.
 *
 * <p>A finer tile under ground a coarser stored tile still draws is hidden: under a fill tile that
 * has not given its quarter up, and, in the band where two levels fade into each other, under a
 * band tile whose four finer tiles are not all shown yet, since a band fades only when they are.
 *
 * <p>So of the stored tiles over any column, exactly one draws it outside the bands, and one or
 * both of two inside them. Tiles kept past their ring are outside this rule: they are drawn whole
 * until what replaces them is stored.
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

    /** Every quarter of a tile given up. */
    public static final int ALL = 15;

    private final Rings rings;
    private final Tiles tiles;

    public Cover(Rings rings, Tiles tiles) {
        this.rings = rings;
        this.tiles = tiles;
    }

    /**
     * The quarters of a tile that finer tiles draw, a bit each, numbered as
     * {@link TileKey#child}: x first, then z. For a tile of level 0, none.
     */
    public int given(long key) {
        if (TileKey.level(key) == 0) {
            return 0;
        }
        int given = 0;
        for (int quarter = 0; quarter < 4; quarter++) {
            if (taken(TileKey.child(key, quarter))) {
                given |= 1 << quarter;
            }
        }
        return given;
    }

    /** Whether a tile's ground is drawn without the tile above it: by itself, or all by finer tiles. */
    private boolean taken(long key) {
        if (tiles.shown(key)) {
            return true;
        }
        if (!tiles.wanted(key)) {
            // In its level's square and not wanted: outside the world, where there is nothing to
            // draw. Outside the square: its ground belongs to the tile above and to no finer one.
            return rings.inSquare(key);
        }
        return rings.filling(key) && given(key) == ALL;
    }

    /** Whether a fill tile has given all its ground up: it need not be built, or can be let go. */
    public boolean spare(long key) {
        return rings.filling(key) && given(key) == ALL;
    }

    /** Whether the four tiles under a band tile are all shown, so that the band can fade. */
    public boolean finerShown(long key) {
        if (TileKey.level(key) == 0) {
            return false;
        }
        for (int quarter = 0; quarter < 4; quarter++) {
            if (!tiles.shown(TileKey.child(key, quarter))) {
                return false;
            }
        }
        return true;
    }

    /** Whether a coarser stored tile still draws this tile's ground, so that this one is not drawn. */
    public boolean hidden(long key) {
        long under = key;
        for (int level = TileKey.level(key) + 1; level < rings.levels(); level++) {
            long over = TileKey.parent(under);
            if (tiles.shown(over)) {
                if (rings.filling(over)) {
                    if ((given(over) & 1 << TileKey.childIndex(under)) == 0) {
                        return true;
                    }
                } else if (under == key && !finerShown(over)) {
                    // A band tile over this one: it is whole until all four under it are shown.
                    return true;
                }
            }
            under = over;
        }
        return false;
    }

    /** Whether a shown tile draws anything: not hidden, and not a fill tile with nothing left. */
    public boolean draws(long key) {
        return !hidden(key) && !(rings.filling(key) && given(key) == ALL);
    }

    /**
     * Whether a shown tile draws a column of its ground, given by which quarter of the tile the
     * column lies in.
     */
    public boolean draws(long key, int quarter) {
        if (hidden(key)) {
            return false;
        }
        return !rings.filling(key) || (given(key) & 1 << quarter) == 0;
    }
}
