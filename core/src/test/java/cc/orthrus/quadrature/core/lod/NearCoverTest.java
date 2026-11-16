package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NearCoverTest {

    /** Near terrain's rule, as Terrain applies it: a cube is meshed when its six neighbors are loaded. */
    private static boolean meshed(int x, int y, int z, int[] c) {
        return loaded(x - 1, y, z, c) && loaded(x + 1, y, z, c) && loaded(x, y - 1, z, c) && loaded(x, y + 1, z, c)
            && loaded(x, y, z - 1, c) && loaded(x, y, z + 1, c);
    }

    /** A client holds the cubes within its reach of the viewer's cube: {x, y, z, across, upward}. */
    private static boolean loaded(int x, int y, int z, int[] c) {
        return Math.abs(x - c[0]) <= c[3] && Math.abs(y - c[1]) <= c[4] && Math.abs(z - c[2]) <= c[3];
    }

    @Test
    void theBoxHoldsExactlyTheCubesWhoseSixNeighborsAreLoaded() {
        int[][] cases = {{-244, 4, 682, 12, 12}, {0, 0, 0, 2, 1}, {7, -100, -3, 5, 8}, {-1, -1, -1, 1, 1}};
        for (int[] c : cases) {
            long minX = NearCover.minimum(c[0], c[3]);
            long maxX = NearCover.maximum(c[0], c[3]);
            long minY = NearCover.minimum(c[1], c[4]);
            long maxY = NearCover.maximum(c[1], c[4]);
            long minZ = NearCover.minimum(c[2], c[3]);
            long maxZ = NearCover.maximum(c[2], c[3]);
            for (int x = c[0] - c[3] - 1; x <= c[0] + c[3] + 1; x++) {
                for (int y = c[1] - c[4] - 1; y <= c[1] + c[4] + 1; y++) {
                    for (int z = c[2] - c[3] - 1; z <= c[2] + c[3] + 1; z++) {
                        boolean inBox = x * 16L >= minX && x * 16L + 16 <= maxX && y * 16L >= minY
                            && y * 16L + 16 <= maxY && z * 16L >= minZ && z * 16L + 16 <= maxZ;
                        assertEquals(meshed(x, y, z, c), inBox, "cube " + x + " " + y + " " + z);
                    }
                }
            }
        }
    }

    @Test
    void theSecondRunsCoastViewHasItsBoxWhereNearTerrainEnds() {
        // The viewer stood at (-3903.5, 66, 10920.5) with cubes loaded 12 out: near terrain meshed
        // cubes -255 to -233 on x, so its ground ran from block -4080 up to -3712.
        assertEquals(-4080, NearCover.minimum(-244, 12));
        assertEquals(-3712, NearCover.maximum(-244, 12));
        assertEquals(0, NearCover.meshedReach(0));
    }

    @Test
    void aSquareIsCoveredOnlyWhenEveryColumnItTouchesIsMeshed() {
        // A map 5 columns a side whose first column is cube (-2, 10): all meshed but one, and the
        // outer ring waiting, as near terrain leaves it.
        int side = 5;
        byte[] states = new byte[side * side];
        for (int z = 0; z < side; z++) {
            for (int x = 0; x < side; x++) {
                boolean edge = x == 0 || z == 0 || x == side - 1 || z == side - 1;
                states[z * side + x] = edge ? (byte) 2 : NearCover.SURFACE_MESHED;
            }
        }
        // The inner 3 by 3 columns are cubes -1..1 by 11..13: blocks -16..32 by 176..224.
        assertTrue(NearCover.covered(states, side, -2, 10, -16, 176, 32, 224));
        assertTrue(NearCover.covered(states, side, -2, 10, 0, 192, 16, 208), "one column");
        assertTrue(NearCover.covered(states, side, -2, 10, -1, 191, 1, 193), "a square across four columns");
        assertFalse(NearCover.covered(states, side, -2, 10, -17, 176, 32, 224), "it touches a waiting column");
        assertFalse(NearCover.covered(states, side, -2, 10, -16, 176, 33, 224));
        assertFalse(NearCover.covered(states, side, -2, 10, -48, 176, -16, 224), "partly outside the map");
        assertFalse(NearCover.covered(states, side, -2, 10, 4000, 4000, 4064, 4064), "far outside the map");
        states[2 * side + 2] = 1;
        assertFalse(NearCover.covered(states, side, -2, 10, -16, 176, 32, 224), "one column has no surface");
        assertTrue(NearCover.covered(states, side, -2, 10, -16, 176, 0, 224), "the columns beside it are still covered");
    }

    @Test
    void imagesFadeOutsideNearTerrainAndInsideRingZero() {
        // The usual view distance: near terrain to about 190 blocks, the fade as asked.
        assertArrayEquals(new float[] {224f, 352f}, NearCover.imageFade(224f, 352f, 192f, 384f));
        // A short view distance changes nothing: the fade is already outside.
        assertArrayEquals(new float[] {224f, 352f}, NearCover.imageFade(224f, 352f, 64f, 384f));
        // A longer one moves the start past near terrain and squeezes the fade against the ring's edge.
        assertArrayEquals(new float[] {288f, 352f}, NearCover.imageFade(224f, 352f, 256f, 384f));
        float[] far = NearCover.imageFade(224f, 352f, 512f, 384f);
        assertTrue(far[0] < far[1] && far[1] <= 352f, "near terrain past ring 0: the fade stays inside the ring");
        // Settings that ask for the impossible still give a start before an end.
        float[] odd = NearCover.imageFade(900f, 100f, 192f, 384f);
        assertTrue(odd[0] < odd[1] && odd[1] <= 352f);
    }

    @Test
    void onlyWaterIsLoweredToTheGamesSurface() {
        assertEquals(1f / 9f, NearCover.topDrop(TileDraws.WATER));
        assertEquals(0f, NearCover.topDrop(TileDraws.OPAQUE));
        assertEquals(0f, NearCover.topDrop(TileDraws.CUTOUT));
    }
}
