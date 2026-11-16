package cc.orthrus.quadrature.core.view;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class StateGuardTest {

    /** State as numbers, with a record of what was written, and one slot whose write disturbs another. */
    private static final class Fake implements StateGuard.Access {
        final int[] values = {7, 0, 1, 515, 42, 3};
        final List<Integer> written = new ArrayList<>();

        @Override
        public int read(int slot) {
            return values[slot];
        }

        @Override
        public void write(int slot, int value) {
            written.add(slot);
            values[slot] = value;
            if (slot == 4) {
                // As binding a buffer to an indexed point also moves the general one.
                values[5] = value;
            }
        }
    }

    @Test
    void everySlotReadsAfterwardsAsItWasFound() {
        Fake state = new Fake();
        int[] before = state.values.clone();
        StateGuard guard = new StateGuard(0, 1, 2, 3, 4, 5);
        guard.save(state);
        state.values[0] = 99;
        state.values[2] = 0;
        state.values[3] = 518;
        state.values[4] = 0;
        state.values[5] = 0;
        assertEquals(5, guard.restore(state));
        assertArrayEquals(before, state.values);
        // In the list's order, so the general point is written after the indexed one disturbed it.
        assertEquals(List.of(0, 2, 3, 4, 5), state.written);
    }

    @Test
    void whatWasNotChangedIsNotWritten() {
        Fake state = new Fake();
        StateGuard guard = new StateGuard(0, 1, 2, 3, 4, 5);
        guard.save(state);
        state.values[1] = 1;
        assertEquals(1, guard.restore(state));
        assertEquals(List.of(1), state.written);
    }

    @Test
    void aSlotOutsideTheListIsLeftAsTheWorkLeftIt() {
        Fake state = new Fake();
        StateGuard guard = new StateGuard(0, 1);
        guard.save(state);
        state.values[0] = 5;
        state.values[3] = 5;
        guard.restore(state);
        assertEquals(7, state.values[0]);
        assertEquals(5, state.values[3]);
    }

    @Test
    void restoringTwiceOrWithoutSavingWritesNothingAndTheFirstReadingStands() {
        Fake state = new Fake();
        StateGuard guard = new StateGuard(0, 1, 2);
        assertEquals(0, guard.restore(state));
        guard.save(state);
        state.values[0] = 5;
        // A second save inside the work does not take the changed value for the found one.
        guard.save(state);
        assertEquals(1, guard.restore(state));
        assertEquals(7, state.values[0]);
        state.values[0] = 6;
        assertEquals(0, guard.restore(state));
        assertEquals(6, state.values[0]);
        assertEquals(3, guard.size());
    }
}
