package cc.orthrus.quadrature.core.terrain;

import static cc.orthrus.quadrature.core.terrain.CubeConnectivity.DOWN;
import static cc.orthrus.quadrature.core.terrain.CubeConnectivity.EAST;
import static cc.orthrus.quadrature.core.terrain.CubeConnectivity.NORTH;
import static cc.orthrus.quadrature.core.terrain.CubeConnectivity.SOUTH;
import static cc.orthrus.quadrature.core.terrain.CubeConnectivity.UP;
import static cc.orthrus.quadrature.core.terrain.CubeConnectivity.WEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class CubeConnectivityTest {

    private final CubeConnectivity connectivity = new CubeConnectivity();

    private static long[] solid() {
        long[] opaque = new long[64];
        Arrays.fill(opaque, -1L);
        return opaque;
    }

    private static void open(long[] opaque, int x, int y, int z) {
        int cell = CubeConnectivity.cell(x, y, z);
        opaque[cell >> 6] &= ~(1L << cell);
    }

    private static void fill(long[] opaque, int x, int y, int z) {
        int cell = CubeConnectivity.cell(x, y, z);
        opaque[cell >> 6] |= 1L << cell;
    }

    @Test
    void anEmptyCubeJoinsEveryPairOfFaces() {
        assertEquals(CubeConnectivity.ALL, connectivity.of(new long[64]));
    }

    @Test
    void aSolidCubeJoinsNone() {
        assertEquals(CubeConnectivity.NONE, connectivity.of(solid()));
    }

    @Test
    void aWallAcrossTheCubeSeparatesTheTwoSidesOnly() {
        long[] opaque = new long[64];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                fill(opaque, 8, y, z);
            }
        }
        int links = connectivity.of(opaque);
        assertFalse(CubeConnectivity.connected(links, WEST, EAST));
        for (int side : new int[] {DOWN, UP, NORTH, SOUTH}) {
            assertTrue(CubeConnectivity.connected(links, WEST, side));
            assertTrue(CubeConnectivity.connected(links, EAST, side));
        }
        assertTrue(CubeConnectivity.connected(links, DOWN, UP));
        assertTrue(CubeConnectivity.connected(links, NORTH, SOUTH));
    }

    @Test
    void aTunnelJoinsItsTwoEndsAndNothingElse() {
        long[] opaque = solid();
        for (int x = 0; x < 16; x++) {
            open(opaque, x, 8, 8);
        }
        int links = connectivity.of(opaque);
        assertTrue(CubeConnectivity.connected(links, WEST, EAST));
        assertTrue(CubeConnectivity.connected(links, EAST, WEST));
        assertEquals(1, Integer.bitCount(links));
    }

    @Test
    void aSealedRoomJoinsNone() {
        long[] opaque = solid();
        for (int x = 1; x < 15; x++) {
            for (int y = 1; y < 15; y++) {
                for (int z = 1; z < 15; z++) {
                    open(opaque, x, y, z);
                }
            }
        }
        assertEquals(CubeConnectivity.NONE, connectivity.of(opaque));
    }

    @Test
    void aBentPassageJoinsTheFacesItReaches() {
        long[] opaque = solid();
        for (int y = 0; y <= 8; y++) {
            open(opaque, 3, y, 4);
        }
        for (int z = 4; z < 16; z++) {
            open(opaque, 3, 8, z);
        }
        int links = connectivity.of(opaque);
        assertTrue(CubeConnectivity.connected(links, DOWN, SOUTH));
        assertEquals(1, Integer.bitCount(links));
    }

    @Test
    void separateRegionsDoNotJoinEachOthersFaces() {
        long[] opaque = solid();
        open(opaque, 0, 5, 5);
        open(opaque, 15, 5, 5);
        open(opaque, 7, 0, 7);
        open(opaque, 7, 1, 7);
        assertEquals(CubeConnectivity.NONE, connectivity.of(opaque));
    }

    @Test
    void oppositeFacesAndStepsAgree() {
        for (int face = 0; face < CubeConnectivity.FACES; face++) {
            int other = CubeConnectivity.opposite(face);
            assertEquals(-CubeConnectivity.stepX(face), CubeConnectivity.stepX(other));
            assertEquals(-CubeConnectivity.stepY(face), CubeConnectivity.stepY(other));
            assertEquals(-CubeConnectivity.stepZ(face), CubeConnectivity.stepZ(other));
            assertEquals(1, Math.abs(CubeConnectivity.stepX(face)) + Math.abs(CubeConnectivity.stepY(face))
                + Math.abs(CubeConnectivity.stepZ(face)));
        }
    }
}
