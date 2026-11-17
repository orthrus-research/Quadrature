package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TravelTest {

    @Test
    void delaysAreReadInOrderOrNotAtAll() {
        assertArrayEquals(new long[] {500, 2000, 5000, 12000}, Travel.delays("500,2000, 5000 ,12000"));
        assertArrayEquals(new long[] {0}, Travel.delays("0"));
        assertArrayEquals(new long[0], Travel.delays(null));
        assertArrayEquals(new long[0], Travel.delays("  "));
        assertArrayEquals(new long[0], Travel.delays("500,400"));
        assertArrayEquals(new long[0], Travel.delays("500,500"));
        assertArrayEquals(new long[0], Travel.delays("-1,5"));
        assertArrayEquals(new long[0], Travel.delays("0.5,2"));
        assertArrayEquals(new long[0], Travel.delays("soon"));
    }

    @Test
    void aJumpIsMoreThanEightBlocksOverTheGroundOrInHeight() {
        assertTrue(Travel.jumped(0, 80, 0, 16, 80, 0));
        assertTrue(Travel.jumped(0, 80, 0, 5, 80, -4));
        assertTrue(Travel.jumped(0, 80, 0, 0, 112, 0));
        assertTrue(Travel.jumped(0, 80, 0, 0, 71, 0));
        // A flight at 34 blocks a second covers a block or two a frame.
        assertFalse(Travel.jumped(0, 80, 0, 1.7, 80.4, 0.3));
        assertFalse(Travel.jumped(0, 80, 0, 4, 88, 4));
        assertFalse(Travel.jumped(0, 80, 0, 0, 80, 0));
    }

    @Test
    void aPlaceIsWholeWhenCubesAreSettledAroundTheViewerAndEveryTileIsStored() {
        int[] box = {-188, 10, 750, 12, 4};
        assertTrue(Travel.whole(true, box, -188, 10, 750, 310, 310, 0));
        // The center may trail the viewer by a cube.
        assertTrue(Travel.whole(true, box, -187, 9, 751, 310, 310, 0));
        // Cubes still arriving, a tile missing, a tile being built, nothing asked for yet.
        assertFalse(Travel.whole(false, box, -188, 10, 750, 310, 310, 0));
        assertFalse(Travel.whole(true, box, -188, 10, 750, 310, 309, 0));
        assertFalse(Travel.whole(true, box, -188, 10, 750, 310, 310, 1));
        assertFalse(Travel.whole(true, box, -188, 10, 750, 0, 0, 0));
        // Just after a jump the settled box is still the old place's.
        assertFalse(Travel.whole(true, box, -312, 10, 754, 310, 310, 0));
        assertFalse(Travel.whole(true, box, -188, 12, 750, 310, 310, 0));
        assertFalse(Travel.whole(true, box, -188, 10, 748, 310, 310, 0));
        // A server that states no box: the quiet ticks stand alone.
        assertTrue(Travel.whole(true, null, -312, 10, 754, 310, 310, 0));
        assertFalse(Travel.whole(false, null, -312, 10, 754, 310, 310, 0));
    }
}
