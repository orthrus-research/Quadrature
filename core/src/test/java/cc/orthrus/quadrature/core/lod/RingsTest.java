package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Random;

class RingsTest {

    private static int[] perLevel(Rings rings) {
        int[] counts = new int[rings.levels()];
        for (int i = 0; i < rings.count(); i++) {
            counts[TileKey.level(rings.tile(i))]++;
        }
        return counts;
    }

    @Test
    void everyLevelHoldsTheSameTilesWhereverTheCameraStands() {
        Rings rings = new Rings(384, 8);
        Random random = new Random(7);
        for (int trial = 0; trial < 200; trial++) {
            long x = trial == 0 ? 0 : random.nextInt(4_000_000) - 2_000_000;
            long z = trial == 0 ? 0 : random.nextInt(4_000_000) - 2_000_000;
            rings.select(x, z);
            int[] counts = perLevel(rings);
            assertEquals(144, counts[0]);
            for (int level = 1; level < 8; level++) {
                assertEquals(108, counts[level], "level " + level + " at " + x + " " + z);
            }
            assertEquals(900, rings.count());
        }
        assertEquals(64, new Rings(256, 8).across() * new Rings(256, 8).across());
        assertEquals(256, new Rings(512, 8).across() * new Rings(512, 8).across());
        assertThrows(IllegalArgumentException.class, () -> new Rings(400, 8));
    }

    @Test
    void theRingsTileTheTopSquareOnceWithNoGapAndTheCameraInRingZero() {
        Rings rings = new Rings(384, 8);
        long cameraX = 123_457;
        long cameraZ = -98_761;
        rings.select(cameraX, cameraZ);
        // Areas add up to the top level's square, and no tile lies over another.
        long area = 0;
        boolean cameraHeld = false;
        for (int i = 0; i < rings.count(); i++) {
            long tile = rings.tile(i);
            long side = TileKey.side(TileKey.level(tile));
            area += side * side;
            for (int j = 0; j < i; j++) {
                assertFalse(TileKey.covers(rings.tile(j), tile) || TileKey.covers(tile, rings.tile(j)));
            }
            if (cameraX >= TileKey.originX(tile) && cameraX < TileKey.originX(tile) + side
                    && cameraZ >= TileKey.originZ(tile) && cameraZ < TileKey.originZ(tile) + side) {
                assertEquals(0, TileKey.level(tile));
                cameraHeld = true;
            }
        }
        long top = rings.across() * TileKey.side(7);
        assertEquals(top * top, area);
        assertTrue(cameraHeld);
        // Ring 0 reaches at least its reach less one tile in every direction.
        long start = rings.squareStart(0, cameraX) * 64L;
        assertTrue(cameraX - start >= 384 - 64 && start + 768 - cameraX >= 384 - 64);
    }

    @Test
    void coarsestLevelsComeFirstAndNearTilesBeforeFar() {
        Rings rings = new Rings(384, 8);
        long cameraX = 5_000;
        long cameraZ = 7_000;
        rings.select(cameraX, cameraZ);
        int level = 7;
        long last = -1;
        for (int i = 0; i < rings.count(); i++) {
            long tile = rings.tile(i);
            if (TileKey.level(tile) != level) {
                assertEquals(level - 1, TileKey.level(tile));
                level--;
                last = -1;
            }
            long side = TileKey.side(level);
            long far = Math.max(Math.abs(2 * TileKey.originX(tile) + side - 2 * cameraX),
                    Math.abs(2 * TileKey.originZ(tile) + side - 2 * cameraZ));
            assertTrue(far >= last);
            last = far;
        }
        assertEquals(0, level);
    }

    @Test
    void tilesOutsideTheWorldAreLeftOut() {
        Rings rings = new Rings(384, 8);
        // An 8 km world seen from (0, -2560): levels 4 to 0 cover it.
        rings.select(0, -2560, -4096, -4096, 4096, 4096);
        int[] small = perLevel(rings);
        assertEquals(144, small[0]);
        assertEquals(108, small[1]);
        assertEquals(108, small[2]);
        assertEquals(84, small[3]);
        assertEquals(34, small[4]);
        assertEquals(0, small[5] + small[6] + small[7]);
        assertEquals(478, rings.count());
        assertEquals(4, rings.coarsestNeeded(0, -2560, -4096, -4096, 4096, 4096));
        // A 32 km world seen from (10240, -8192): levels 6 to 0.
        rings.select(10240, -8192, -16384, -16384, 16384, 16384);
        int[] large = perLevel(rings);
        assertEquals(144, large[0]);
        assertEquals(108, large[1] + 0);
        assertEquals(44, large[5]);
        assertEquals(44, large[6]);
        assertEquals(0, large[7]);
        assertEquals(664, rings.count());
        assertEquals(6, rings.coarsestNeeded(10240, -8192, -16384, -16384, 16384, 16384));
        for (int i = 0; i < rings.count(); i++) {
            long tile = rings.tile(i);
            long side = TileKey.side(TileKey.level(tile));
            assertTrue(TileKey.originX(tile) < 16384 && TileKey.originX(tile) + side > -16384);
            assertTrue(TileKey.originZ(tile) < 16384 && TileKey.originZ(tile) + side > -16384);
        }
        // A camera in a corner still needs the highest level there is.
        assertEquals(7, rings.coarsestNeeded(16000, 16000, -16384, -16384, 16384, 16384));
    }

    @Test
    void aTileKeyHoldsItsPlaceAndItsFamily() {
        Random random = new Random(11);
        for (int trial = 0; trial < 1000; trial++) {
            int level = random.nextInt(TileKey.LEVELS - 1);
            int x = random.nextInt(1 << 20) - (1 << 19);
            int z = random.nextInt(1 << 20) - (1 << 19);
            long key = TileKey.of(level, x, z);
            assertEquals(level, TileKey.level(key));
            assertEquals(x, TileKey.x(key));
            assertEquals(z, TileKey.z(key));
            assertEquals((long) x * 64 << level, TileKey.originX(key));
            long parent = TileKey.parent(key);
            assertEquals(level + 1, TileKey.level(parent));
            assertEquals(Math.floorDiv(x, 2), TileKey.x(parent));
            assertEquals(key, TileKey.child(parent, TileKey.childIndex(key)));
            assertTrue(TileKey.covers(parent, key) && TileKey.covers(key, key));
            assertFalse(TileKey.covers(key, parent));
            // The four children lie exactly under their parent.
            if (level > 0) {
                long side = TileKey.side(level);
                for (int child = 0; child < 4; child++) {
                    long under = TileKey.child(key, child);
                    assertTrue(TileKey.covers(key, under));
                    assertEquals(TileKey.originX(key) + (child & 1) * side / 2, TileKey.originX(under));
                    assertEquals(TileKey.originZ(key) + (child >> 1) * side / 2, TileKey.originZ(under));
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> TileKey.of(16, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TileKey.of(0, 1 << 29, 0));
    }
}
