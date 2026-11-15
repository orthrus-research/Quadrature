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

    /** Pages of ten granules, three at most, free ranges neither split nor joined. */
    private static ArenaAllocator small() {
        return new ArenaAllocator(10L * GRANULE, GRANULE, 3, false);
    }

    /** The same pages, free ranges split and joined. */
    private static ArenaAllocator joining() {
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
        ArenaAllocator arena = new ArenaAllocator(64L * GRANULE, GRANULE, 4, false);
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
        ArenaAllocator arena = new ArenaAllocator(10L * GRANULE, GRANULE, 2, false);
        assertEquals(0, arena.largestRoomBytes());
        assertTrue(arena.mayGrow());
        arena.allocate(7 * GRANULE);
        assertEquals(3L * GRANULE, arena.largestRoomBytes());
        arena.allocate(4 * GRANULE);
        assertEquals(6L * GRANULE, arena.largestRoomBytes());
        assertFalse(arena.mayGrow());
    }

    @Test
    void aFreedRangeIsSplitForASmallerRequest() {
        ArenaAllocator arena = joining();
        long three = arena.allocate(3 * GRANULE);
        arena.allocate(7 * GRANULE);
        arena.free(three, 3 * GRANULE, 1);
        arena.release(1);
        long two = arena.allocate(2 * GRANULE);
        assertEquals(0, ArenaAllocator.page(two));
        assertEquals(0, arena.offset(two));
        assertEquals((long) GRANULE, arena.reusableBytes());
        long one = arena.allocate(GRANULE);
        assertEquals(2L * GRANULE, arena.offset(one));
        assertEquals(1, arena.pages());
        assertEquals(0, arena.reusableBytes());
    }

    @Test
    void freedRangesJoinTheirNeighborsOnBothSides() {
        ArenaAllocator arena = joining();
        long a = arena.allocate(2 * GRANULE);
        long b = arena.allocate(3 * GRANULE);
        long c = arena.allocate(2 * GRANULE);
        arena.allocate(3 * GRANULE);
        arena.free(a, 2 * GRANULE, 1);
        arena.free(c, 2 * GRANULE, 1);
        arena.release(1);
        assertEquals(2, arena.freeRanges());
        assertEquals(2L * GRANULE, arena.largestRoomBytes());
        // Seven granules are free only once the range between the two is.
        arena.free(b, 3 * GRANULE, 2);
        arena.release(2);
        assertEquals(1, arena.freeRanges());
        assertEquals(7L * GRANULE, arena.largestRoomBytes());
        long seven = arena.allocate(7 * GRANULE);
        assertEquals(0, ArenaAllocator.page(seven));
        assertEquals(0, arena.offset(seven));
        assertEquals(1, arena.pages());
    }

    @Test
    void theSmallestRangeThatHoldsARequestIsTaken() {
        ArenaAllocator arena = joining();
        long four = arena.allocate(4 * GRANULE);
        arena.allocate(GRANULE);
        long two = arena.allocate(2 * GRANULE);
        arena.allocate(3 * GRANULE);
        arena.free(four, 4 * GRANULE, 1);
        arena.free(two, 2 * GRANULE, 1);
        arena.release(1);
        long fits = arena.allocate(2 * GRANULE);
        assertEquals(5L * GRANULE, arena.offset(fits));
        assertEquals(4L * GRANULE, arena.largestRoomBytes());
    }

    @Test
    void aRangeStillPendingIsNotJoined() {
        ArenaAllocator arena = joining();
        long a = arena.allocate(5 * GRANULE);
        long b = arena.allocate(5 * GRANULE);
        arena.free(a, 5 * GRANULE, 1);
        arena.free(b, 5 * GRANULE, 2);
        arena.release(1);
        assertEquals(5L * GRANULE, arena.largestRoomBytes());
        assertEquals(1, ArenaAllocator.page(arena.allocate(6 * GRANULE)));
        arena.release(2);
        assertEquals(10L * GRANULE, arena.largestRoomBytes());
    }

    @Test
    void manyAllocationsAndFreesNeverOverlapAndAccountForEveryGranule() {
        ArenaAllocator arena = new ArenaAllocator(64L * GRANULE, GRANULE, 6);
        java.util.Random random = new java.util.Random(2485331);
        java.util.ArrayList<long[]> held = new java.util.ArrayList<>();
        boolean[][] taken = new boolean[6][64];
        long used = 0;
        for (int step = 0; step < 20_000; step++) {
            if (held.isEmpty() || random.nextInt(5) < 3) {
                int granules = 1 + random.nextInt(12);
                long block = arena.allocate(granules * GRANULE - random.nextInt(GRANULE));
                if (block < 0) {
                    continue;
                }
                int page = ArenaAllocator.page(block);
                int start = (int) (arena.offset(block) / GRANULE);
                assertEquals((long) granules * GRANULE, arena.size(block));
                for (int g = start; g < start + granules; g++) {
                    assertFalse(taken[page][g]);
                    taken[page][g] = true;
                }
                used += granules;
                held.add(new long[] {block, granules});
            } else {
                long[] gone = held.remove(random.nextInt(held.size()));
                int page = ArenaAllocator.page(gone[0]);
                int start = (int) (arena.offset(gone[0]) / GRANULE);
                for (int g = start; g < start + gone[1]; g++) {
                    taken[page][g] = false;
                }
                used -= gone[1];
                arena.free(gone[0], (int) gone[1] * GRANULE, step);
                arena.release(step);
            }
            assertEquals(used * GRANULE, arena.usedBytes());
            assertEquals(arena.capacityBytes(), arena.usedBytes() + arena.reusableBytes());
        }
        for (long[] block : held) {
            arena.free(block[0], (int) block[1] * GRANULE, 30_000);
        }
        arena.release(30_000);
        assertEquals(0, arena.usedBytes());
        // Everything given back has joined into one range a page.
        assertEquals(arena.pages(), arena.freeRanges());
        assertEquals(64L * GRANULE, arena.largestRoomBytes());
    }
}