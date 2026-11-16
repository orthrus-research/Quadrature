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
}
