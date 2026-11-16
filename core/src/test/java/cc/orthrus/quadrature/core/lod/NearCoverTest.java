package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void onlyWaterIsLoweredToTheGamesSurface() {
        assertEquals(1f / 9f, NearCover.topDrop(TileDraws.WATER));
        assertEquals(0f, NearCover.topDrop(TileDraws.OPAQUE));
        assertEquals(0f, NearCover.topDrop(TileDraws.CUTOUT));
    }
}
