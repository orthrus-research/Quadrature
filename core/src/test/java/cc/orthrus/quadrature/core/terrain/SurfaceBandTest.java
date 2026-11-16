package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class SurfaceBandTest {

    private static final int UNKNOWN = Integer.MIN_VALUE;

    /** What a source that tracks heights would fill for a column: ground, liquid, and whether loaded. */
    private static final class FakeSource {
        final int[] ground = new int[256];
        final int[] liquid = new int[256];
        final boolean[] loaded = new boolean[256];

        FakeSource(int groundTop) {
            Arrays.fill(ground, groundTop);
            Arrays.fill(liquid, UNKNOWN);
            Arrays.fill(loaded, true);
        }

        long band() {
            return SurfaceBand.of(ground, liquid, loaded, UNKNOWN);
        }
    }

    @Test
    void theBandSpansTheCubesOfTheLowestAndHighestGround() {
        // Ground whose top face is at 64: the block is 63, in cube 3.
        FakeSource flat = new FakeSource(64);
        assertEquals(3, SurfaceBand.lowest(flat.band()));
        assertEquals(3, SurfaceBand.highest(flat.band()));
        // A hillside from block 60 up to block 85.
        FakeSource hill = new FakeSource(61);
        hill.ground[200] = 86;
        assertEquals(3, SurfaceBand.lowest(hill.band()));
        assertEquals(5, SurfaceBand.highest(hill.band()));
        // Below zero the cubes go on down.
        FakeSource deep = new FakeSource(-16);
        assertEquals(-2, SurfaceBand.lowest(deep.band()));
    }

    @Test
    void liquidOverTheGroundRaisesTheBandToItsSurface() {
        FakeSource lake = new FakeSource(40);
        lake.liquid[7] = 63;
        assertEquals(2, SurfaceBand.lowest(lake.band()));
        assertEquals(3, SurfaceBand.highest(lake.band()));
    }

    @Test
    void onlyWhatLoadedCubesAnsweredDecides() {
        FakeSource partly = new FakeSource(64);
        // A guessed mountain in one block column, and an unknown one.
        partly.ground[10] = 300;
        partly.loaded[10] = false;
        partly.ground[11] = UNKNOWN;
        assertEquals(3, SurfaceBand.highest(partly.band()));
        // Nothing from loaded cubes: no band.
        Arrays.fill(partly.loaded, false);
        assertEquals(SurfaceBand.NONE, partly.band());
    }
}
