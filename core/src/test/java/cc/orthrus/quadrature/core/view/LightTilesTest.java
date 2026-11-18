package cc.orthrus.quadrature.core.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

class LightTilesTest {

    @Test
    void theOutwardOrderHoldsEveryTileOnceAndStartsAtTheMiddle() {
        Set<Integer> seen = new HashSet<>();
        for (int rank = 0; rank < LightTiles.COUNT; rank++) {
            seen.add(LightTiles.outward(rank));
        }
        assertEquals(LightTiles.COUNT, seen.size());
        // The four tiles about the middle come first, a corner last.
        Set<Integer> middle = Set.of(LightTiles.index(7, 7), LightTiles.index(8, 7), LightTiles.index(7, 8),
            LightTiles.index(8, 8));
        for (int rank = 0; rank < 4; rank++) {
            assertTrue(middle.contains(LightTiles.outward(rank)));
        }
        int last = LightTiles.outward(LightTiles.COUNT - 1);
        assertTrue(Set.of(0, 15, 240, 255).contains(last));
    }

    @Test
    void aRangeOfTexelsCoversAndCountsTheTilesItTouches() {
        BitSet tiles = new BitSet();
        // Texels 250 to 260 across and 0 to 10 up, of 4,096: tiles 0 and 1 of the first row.
        int[] range = {250, 260, 0, 10};
        LightTiles.cover(tiles, range, 4096);
        assertEquals(2, tiles.cardinality());
        assertTrue(tiles.get(0) && tiles.get(1));
        assertTrue(LightTiles.covers(tiles, new int[] {255, 255, 5, 5}, 4096));
        assertFalse(LightTiles.covers(tiles, new int[] {600, 700, 0, 10}, 4096));
        int[] counts = new int[LightTiles.COUNT];
        LightTiles.count(counts, new int[] {0, 4095, 4000, 4095}, 4096);
        assertEquals(1, counts[LightTiles.index(0, 15)]);
        assertEquals(1, counts[LightTiles.index(15, 15)]);
        assertEquals(0, counts[LightTiles.index(0, 14)]);
    }

    @Test
    void tilesAlongARowAreClearedAsOneRectangle() {
        BitSet tiles = new BitSet();
        tiles.set(LightTiles.index(2, 3), LightTiles.index(6, 3));
        tiles.set(LightTiles.index(9, 3));
        tiles.set(LightTiles.index(0, 15));
        int[] out = new int[4 * LightTiles.COUNT];
        int count = LightTiles.rectangles(tiles, 4096, out);
        assertEquals(3, count);
        // Tiles 2 to 5 of row 3: 4 tiles of 256 texels.
        assertEquals(512, out[0]);
        assertEquals(768, out[1]);
        assertEquals(1024, out[2]);
        assertEquals(256, out[3]);
        assertEquals(2304, out[4]);
        assertEquals(256, out[6]);
        assertEquals(0, out[8]);
        assertEquals(3840, out[9]);
    }
}
