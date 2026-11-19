package cc.orthrus.quadrature.core.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.orthrus.quadrature.core.handoff.HandoffSchedule.Phase;

import org.junit.jupiter.api.Test;

import java.util.List;

class HandoffScheduleTest {

    @Test
    void partsTakeTurnsInSegmentsAndRepeat() {
        HandoffSchedule schedule = HandoffSchedule.parse("off, sync,FULL", 10, 2);
        assertEquals(List.of(Phase.OFF, Phase.SYNC, Phase.FULL), schedule.order());
        assertEquals(Phase.OFF, schedule.phase(0));
        assertEquals(Phase.OFF, schedule.phase(9));
        assertEquals(Phase.SYNC, schedule.phase(10));
        assertEquals(Phase.FULL, schedule.phase(29));
        assertEquals(Phase.OFF, schedule.phase(30));
        assertEquals(30, schedule.cycle());
    }

    @Test
    void theFirstFramesOfATurnAreNotMeasured() {
        HandoffSchedule schedule = HandoffSchedule.parse("off,full", 10, 2);
        assertFalse(schedule.measured(0));
        assertFalse(schedule.measured(1));
        assertTrue(schedule.measured(2));
        assertTrue(schedule.measured(9));
        assertFalse(schedule.measured(10));
        assertTrue(schedule.measured(12));
    }

    @Test
    void eachPartSaysWhatItRuns() {
        assertFalse(Phase.OFF.copies() || Phase.OFF.roundTrip() || Phase.OFF.evaluates());
        assertTrue(Phase.SYNC.roundTrip() && !Phase.SYNC.evaluates() && !Phase.SYNC.copies());
        assertTrue(Phase.STANDIN.roundTrip() && Phase.STANDIN.evaluates() && !Phase.STANDIN.copies());
        assertTrue(Phase.COPY.copies() && !Phase.COPY.roundTrip());
        assertTrue(Phase.FULL.copies() && Phase.FULL.roundTrip() && Phase.FULL.evaluates());
    }

    @Test
    void badSchedulesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> HandoffSchedule.parse("off,dlss", 10, 2));
        assertThrows(IllegalArgumentException.class, () -> HandoffSchedule.parse(" , ", 10, 2));
        assertThrows(IllegalArgumentException.class, () -> HandoffSchedule.parse("off", 4, 4));
        assertThrows(IllegalArgumentException.class, () -> HandoffSchedule.parse("off", 0, 0));
    }
}
