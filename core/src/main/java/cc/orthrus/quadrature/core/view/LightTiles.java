package cc.orthrus.quadrature.core.view;

import java.util.BitSet;

/**
 * The tiles a layer of the light's depth is drawn in: {@value #ACROSS} by {@value #ACROSS}, each a
 * square of the map, numbered row by row from the corner where both of the box's axes are least. A
 * tile is the unit in which a layer is drawn again: it is cleared and every cube that may cover it is
 * drawn.
 */
public final class LightTiles {

    /** Tiles along a side of a layer. */
    public static final int ACROSS = 16;
    /** Tiles in a layer. */
    public static final int COUNT = ACROSS * ACROSS;

    /** Every tile, nearest the box's center first, so that a layer drawn in parts is drawn outward. */
    private static final int[] OUTWARD = outward();

    private LightTiles() {
    }

    public static int index(int across, int up) {
        return up * ACROSS + across;
    }

    /** The tile a texel lies in, along one axis, in a map {@code texels} across. */
    public static int tileOf(int texel, int texels) {
        return texel * ACROSS / texels;
    }

    /** The tiles in the order a layer drawn in parts is drawn: from the center outward. */
    public static int outward(int rank) {
        return OUTWARD[rank];
    }

    /** Sets the tiles a range of texels covers, inclusive, in a map {@code texels} across. */
    public static void cover(BitSet tiles, int[] range, int texels) {
        int across0 = tileOf(range[0], texels);
        int across1 = tileOf(range[1], texels);
        int up0 = tileOf(range[2], texels);
        int up1 = tileOf(range[3], texels);
        for (int up = up0; up <= up1; up++) {
            tiles.set(index(across0, up), index(across1, up) + 1);
        }
    }

    /** Whether a range of texels, inclusive, covers any of the tiles set. */
    public static boolean covers(BitSet tiles, int[] range, int texels) {
        int across0 = tileOf(range[0], texels);
        int across1 = tileOf(range[1], texels);
        int up0 = tileOf(range[2], texels);
        int up1 = tileOf(range[3], texels);
        for (int up = up0; up <= up1; up++) {
            int next = tiles.nextSetBit(index(across0, up));
            if (next >= 0 && next <= index(across1, up)) {
                return true;
            }
        }
        return false;
    }

    /** Adds one to each tile's count that a range of texels, inclusive, covers. */
    public static void count(int[] counts, int[] range, int texels) {
        int across0 = tileOf(range[0], texels);
        int across1 = tileOf(range[1], texels);
        int up0 = tileOf(range[2], texels);
        int up1 = tileOf(range[3], texels);
        for (int up = up0; up <= up1; up++) {
            for (int across = across0; across <= across1; across++) {
                counts[index(across, up)]++;
            }
        }
    }

    /**
     * The tiles set, as rectangles of texels to clear: runs of tiles along a row joined into one.
     *
     * @param out filled with x, y, width and height for each rectangle
     * @return how many rectangles
     */
    public static int rectangles(BitSet tiles, int texels, int[] out) {
        int size = texels / ACROSS;
        int count = 0;
        for (int up = 0; up < ACROSS; up++) {
            int across = 0;
            while (across < ACROSS) {
                if (!tiles.get(index(across, up))) {
                    across++;
                    continue;
                }
                int start = across;
                while (across < ACROSS && tiles.get(index(across, up))) {
                    across++;
                }
                out[count * 4] = start * size;
                out[count * 4 + 1] = up * size;
                out[count * 4 + 2] = (across - start) * size;
                out[count * 4 + 3] = size;
                count++;
            }
        }
        return count;
    }

    private static int[] outward() {
        Integer[] tiles = new Integer[COUNT];
        for (int i = 0; i < COUNT; i++) {
            tiles[i] = i;
        }
        double middle = (ACROSS - 1) / 2.0;
        java.util.Arrays.sort(tiles, java.util.Comparator.comparingDouble((Integer tile) -> {
            double across = tile % ACROSS - middle;
            double up = tile / ACROSS - middle;
            return across * across + up * up;
        }).thenComparingInt(tile -> tile));
        int[] order = new int[COUNT];
        for (int i = 0; i < COUNT; i++) {
            order[i] = tiles[i];
        }
        return order;
    }
}
