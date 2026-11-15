package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class ArenaAllocatorTest {

    private static final int GRANULE = 4088;

    /** Pages of ten granules, three at most. */
    private static ArenaAllocator small() {
        return new ArenaAllocator(10L * GRANULE, GRANULE, 3);
    }

    @Test
    void sizesAreRoundedUpToWholeGranules() {
        ArenaAllocator arena = small();
        long one = arena.allocate(1);
        long exact = arena.allocate(GRANULE);
        long over = arena.allocate(GRANULE + 1);
        assertEquals(GRANULE, arena.size(one));
        assertEquals(GRANULE, arena.size(exact));
        assertEquals(2L * GRANULE, arena.size(over));
        assertEquals(0, arena.offset(one));
        assertEquals(GRANULE, arena.offset(exact));
        assertEquals(2L * GRANULE, arena.offset(over));
        assertEquals(4L * GRANULE, arena.usedBytes());
        assertEquals(4L * GRANULE - (1 + GRANULE + GRANULE + 1), arena.wastedBytes());
    }

    @Test
    void blocksNeverOverlap() {
        ArenaAllocator arena = small();
        Set<Long> granules = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            long block = arena.allocate(GRANULE * (1 + i % 3));
            for (long at = arena.offset(block); at < arena.offset(block) + arena.size(block); at += GRANULE) {
                assertTrue(granules.add((long) ArenaAllocator.page(block) << 32 | at / GRANULE));
            }
        }
    }

    @Test
    void aPageIsAddedWhenTheLastIsFull() {
        ArenaAllocator arena = small();
        assertEquals(0, arena.pages());
        long first = arena.allocate(6 * GRANULE);
        assertEquals(1, arena.pages());
        long second = arena.allocate(6 * GRANULE);
        assertEquals(2, arena.pages());
        assertEquals(0, ArenaAllocator.page(first));
        assertEquals(1, ArenaAllocator.page(second));
        assertEquals(0, arena.offset(second));
        // The room left at the end of the first page is still used.
        long fits = arena.allocate(4 * GRANULE);
        assertEquals(0, ArenaAllocator.page(fits));
        assertEquals(20L * GRANULE, arena.capacityBytes());
    }

    @Test
    void itRefusesWhatCannotFit() {
        ArenaAllocator arena = small();
        assertEquals(-1, arena.allocate(11 * GRANULE));
        for (int i = 0; i < 3; i++) {
            assertNotEquals(-1, arena.allocate(10 * GRANULE));
        }
        assertEquals(3, arena.pages());
        assertEquals(-1, arena.allocate(1));
        assertEquals(30L * GRANULE, arena.usedBytes());
        assertThrows(IllegalArgumentException.class, () -> arena.allocate(0));
    }

    @Test
    void aFreedBlockIsReusedOnlyAfterItsFrameHasFinished() {
        ArenaAllocator arena = small();
        long block = arena.allocate(3 * GRANULE);
        arena.allocate(7 * GRANULE);
        arena.free(block, 3 * GRANULE, 5);
        assertEquals(3L * GRANULE, arena.pendingBytes());
        assertEquals(7L * GRANULE, arena.usedBytes());

        // Not yet: the frame it was freed in has not finished, so a new page is taken instead.
        arena.release(4);
        long elsewhere = arena.allocate(3 * GRANULE);
        assertEquals(1, ArenaAllocator.page(elsewhere));

        arena.release(5);
        assertEquals(0, arena.pendingBytes());
        assertEquals(3L * GRANULE, arena.reusableBytes());
        long again = arena.allocate(3 * GRANULE - 100);
        assertEquals(block, again);
        assertEquals(0, arena.reusableBytes());
    }

    @Test
    void aFreedBlockServesOnlyItsOwnClass() {
        ArenaAllocator arena = small();
        long three = arena.allocate(3 * GRANULE);
        arena.allocate(7 * GRANULE);
        arena.free(three, 3 * GRANULE, 1);
        arena.release(1);
        // Two granules would fit in the freed three, but ranges are not split.
        long two = arena.allocate(2 * GRANULE);
        assertEquals(1, ArenaAllocator.page(two));
        assertEquals(3L * GRANULE, arena.reusableBytes());
    }

    @Test
    void blocksFreedInLaterFramesStayPending() {
        ArenaAllocator arena = small();
        long a = arena.allocate(GRANULE);
        long b = arena.allocate(GRANULE);
        arena.free(a, GRANULE, 10);
        arena.free(b, GRANULE, 12);
        arena.release(11);
        assertEquals((long) GRANULE, arena.pendingBytes());
        assertEquals((long) GRANULE, arena.reusableBytes());
        arena.release(12);
        assertEquals(0, arena.pendingBytes());
        assertEquals(2L * GRANULE, arena.reusableBytes());
    }

    @Test
    void accountingReturnsToNothingWhenEverythingIsFreed() {
        ArenaAllocator arena = new ArenaAllocator(64L * GRANULE, GRANULE, 4);
        long[] blocks = new long[40];
        int[] sizes = new int[40];
        for (int i = 0; i < blocks.length; i++) {
            sizes[i] = 500 + i * 733;
            blocks[i] = arena.allocate(sizes[i]);
        }
        long used = arena.usedBytes();
        assertTrue(arena.wastedBytes() > 0 && arena.wastedBytes() < 40L * GRANULE);
        for (int i = 0; i < blocks.length; i++) {
            arena.free(blocks[i], sizes[i], i);
        }
        assertEquals(0, arena.usedBytes());
        assertEquals(0, arena.wastedBytes());
        assertEquals(used, arena.pendingBytes());
        arena.release(39);
        assertEquals(used, arena.reusableBytes());
        assertEquals(arena.pages() * arena.pageBytes(), arena.capacityBytes());
    }

    @Test
    void aPageMustBeAWholeNumberOfGranules() {
        assertThrows(IllegalArgumentException.class, () -> new ArenaAllocator(10L * GRANULE + 1, GRANULE, 1));
    }

    @Test
    void theLargestRoomFallsAsPagesFillAndRisesWithANewPage() {
        ArenaAllocator arena = new ArenaAllocator(10L * GRANULE, GRANULE, 2);
        assertEquals(0, arena.largestRoomBytes());
        assertTrue(arena.mayGrow());
        arena.allocate(7 * GRANULE);
        assertEquals(3L * GRANULE, arena.largestRoomBytes());
        arena.allocate(4 * GRANULE);
        assertEquals(6L * GRANULE, arena.largestRoomBytes());
        assertFalse(arena.mayGrow());
    }
}