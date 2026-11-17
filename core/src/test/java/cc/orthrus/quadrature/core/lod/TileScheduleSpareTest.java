package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class TileScheduleSpareTest {

    private static Rings rings(long x, long z) {
        Rings rings = new Rings(384, 2);
        rings.overlap(true);
        rings.fill(true);
        rings.select(x, z);
        return rings;
    }

    @Test
    void aSparedTileIsNotBuiltIsNotCountedAndHoldsNothingBack() {
        Rings rings = rings(0, 0);
        TileSchedule schedule = new TileSchedule();
        schedule.want(rings);
        int[] count = new int[2];
        schedule.count(1, count);
        assertEquals(144, count[0]);
        // Every fill tile is spared: none is handed out, and none counts as wanted.
        Set<Long> built = new HashSet<>();
        long key;
        while ((key = schedule.next(1000, rings::filling)) != -1) {
            assertFalse(rings.filling(key));
            built.add(key);
        }
        assertEquals(128 + 144, built.size());
        schedule.count(1, count);
        assertEquals(128, count[0]);
        assertEquals(0, count[1]);
        for (long tile : built) {
            assertTrue(schedule.built(tile, schedule.generation()));
            schedule.placed(tile);
        }
        schedule.count(1, count);
        assertEquals(128, count[1]);
        // The camera moves two level 1 tiles: the tiles that left are kept only until the new
        // ones over their ground are stored, and the spared fill tiles of the new selection,
        // which are asked about again and spared again, do not keep them.
        Rings moved = rings(256, 0);
        schedule.want(moved);
        assertTrue(schedule.leaving() > 0);
        while ((key = schedule.next(1000, moved::filling)) != -1) {
            assertTrue(schedule.built(key, schedule.generation()));
            schedule.placed(key);
        }
        assertFalse(schedule.takeReleased().isEmpty());
        schedule.takeReleased().clear();
        assertEquals(0, schedule.leaving());
    }

    @Test
    void aStoredFillTileIsRetiredOnceAndBuiltAgainOnlyAfterANewSelection() {
        Rings rings = rings(0, 0);
        TileSchedule schedule = new TileSchedule();
        schedule.want(rings);
        long fill = schedule.next(1);
        assertTrue(rings.filling(fill), "the level's fill tiles come first");
        assertThrows(IllegalStateException.class, () -> schedule.retire(fill));
        assertTrue(schedule.built(fill, schedule.generation()));
        schedule.placed(fill);
        assertTrue(schedule.drawn(fill));
        schedule.retire(fill);
        assertFalse(schedule.drawn(fill));
        assertTrue(schedule.wanted(fill));
        long key;
        while ((key = schedule.next(1000)) != -1) {
            assertFalse(key == fill, "a retired tile is not handed out again");
        }
        // The same selection taken again asks about it again.
        schedule.want(rings);
        boolean again = false;
        while ((key = schedule.next(1000)) != -1) {
            again |= key == fill;
        }
        assertTrue(again);
    }
}
