package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

class CubeSlotsTest {

    @Test
    void aCubeKeepsItsSlotWhileItIsHeld() {
        CubeSlots slots = new CubeSlots();
        int a = slots.acquire(CubeKey.of(1, 2, 3));
        int b = slots.acquire(CubeKey.of(-1, 2, 3));
        assertNotEquals(a, b);
        assertEquals(a, slots.acquire(CubeKey.of(1, 2, 3)));
        assertEquals(a, slots.find(CubeKey.of(1, 2, 3)));
        assertEquals(-1, slots.find(CubeKey.of(9, 9, 9)));
        assertEquals(2, slots.held());
        assertEquals(CubeKey.of(-1, 2, 3), slots.key(b));
    }

    @Test
    void aReleasedSlotIsTakenByTheNextCube() {
        CubeSlots slots = new CubeSlots();
        int a = slots.acquire(CubeKey.of(0, 0, 0));
        slots.acquire(CubeKey.of(1, 0, 0));
        assertEquals(a, slots.release(CubeKey.of(0, 0, 0)));
        assertEquals(-1, slots.find(CubeKey.of(0, 0, 0)));
        assertEquals(-1, slots.release(CubeKey.of(0, 0, 0)));
        assertEquals(a, slots.acquire(CubeKey.of(5, 5, 5)));
        assertEquals(2, slots.capacity());
    }

    @Test
    void aSlotHoldsItsMeshUntilItIsClearedOrGivenUp() {
        CubeSlots slots = new CubeSlots();
        int slot = slots.acquire(CubeKey.of(4, 4, 4));
        assertEquals(-1, slots.block(slot));
        assertEquals(0, slots.count(slot, 1));
        slots.set(slot, 77L, 2800, 10, 40, 50, 60, 110, 0);
        assertEquals(77L, slots.block(slot));
        assertEquals(2800, slots.bytes(slot));
        assertEquals(10, slots.first(slot, 0));
        assertEquals(40, slots.count(slot, 0));
        assertEquals(50, slots.first(slot, 1));
        assertEquals(60, slots.count(slot, 1));
        assertEquals(0, slots.count(slot, 2));
        slots.clear(slot);
        assertEquals(-1, slots.block(slot));
        assertEquals(0, slots.count(slot, 0));

        slots.set(slot, 78L, 28, 0, 1, 0, 0, 0, 0);
        slots.release(CubeKey.of(4, 4, 4));
        int again = slots.acquire(CubeKey.of(6, 6, 6));
        assertEquals(slot, again);
        assertEquals(-1, slots.block(again));
        assertEquals(0, slots.count(again, 0));
    }

    @Test
    void manyCubesComeAndGoWithoutLosingAny() {
        CubeSlots slots = new CubeSlots();
        Map<Long, Integer> expected = new HashMap<>();
        Random random = new Random(42);
        for (int step = 0; step < 60_000; step++) {
            long key = CubeKey.of(random.nextInt(40) - 20, random.nextInt(24) - 12, random.nextInt(40) - 20);
            if (random.nextInt(3) == 0) {
                Integer had = expected.remove(key);
                assertEquals(had == null ? -1 : had, slots.release(key));
            } else {
                int slot = slots.acquire(key);
                Integer had = expected.putIfAbsent(key, slot);
                if (had != null) {
                    assertEquals(had, slot);
                }
            }
        }
        assertEquals(expected.size(), slots.held());
        boolean[] taken = new boolean[slots.capacity()];
        for (Map.Entry<Long, Integer> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), slots.find(entry.getKey()));
            assertEquals(entry.getKey(), slots.key(entry.getValue()));
            assertTrue(!taken[entry.getValue()], "two cubes share a slot");
            taken[entry.getValue()] = true;
        }
        assertTrue(slots.capacity() <= 40 * 24 * 40);
    }
}
