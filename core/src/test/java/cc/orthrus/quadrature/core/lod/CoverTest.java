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
        final long cameraX;
        final long cameraZ;

        World(int levels, long cameraX, long cameraZ, long half, boolean nearFirst) {
            this.cameraX = cameraX;
            this.cameraZ = cameraZ;
            rings = new Rings(384, levels);
            rings.overlap(true);
            rings.fill(true);
            rings.nearFirst(nearFirst);
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
         * Every place under any shown tile is drawn by exactly one tile whole, the finest shown
         * there, and besides it at most by the band tile over it, fading; a place under no shown
         * tile by none. The masks the draw reads say the same of every level.
         *
         * @return a digest of which places are drawn at all
         */
        long check(String when, int stride) {
            int top = rings.levels() - 1;
            int cells = rings.across() << top;
            long firstX = (long) rings.squareStart(top, cameraX) << top;
            long firstZ = (long) rings.squareStart(top, cameraZ) << top;
            int across = rings.across();
            int[] masks = new int[rings.levels() * Cover.maskWords(across)];
            cover.masks(masks);
            long drawnPlaces = 0;
            for (int j = 0; j < cells; j += stride) {
                for (int i = 0; i < cells; i += stride) {
                    long x = firstX + i;
                    long z = firstZ + j;
                    int whole = 0;
                    int fading = 0;
                    int under = 0;
                    long finest = -1;
                    long fader = -1;
                    for (int level = top; level >= 0; level--) {
                        long key = TileKey.of(level, (int) (x >> level), (int) (z >> level));
                        boolean isShown = shown.contains(key);
                        assertEquals(isShown, Cover.shownAt(masks, across, level, rings.squareX(level),
                            rings.squareZ(level), x * 64 + 5, z * 64 + 60), when + ": the mask of level " + level);
                        if (!isShown) {
                            continue;
                        }
                        under++;
                        int draws = cover.draws(key, x, z);
                        if (draws == Cover.WHOLE) {
                            whole++;
                            finest = key;
                        } else if (draws == Cover.FADING) {
                            fading++;
                            fader = key;
                        }
                    }
                    if (under == 0) {
                        assertEquals(0, whole + fading);
                        continue;
                    }
                    drawnPlaces = drawnPlaces * 31 + i * 7919L + j;
                    if (whole != 1 || fading > 1) {
                        fail(when + ": place " + x + " " + z + " under " + under + " shown tiles is drawn whole by "
                            + whole + " and fading by " + fading);
                    }
                    if (fading == 1) {
                        assertEquals(fader, TileKey.parent(finest), when + ": a band fades with the level under it");
                        assertFalse(rings.filling(fader), when + ": a fill tile does not fade, it gives way");
                    }
                }
            }
            return drawnPlaces;
        }
    }

    private static World world(int levels, long x, long z, long half) {
        return new World(levels, x, z, half, false);
    }

    @Test
    void aLevelWithFillTakesItsWholeSquareNearestFirst() {
        World world = world(4, 1000, -3000, 0);
        int[] counts = new int[4];
        for (long key : world.order) {
            counts[TileKey.level(key)]++;
        }
        // Twelve by twelve a level: the ring's 108, the band's 20 and 16 fill tiles.
        for (int level = 0; level < 4; level++) {
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
    void nearFirstListsTheCoarsestFillThenEveryLevelFromTheFinest() {
        World coarse = world(5, 1000, -3000, 0);
        World near = new World(5, 1000, -3000, 0, true);
        assertEquals(new HashSet<>(coarse.order), new HashSet<>(near.order));
        assertEquals(coarse.order.size(), near.order.size());
        // Sixteen fill tiles of the top level, then level 0 whole, then 1, 2, 3 and the rest of 4.
        for (int i = 0; i < 16; i++) {
            long key = near.order.get(i);
            assertEquals(4, TileKey.level(key));
            assertTrue(near.rings.filling(key));
        }
        int level = 0;
        for (int i = 16; i < near.order.size(); i++) {
            int now = TileKey.level(near.order.get(i));
            assertTrue(now == level || now == level + 1, "levels rise from the finest");
            level = now;
            if (now == 4) {
                assertFalse(near.rings.filling(near.order.get(i)));
            }
        }
        assertEquals(4, level);
        assertEquals(0, TileKey.level(near.order.get(16)));
    }

    @Test
    void atEveryMomentOfAnArrivalEveryPlaceUnderAStoredTileIsDrawnOnce() {
        long[][] cameras = {{0, 0}, {1000, -3000}, {-4991, 12072}, {63, 64}};
        for (boolean nearFirst : new boolean[] {false, true}) {
            for (long[] camera : cameras) {
                World world = new World(4, camera[0], camera[1], 0, nearFirst);
                for (int stored = 0; stored <= world.order.size(); stored++) {
                    if (stored > 0) {
                        world.shown.add(world.order.get(stored - 1));
                    }
                    if (stored % 7 == 0 || stored == world.order.size()) {
                        world.check("camera " + camera[0] + " " + camera[1] + ", " + stored + " stored, near first "
                            + nearFirst, 1);
                    }
                }
                // All stored: every fill tile is spare, and no other tile is.
                for (long key : world.order) {
                    assertEquals(world.rings.filling(key), world.cover.spare(key));
                }
            }
        }
    }

    @Test
    void lettingSpareFillTilesGoLeavesEveryPlaceDrawn() {
        Random random = new Random(41);
        for (int trial = 0; trial < 30; trial++) {
            World world = new World(4, random.nextInt(20000) - 10000, random.nextInt(20000) - 10000,
                trial % 3 == 0 ? 2048 : 0, trial % 2 == 0);
            List<Long> shuffled = new ArrayList<>(world.order);
            Collections.shuffle(shuffled, random);
            int take = random.nextInt(shuffled.size() + 1);
            world.shown.addAll(shuffled.subList(0, take));
            long before = world.check("trial " + trial + ", " + take + " of " + shuffled.size() + " stored at random", 1);
            int let = 0;
            for (long key : world.order) {
                if (world.shown.contains(key) && world.cover.spare(key)) {
                    world.shown.remove(key);
                    let++;
                }
            }
            long after = world.check("trial " + trial + " after letting " + let + " spare fill tiles go", 1);
            assertEquals(before, after, "trial " + trial + ": the same places are drawn");
        }
    }

    @Test
    void aFinerTileShowsAtOnceUnderACoarseFillTile() {
        // Near first: the top level's fill tiles, then level 0. Level 0 must draw as soon as it is
        // shown, with the fill tile still drawing the ground beside it.
        World world = new World(5, 0, 0, 0, true);
        for (int i = 0; i < 16; i++) {
            world.shown.add(world.order.get(i));
        }
        long fine = world.order.get(16);
        world.shown.add(fine);
        long cellX = TileKey.x(fine);
        long cellZ = TileKey.z(fine);
        long fill = TileKey.of(4, (int) (cellX >> 4), (int) (cellZ >> 4));
        assertTrue(world.shown.contains(fill));
        assertEquals(Cover.WHOLE, world.cover.draws(fine, cellX, cellZ));
        assertEquals(Cover.NOT, world.cover.draws(fill, cellX, cellZ));
        // Three level 0 tiles away nothing finer is shown: the fill tile draws there.
        assertEquals(Cover.WHOLE, world.cover.draws(fill, cellX + 3, cellZ));
        assertFalse(world.cover.spare(fill));
        world.check("sixteen coarse fill tiles and one of level 0", 1);
    }

    @Test
    void sevenLevelsInTheLabsWorldArriveWithoutAHole() {
        for (boolean nearFirst : new boolean[] {false, true}) {
            World world = new World(7, -3000, 12000, 16384, nearFirst);
            int step = Math.max(1, world.order.size() / 30);
            for (int stored = 0; stored < world.order.size(); stored++) {
                world.shown.add(world.order.get(stored));
                if (stored % step == 0) {
                    world.check(stored + 1 + " stored, near first " + nearFirst, 4);
                }
            }
            world.check("all stored", 2);
            // What it costs: the fill tiles, 16 a level above the lowest, less the 4 this world
            // does not hold from here.
            int fill = 0;
            for (long key : world.order) {
                fill += world.rings.filling(key) ? 1 : 0;
            }
            assertEquals(6 * 16 - 4, fill);
        }
    }

    /** A camera that has jumped: the old place's tiles kept, the new place's stored one at a time. */
    private static final class Jump implements Cover.Tiles {
        final Rings rings;
        final List<Long> order = new ArrayList<>();
        final Set<Long> wanted = new HashSet<>();
        final Set<Long> shown = new HashSet<>();
        final Set<Long> kept = new HashSet<>();
        final Cover cover;
        long minX = Long.MAX_VALUE, minZ = Long.MAX_VALUE, maxX = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;

        Jump(int levels, long fromX, long fromZ, long toX, long toZ, boolean nearFirst) {
            // The old place, settled: every tile but the spare fill tiles stored.
            World old = new World(levels, fromX, fromZ, 0, nearFirst);
            Set<Long> stored = new HashSet<>();
            for (long key : old.order) {
                if (!old.rings.filling(key)) {
                    stored.add(key);
                }
            }
            rings = new Rings(384, levels);
            rings.overlap(true);
            rings.fill(true);
            rings.nearFirst(nearFirst);
            rings.select(toX, toZ);
            for (int i = 0; i < rings.count(); i++) {
                order.add(rings.tile(i));
            }
            wanted.addAll(order);
            for (long key : stored) {
                (wanted.contains(key) ? shown : kept).add(key);
                long side = TileKey.side(TileKey.level(key)) / 64;
                minX = Math.min(minX, TileKey.originX(key) / 64);
                minZ = Math.min(minZ, TileKey.originZ(key) / 64);
                maxX = Math.max(maxX, TileKey.originX(key) / 64 + side);
                maxZ = Math.max(maxZ, TileKey.originZ(key) / 64 + side);
            }
            for (long key : order) {
                long side = TileKey.side(TileKey.level(key)) / 64;
                minX = Math.min(minX, TileKey.originX(key) / 64);
                minZ = Math.min(minZ, TileKey.originZ(key) / 64);
                maxX = Math.max(maxX, TileKey.originX(key) / 64 + side);
                maxZ = Math.max(maxZ, TileKey.originZ(key) / 64 + side);
            }
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

        @Override
        public boolean kept(long key) {
            return kept.contains(key);
        }

        /** Every place under any stored tile, kept or shown, is drawn once, or twice in a wanted band. */
        void check(String when) {
            int top = rings.levels() - 1;
            for (long z = minZ; z < maxZ; z++) {
                for (long x = minX; x < maxX; x++) {
                    int whole = 0;
                    int fading = 0;
                    int under = 0;
                    for (int level = top; level >= 0; level--) {
                        long key = TileKey.of(level, (int) (x >> level), (int) (z >> level));
                        if (shown.contains(key)) {
                            under++;
                            int draws = cover.draws(key, x, z);
                            whole += draws == Cover.WHOLE ? 1 : 0;
                            fading += draws == Cover.FADING ? 1 : 0;
                        } else if (kept.contains(key)) {
                            under++;
                            whole += cover.keptDraws(key, x, z) ? 1 : 0;
                        }
                    }
                    if (under > 0 && (whole != 1 || fading > 1)) {
                        fail(when + ": place " + x + " " + z + " under " + under + " stored tiles is drawn whole by "
                            + whole + " and fading by " + fading);
                    }
                }
            }
        }
    }

    @Test
    void afterAJumpKeptTilesGiveWayPlaceByPlaceAndLeaveNoHole() {
        long[][] jumps = {{0, 0, 2000, -72}, {1000, -3000, 1000 + 96, -3000}, {-4991, 12072, -3000, 12000},
            {0, 0, 40000, 40000}};
        for (boolean nearFirst : new boolean[] {false, true}) {
            for (long[] j : jumps) {
                Jump jump = new Jump(4, j[0], j[1], j[2], j[3], nearFirst);
                String name = "jump " + j[0] + " " + j[1] + " to " + j[2] + " " + j[3] + ", near first " + nearFirst;
                jump.check(name + ", nothing new stored");
                int stored = 0;
                for (long key : jump.order) {
                    if (jump.shown.contains(key)) {
                        continue;
                    }
                    jump.shown.add(key);
                    if (++stored % 9 == 0) {
                        jump.check(name + ", " + stored + " new tiles stored");
                    }
                }
                jump.check(name + ", all stored");
                // With everything wanted stored, no kept tile draws anything where a wanted tile lies.
                for (long key : jump.kept) {
                    long x = TileKey.originX(key) / 64;
                    long z = TileKey.originZ(key) / 64;
                    boolean covered = jump.rings.inSquare(TileKey.of(0, (int) x, (int) z))
                        || jump.shown.contains(TileKey.of(3, (int) (x >> 3), (int) (z >> 3)));
                    if (covered) {
                        assertFalse(jump.cover.keptDraws(key, x, z), name + ": a kept tile over a stored one");
                    }
                }
            }
        }
    }
}
