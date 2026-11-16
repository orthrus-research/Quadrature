package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

class CoverTest {

    /** A selection with bands and fill tiles, and a set of its tiles taken as shown. */
    private static final class World implements Cover.Tiles {
        final Rings rings;
        final List<Long> order = new ArrayList<>();
        final Set<Long> wanted = new HashSet<>();
        final Set<Long> shown = new HashSet<>();
        final Cover cover;

        World(int levels, long cameraX, long cameraZ, long half) {
            rings = new Rings(384, levels);
            rings.overlap(true);
            rings.fill(true);
            if (half > 0) {
                rings.select(cameraX, cameraZ, -half, -half, half, half);
            } else {
                rings.select(cameraX, cameraZ);
            }
            for (int i = 0; i < rings.count(); i++) {
                order.add(rings.tile(i));
            }
            wanted.addAll(order);
            cover = new Cover(rings, this);
        }

        @Override
        public boolean wanted(long key) {
            return wanted.contains(key);
        }

        @Override
        public boolean shown(long key) {
            return shown.contains(key);
        }

        /**
         * Every column of ground under any shown tile is drawn by exactly one tile, or by the two
         * of a band that fades; a column under no shown tile by none.
         *
         * @return how many cells were drawn by two
         */
        int check(String when, int stride) {
            int top = rings.levels() - 1;
            int cells = rings.across() << top;
            long first = (long) rings.squareStart(top, cameraX()) << top;
            long firstZ = (long) rings.squareStart(top, cameraZ()) << top;
            int doubled = 0;
            for (int j = 0; j < cells; j += stride) {
                for (int i = 0; i < cells; i += stride) {
                    long x = first + i;
                    long z = firstZ + j;
                    int drawing = 0;
                    int under = 0;
                    long coarse = -1;
                    long fine = -1;
                    for (int level = top; level >= 0; level--) {
                        long key = TileKey.of(level, (int) (x >> level), (int) (z >> level));
                        if (!shown.contains(key)) {
                            continue;
                        }
                        under++;
                        int quarter = level == 0 ? 0 : (int) (x >> (level - 1) & 1) | (int) (z >> (level - 1) & 1) << 1;
                        if (cover.draws(key, quarter)) {
                            drawing++;
                            if (coarse == -1) {
                                coarse = key;
                            } else {
                                fine = key;
                            }
                        }
                    }
                    if (under == 0) {
                        assertEquals(0, drawing);
                    } else if (drawing == 2) {
                        // Only a band: a tile that is not a fill tile, with the four under it shown.
                        assertFalse(rings.filling(coarse), when + ": a fill tile drawn over a finer one");
                        assertEquals(coarse, TileKey.parent(fine), when + ": two levels that are not neighbors");
                        assertTrue(cover.finerShown(coarse), when + ": a band tile over part of its four");
                        doubled++;
                    } else if (drawing != 1) {
                        fail(when + ": cell " + x + " " + z + " under " + under + " shown tiles is drawn by " + drawing);
                    }
                }
            }
            return doubled;
        }

        private long cameraX;
        private long cameraZ;

        long cameraX() {
            return cameraX;
        }

        long cameraZ() {
            return cameraZ;
        }

        World at(long x, long z) {
            cameraX = x;
            cameraZ = z;
            return this;
        }
    }

    private static World world(int levels, long x, long z, long half) {
        return new World(levels, x, z, half).at(x, z);
    }

    @Test
    void aLevelWithFillTakesItsWholeSquareNearestFirst() {
        World world = world(4, 1000, -3000, 0);
        int[] counts = new int[4];
        for (long key : world.order) {
            counts[TileKey.level(key)]++;
        }
        // Twelve by twelve a level: the ring's 108, the band's 20 and 16 fill tiles.
        assertEquals(144, counts[0]);
        for (int level = 1; level < 4; level++) {
            assertEquals(144, counts[level]);
        }
        int fill = 0;
        for (long key : world.order) {
            if (world.rings.filling(key)) {
                fill++;
                assertTrue(TileKey.level(key) > 0);
            }
            assertTrue(world.rings.inSquare(key));
        }
        assertEquals(3 * 16, fill);
        // The coarsest level's fill tiles are the first of all: the ground under the camera.
        for (int i = 0; i < 4; i++) {
            assertTrue(world.rings.filling(world.order.get(i)), "tile " + i + " of the order");
        }
        // Without fill the selection is what it was.
        Rings plain = new Rings(384, 4);
        plain.overlap(true);
        plain.select(1000, -3000);
        assertEquals(144 + 3 * 128, plain.count());
        assertFalse(plain.filling(plain.tile(0)));
    }

    @Test
    void atEveryMomentOfAnArrivalEveryColumnIsDrawnOnce() {
        long[][] cameras = {{0, 0}, {1000, -3000}, {-4991, 12072}, {63, 64}};
        for (long[] camera : cameras) {
            World world = world(4, camera[0], camera[1], 0);
            // In the schedule's order, a tile at a time.
            for (int stored = 0; stored <= world.order.size(); stored++) {
                if (stored > 0) {
                    world.shown.add(world.order.get(stored - 1));
                }
                if (stored % 7 == 0 || stored == world.order.size()) {
                    world.check("camera " + camera[0] + " " + camera[1] + ", " + stored + " stored in order", 1);
                }
            }
            // All stored: every fill tile has given everything up, and only the bands are drawn twice.
            for (long key : world.order) {
                if (world.rings.filling(key)) {
                    assertTrue(world.cover.spare(key));
                    assertFalse(world.cover.draws(key));
                } else {
                    assertFalse(world.cover.spare(key));
                    assertTrue(world.cover.draws(key));
                }
            }
            assertTrue(world.check("all stored", 1) > 0);
        }
    }

    @Test
    void anySubsetStoredInAnyOrderLeavesNoColumnDrawnTwiceOrNotAtAll() {
        Random random = new Random(41);
        for (int trial = 0; trial < 30; trial++) {
            World world = world(4, random.nextInt(20000) - 10000, random.nextInt(20000) - 10000,
                trial % 3 == 0 ? 2048 : 0);
            List<Long> shuffled = new ArrayList<>(world.order);
            Collections.shuffle(shuffled, random);
            int take = random.nextInt(shuffled.size() + 1);
            world.shown.addAll(shuffled.subList(0, take));
            world.check("trial " + trial + ", " + take + " of " + shuffled.size() + " stored at random", 1);
            // Fill tiles let go once they have given everything up, as the caller lets them.
            for (long key : world.order) {
                if (world.shown.contains(key) && world.cover.spare(key)) {
                    world.shown.remove(key);
                }
            }
            world.check("trial " + trial + " after letting spare fill tiles go", 1);
        }
    }

    @Test
    void sevenLevelsInTheLabsWorldArriveWithoutAHole() {
        World world = world(7, -3000, 12000, 16384);
        int step = Math.max(1, world.order.size() / 40);
        for (int stored = 0; stored < world.order.size(); stored++) {
            world.shown.add(world.order.get(stored));
            if (stored % step == 0) {
                world.check(stored + 1 + " stored in order", 4);
            }
        }
        world.check("all stored", 2);
        // What it costs: the fill tiles, 16 a level above the lowest, less the 4 this world does
        // not hold from here.
        int fill = 0;
        for (long key : world.order) {
            fill += world.rings.filling(key) ? 1 : 0;
        }
        assertEquals(6 * 16 - 4, fill);
    }

    @Test
    void aFillTileGivesItsGroundUpAQuarterAtATime() {
        World world = world(3, 0, 0, 0);
        long fill = -1;
        for (long key : world.order) {
            if (TileKey.level(key) == 2 && world.rings.filling(key)) {
                fill = key;
                break;
            }
        }
        world.shown.add(fill);
        assertEquals(0, world.cover.given(fill));
        assertTrue(world.cover.draws(fill));
        long first = TileKey.child(fill, 0);
        long third = TileKey.child(fill, 2);
        world.shown.add(first);
        assertEquals(1, world.cover.given(fill));
        assertFalse(world.cover.hidden(first));
        world.shown.add(third);
        assertEquals(1 | 4, world.cover.given(fill));
        // A tile two levels down is hidden while the tile between is not stored and has not given
        // its quarter up, and drawn once it has.
        long second = TileKey.child(fill, 1);
        if (world.rings.filling(second)) {
            long deep = TileKey.child(second, 3);
            world.shown.add(deep);
            assertTrue(world.cover.hidden(deep));
            for (int quarter = 0; quarter < 3; quarter++) {
                world.shown.add(TileKey.child(second, quarter));
            }
            assertFalse(world.cover.hidden(deep));
            assertEquals(1 | 2 | 4, world.cover.given(fill));
        }
        assertFalse(world.cover.spare(fill));
    }
}
