package cc.orthrus.quadrature.core.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FrameTailTest {

    private static final long MS = 1_000_000L;

    @Test
    void theTailIsTheGovernorsNinetyFifthPercentileOfTheLastHundredAndTwenty() {
        FrameTail tail = new FrameTail();
        FrameTail.Reading reading = new FrameTail.Reading();
        tail.read(reading, 20, 25);
        assertEquals(0, reading.frames);
        assertEquals(0.0, reading.tailMs);
        // The governor's own cases: one long frame in 120 is under the tail; eight are not.
        for (int i = 0; i < 119; i++) {
            tail.frame(5 * MS, 0);
        }
        tail.frame(90 * MS, 0);
        tail.read(reading, 20, 25);
        assertEquals(120, reading.frames);
        assertEquals(5.0, reading.tailMs, 1e-9);
        assertEquals(90.0, reading.worstMs, 1e-9);
        assertEquals(1, reading.overBudget);
        for (int i = 0; i < 120; i++) {
            tail.frame((i % 10 == 0 && i < 80 ? 40 : 10) * MS, 0);
        }
        tail.read(reading, 20, 25);
        assertEquals(40.0, reading.tailMs, 1e-9);
        assertEquals(8, reading.overBudget);
        assertEquals(8, reading.overRaise);
        // Six in 120 are still under it: the tail is the 114th smallest.
        tail.clear();
        for (int i = 0; i < 120; i++) {
            tail.frame((i < 6 ? 40 : 10) * MS, 0);
        }
        tail.read(reading, 20, 25);
        assertEquals(10.0, reading.tailMs, 1e-9);
        tail.frame(40 * MS, 0);
        tail.read(reading, 20, 25);
        // The oldest long frame was written over: six again.
        assertEquals(10.0, reading.tailMs, 1e-9);
        assertEquals(6, reading.overBudget);
    }

    @Test
    void longFramesAreCountedWithTheTicksTheyHeld() {
        FrameTail tail = new FrameTail();
        FrameTail.Reading reading = new FrameTail.Reading();
        for (int i = 0; i < 60; i++) {
            boolean ticked = i % 6 == 0;
            // Every sixth frame holds a tick; three of those are long by it, and one long frame holds none.
            long tick = ticked ? (i < 18 ? 22 : 2) * MS : 0;
            long interval = 8 * MS + tick + (i == 31 ? 30 * MS : 0);
            tail.frame(interval, tick);
        }
        tail.read(reading, 20, 25);
        assertEquals(60, reading.frames);
        assertEquals(10, reading.withTick);
        assertEquals(22.0, reading.longestTickMs, 1e-9);
        assertEquals(4, reading.overBudget);
        assertEquals(3, reading.overBudgetWithTick);
        assertEquals(22.0, reading.longestTickOverBudgetMs, 1e-9);
        assertEquals(38.0, reading.worstMs, 1e-9);
        // 57th of 60 sorted: the three ticked long frames at 30 and one at 38 are the top four.
        assertEquals(30.0, reading.tailMs, 1e-9);
    }
}
