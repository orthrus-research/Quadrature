package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FarEdgeTest {

    /** The square of a level for a camera, in blocks: lowest x and z, then the blocks after the highest. */
    private static long[] square(Rings rings, int level, long cameraX, long cameraZ) {
        long side = TileKey.side(level);
        long x = rings.squareStart(level, cameraX) * side;
        long z = rings.squareStart(level, cameraZ) * side;
        return new long[] {x, z, x + rings.across() * side, z + rings.across() * side};
    }

    @Test
    void anEyeOffTheGridIsNearerOneSideThanTheReach() {
        Rings rings = new Rings(384, 7);
        // On the grid: every side is the reach away, 384 << 6.
        long[] s = square(rings, 6, 0, 0);
        assertEquals(24576f, FarEdge.distance(0, 0, s[0], s[1], s[2], s[3]));
        // 3,000 blocks east and 1,000 south of an even boundary of level 6 (tiles of 4,096): the
        // square has not moved, so the east side is 3,000 nearer.
        s = square(rings, 6, 3000, 1000);
        assertEquals(24576f - 3000f, FarEdge.distance(3000.5, 1000.5, s[0], s[1], s[2], s[3]), 1f);
        // Just short of the next tile boundary the square is still there: all but a tile nearer,
        // five sixths of the reach. One block on it moves by two tiles.
        s = square(rings, 6, 4095, 0);
        assertEquals(24576f - 4095f, FarEdge.distance(4095, 0, s[0], s[1], s[2], s[3]));
        s = square(rings, 6, 4096, 0);
        assertEquals(24576f - 4096f, FarEdge.distance(4096, 0, s[0], s[1], s[2], s[3]),
            "the west side is now the nearer, by as much");
        // Never under five sixths of the reach, for any eye.
        for (long x = -9000; x < 9000; x += 37) {
            s = square(rings, 6, x, -x / 3);
            float d = FarEdge.distance(x + 0.5, -x / 3 + 0.5, s[0], s[1], s[2], s[3]);
            assertEquals(true, d >= 24576f - 4096f - 1f && d <= 24576f, "at " + x + ": " + d);
        }
    }

    @Test
    void theLeastIsTheReachLessATileAndALowerLevelReachesHalfAsFar() {
        assertEquals(20480, FarEdge.least(384, 7));
        assertEquals(24576 * 5 / 6, FarEdge.least(384, 7));
        assertEquals(384 - 64, FarEdge.least(384, 1));
        assertEquals((256L << 4) - (64L << 4), FarEdge.least(256, 5));
        // Level 5's square for an eye 3,000 east of a boundary of its grid too: its tiles are 2,048,
        // the nearest even boundary is 4,096, and the west side is the nearer.
        Rings rings = new Rings(384, 7);
        long[] s = square(rings, 5, 3000, 1000);
        assertEquals(12288f - (4096f - 3000f), FarEdge.distance(3000.5, 1000.5, s[0], s[1], s[2], s[3]), 1f);
    }

    @Test
    void theWorldsBoundIsNotAnEdgeToHide() {
        // A world of 16,384 blocks each way from the origin; the eye at (-4991, 12072), the level 6
        // square from -24,576 to 24,576 on both axes. Every side is the world's: the answer is the
        // distance to the farthest of them, the north one.
        assertEquals(12072f + 16384f, FarEdge.distance(-4991, 12072, -24576, -24576, 24576, 24576, -16384, -16384,
            16384, 16384));
        // A level 4 square inside that world, 6,144 blocks each way from (-4096, 12288): its south
        // side is past the bound and is left out; the nearest open side is the west one.
        assertEquals(-4991f + 10240f, FarEdge.distance(-4991, 12072, -10240, 6144, 2048, 18432, -16384, -16384, 16384,
            16384));
        // An eye outside what is drawn.
        assertEquals(0f, FarEdge.distance(5000, 0, -1000, -1000, 1000, 1000));
    }
}
