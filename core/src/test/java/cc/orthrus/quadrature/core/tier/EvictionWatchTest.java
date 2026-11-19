package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EvictionWatchTest {

    @Test
    void risingEvictionsWithFallingLowsStepDownOnce() {
        EvictionWatch watch = new EvictionWatch(0.2, 2, 3);
        assertFalse(watch.window(100, 90));
        assertFalse(watch.window(100, 100));
        assertEquals(100, watch.baseline());
        // Evictions rise: the first window is not enough by itself.
        assertFalse(watch.window(110, 70));
        assertTrue(watch.window(120, 70));
        assertEquals(1, watch.steps());
        // Waits three windows, whatever they hold.
        assertFalse(watch.window(130, 40));
        assertFalse(watch.window(140, 40));
        assertFalse(watch.window(150, 40));
        // The baseline is found again before another step.
        assertFalse(watch.window(160, 40));
        assertFalse(watch.window(170, 40));
        assertEquals(1, watch.steps());
    }

    @Test
    void evictionsWithoutAFallInTheLowsDoNothing() {
        EvictionWatch watch = new EvictionWatch(0.2, 2, 3);
        watch.window(0, 100);
        watch.window(0, 100);
        for (int i = 1; i <= 10; i++) {
            assertFalse(watch.window(i * 10, 85));
        }
        assertEquals(0, watch.steps());
    }

    @Test
    void aFallWithoutEvictionsDoesNothing() {
        EvictionWatch watch = new EvictionWatch(0.2, 2, 3);
        // The first window has nothing to compare its count with: it sets no baseline.
        watch.window(5, 120);
        assertTrue(Double.isNaN(watch.baseline()));
        watch.window(5, 100);
        for (int i = 0; i < 10; i++) {
            assertFalse(watch.window(5, 30));
        }
        assertEquals(0, watch.steps());
        // A quiet window that runs slower does not lower the baseline.
        assertEquals(100, watch.baseline());
    }

    @Test
    void aQuietWindowBreaksTheRun() {
        EvictionWatch watch = new EvictionWatch(0.2, 2, 3);
        watch.window(0, 100);
        watch.window(0, 100);
        assertFalse(watch.window(10, 50));
        assertFalse(watch.window(10, 50));
        assertFalse(watch.window(20, 50));
        assertTrue(watch.window(30, 50));
    }
}
