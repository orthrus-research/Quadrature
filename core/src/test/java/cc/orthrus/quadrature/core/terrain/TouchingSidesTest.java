package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TouchingSidesTest {

    @Test
    void bitsNumberTheOffsetsAndLeaveTheCenterOut() {
        assertEquals(1, TouchingSides.bit(-1, -1, -1));
        assertEquals(1 << 26, TouchingSides.bit(1, 1, 1));
        assertEquals(1 << TouchingSides.CENTER, TouchingSides.bit(0, 0, 0));
        assertEquals(26, Integer.bitCount(TouchingSides.ALL));
        assertEquals(0, TouchingSides.ALL & TouchingSides.bit(0, 0, 0));
    }

    @Test
    void aBlocksOffsetIsTheCubeItLiesIn() {
        // The cube whose lowest block is -16: blocks -16 to -1 are its own.
        assertEquals(0, TouchingSides.offset(-16, -16));
        assertEquals(0, TouchingSides.offset(-1, -16));
        assertEquals(1, TouchingSides.offset(0, -16));
        assertEquals(-1, TouchingSides.offset(-17, -16));
        // Farther counts as the touching cube on that side.
        assertEquals(1, TouchingSides.offset(40, -16));
        assertEquals(-1, TouchingSides.offset(-100, -16));
    }

    @Test
    void aChangeReachesOnlyTheFacesEdgesAndCornersItsBoundsMeet() {
        // An arrival, the whole cube: all 26.
        assertEquals(TouchingSides.ALL, TouchingSides.reached(0, 0, 0, 15, 15, 15));
        // Inside, away from every face: none.
        assertEquals(0, TouchingSides.reached(1, 1, 1, 14, 14, 14));
        // One block on the +x face, inside along y and z: that face alone.
        assertEquals(TouchingSides.bit(1, 0, 0), TouchingSides.reached(15, 5, 5, 15, 5, 5));
        // On the +x +y edge: the two faces and the edge.
        assertEquals(TouchingSides.bit(1, 0, 0) | TouchingSides.bit(0, 1, 0) | TouchingSides.bit(1, 1, 0),
            TouchingSides.reached(15, 15, 5, 15, 15, 5));
        // At the +x +y +z corner: three faces, three edges, the corner.
        assertEquals(7, Integer.bitCount(TouchingSides.reached(15, 15, 15, 15, 15, 15)));
        // The whole bottom layer, which spans x and z too: every touching cube but the nine above.
        assertEquals(17, Integer.bitCount(TouchingSides.reached(0, 0, 0, 15, 0, 15)));
        assertTrue((TouchingSides.reached(0, 0, 0, 15, 0, 15) & TouchingSides.bit(0, -1, 0)) != 0);
        assertFalse((TouchingSides.reached(0, 0, 0, 15, 0, 15) & TouchingSides.bit(0, 1, 0)) != 0);
    }
}
