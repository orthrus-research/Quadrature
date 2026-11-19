package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ClockSettleTest {

    @Test
    void aClimbingClockIsNotSettledUntilItsTimesAgree() {
        ClockSettle settle = new ClockSettle();
        // The load's time falls as the clock climbs from about 1,300 to 1,950 MHz, then holds.
        double[] climb = {2.75, 2.70, 2.60, 2.45, 2.30, 2.10, 1.95, 1.85, 1.83};
        for (double millis : climb) {
            settle.time(millis);
            assertFalse(settle.settled());
        }
        settle.time(1.84);
        settle.time(1.83);
        assertFalse(settle.settled(), "eleven times are too few");
        settle.time(1.84);
        assertTrue(settle.settled());
        assertEquals(1.835, settle.latestMedian(), 1e-9);
    }

    @Test
    void aClockThatKeepsMovingNeverSettles() {
        ClockSettle settle = new ClockSettle();
        for (int i = 0; i < 40; i++) {
            settle.time(i % 2 == 0 ? 1.8 : 2.0);
        }
        assertFalse(settle.settled());
        assertEquals(40, settle.count());
    }
}
