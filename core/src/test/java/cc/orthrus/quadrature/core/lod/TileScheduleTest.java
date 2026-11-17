package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class TileScheduleTest {

    private static final int REACH = 384;

    private static List<Long> drain(TileSchedule schedule, int limit) {
        List<Long> order = new ArrayList<>();
        for (long key = schedule.next(limit); key != -1; key = schedule.next(limit)) {
            order.add(key);
        }
        return order;
    }

    private static void finish(TileSchedule schedule, long key) {
        assertTrue(schedule.built(key, schedule.generation()));
        schedule.placed(key);
    }

    @Test
    void tilesAreHandedOutCoarsestFirstThenNearestAndOnlyOnce() {
        Rings rings = new Rings(REACH, 4);
        rings.select(1000, -2000);
        TileSchedule schedule = new TileSchedule();
        schedule.want(rings);
        List<Long> order = drain(schedule, Integer.MAX_VALUE);
        assertEquals(rings.count(), order.size());
        for (int i = 0; i < order.size(); i++) {
            assertEquals(rings.tile(i), order.get(i));
        }
        assertEquals(order.size(), schedule.building());
        assertEquals(-1, schedule.next(Integer.MAX_VALUE));
        // Selecting again does not hand out what is already being built.
        schedule.want(rings);
        assertEquals(-1, schedule.next(Integer.MAX_VALUE));
    }

    @Test
    void noMoreAreBuiltAtOnceThanTheLimit() {
        Rings rings = new Rings(REACH, 2);
        rings.select(0, 0);
        TileSchedule schedule = new TileSchedule();
        schedule.want(rings);
        long first = schedule.next(2);
        long second = schedule.next(2);
        assertTrue(first != -1 && second != -1);
        assertEquals(-1, schedule.next(2));
        finish(schedule, first);
        assertTrue(schedule.next(2) != -1);
        assertEquals(-1, schedule.next(2));
        int[] count = new int[2];
        schedule.count(1, count);
        assertEquals(108, count[0]);
        assertEquals(1, count[1]);
    }

    @Test
    void aTileThatLeavesTheRingsWhileBeingBuiltIsRefusedAndAQueuedOneIsDropped() {
        Rings rings = new Rings(REACH, 3);
        rings.select(0, 0);
        TileSchedule schedule = new TileSchedule();
        schedule.want(rings);
        long building = schedule.next(8);
        assertTrue(schedule.wanted(building));
        // The camera goes far away: nothing of the first selection is wanted.
        rings.select(1_000_000, 1_000_000);
        schedule.want(rings);
        assertFalse(schedule.wanted(building));
        assertFalse(schedule.built(building, schedule.generation()));
        assertEquals(0, schedule.building());
        // Only tiles of the new selection are handed out.
        Set<Long> selection = new HashSet<>();
        for (int i = 0; i < rings.count(); i++) {
            selection.add(rings.tile(i));
        }
        List<Long> order = drain(schedule, Integer.MAX_VALUE);
        assertEquals(selection.size(), order.size());
        assertTrue(selection.containsAll(order));
    }

    @Test
    void aStoredTileIsKeptUntilTheTilesOverItsGroundAreStored() {
        Rings rings = new Rings(REACH, 2);
        rings.select(0, 0);
        TileSchedule schedule = new TileSchedule();
        schedule.want(rings);
        for (long key : drain(schedule, Integer.MAX_VALUE)) {
            finish(schedule, key);
        }
        assertTrue(schedule.takeReleased().isEmpty());
        // Move two level-1 tiles east: some level-1 tiles turn into level-0 ground and the reverse.
        Set<Long> before = new HashSet<>();
        for (int i = 0; i < rings.count(); i++) {
            before.add(rings.tile(i));
        }
        rings.select(256, 0);
        schedule.want(rings);
        Set<Long> after = new HashSet<>();
        for (int i = 0; i < rings.count(); i++) {
            after.add(rings.tile(i));
        }
        Set<Long> left = new HashSet<>(before);
        left.removeAll(after);
        assertFalse(left.isEmpty());
        // Nothing new is stored yet: every tile that left still covers ground no stored tile does,
        // unless nothing wanted lies over it at all.
        List<Long> released = schedule.takeReleased();
        for (long key : released) {
            assertTrue(left.contains(key));
            for (long wanted : after) {
                assertFalse(TileKey.covers(key, wanted) || TileKey.covers(wanted, key));
            }
        }
        Set<Long> gone = new HashSet<>(released);
        released.clear();
        for (long key : left) {
            assertEquals(!gone.contains(key), schedule.drawn(key));
        }
        assertEquals(left.size() - gone.size(), schedule.leaving());
        // Build the new tiles: each tile that left is let go once, and only once its ground is covered.
        for (long key : drain(schedule, Integer.MAX_VALUE)) {
            finish(schedule, key);
            for (long freed : schedule.takeReleased()) {
                assertTrue(left.contains(freed));
                assertTrue(gone.add(freed), "let go twice");
                for (long wanted : after) {
                    if (TileKey.covers(freed, wanted) || TileKey.covers(wanted, freed)) {
                        assertTrue(schedule.drawn(wanted));
                    }
                }
            }
            schedule.takeReleased().clear();
        }
        assertEquals(left, gone);
        assertEquals(0, schedule.leaving());
        for (long key : after) {
            assertTrue(schedule.drawn(key));
        }
    }

    @Test
    void aFailedTileIsLeftAloneUntilItReturnsAndARetriedOneIsQueuedAgain() {
        Rings rings = new Rings(REACH, 1);
        rings.select(0, 0);
        TileSchedule schedule = new TileSchedule();
        schedule.want(rings);
        long first = schedule.next(1);
        schedule.failed(first, schedule.generation(), false);
        long second = schedule.next(1);
        assertTrue(second != first);
        schedule.failed(second, schedule.generation(), true);
        assertEquals(second, schedule.next(1));
        // A result for a tile that already failed is refused.
        assertFalse(schedule.built(first, schedule.generation()));
        // Leaving and returning queues the failed tile again.
        rings.select(1_000_000, 0);
        schedule.want(rings);
        rings.select(0, 0);
        schedule.want(rings);
        assertEquals(first, schedule.next(8));
        assertThrows(IllegalStateException.class, () -> schedule.placed(first));
    }

    @Test
    void aNewWorldLetsEveryStoredTileGoAndRefusesWhatIsOnItsWay() {
        Rings rings = new Rings(REACH, 2);
        rings.select(0, 0);
        TileSchedule schedule = new TileSchedule();
        schedule.want(rings);
        long stored = schedule.next(8);
        finish(schedule, stored);
        long onItsWay = schedule.next(8);
        long generation = schedule.generation();
        schedule.reset();
        assertEquals(List.of(stored), schedule.takeReleased());
        schedule.takeReleased().clear();
        assertFalse(schedule.built(onItsWay, generation));
        schedule.failed(onItsWay, generation, true);
        assertEquals(0, schedule.building());
        assertFalse(schedule.drawn(stored));
        assertEquals(-1, schedule.next(8));
        // The same ground in the new world is built afresh.
        schedule.want(rings);
        assertEquals(rings.tile(0), schedule.next(8));
    }
}
