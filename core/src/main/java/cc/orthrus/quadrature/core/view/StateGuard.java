package cc.orthrus.quadrature.core.view;

/**
 * Leaves a list of state as it was found: each value is read before a piece of work and, after
 * it, written back where it reads differently. For a pass that draws inside another renderer's
 * frame and owes it every binding and switch it touched.
 *
 * <p>The state itself is behind {@link Access}, a number a slot, so the rule can be held by a
 * test without a driver. Values are written back in the order of the list, which the list's
 * maker chooses where one slot's write disturbs another's: the disturbed one comes later.
 */
public final class StateGuard {

    /** The state a guard keeps, by slot. */
    public interface Access {

        int read(int slot);

        void write(int slot, int value);
    }

    private final int[] slots;
    private final int[] found;
    private boolean held;

    public StateGuard(int... slots) {
        this.slots = slots.clone();
        this.found = new int[slots.length];
    }

    /** Reads every slot. A second call before {@link #restore} keeps the first reading. */
    public void save(Access state) {
        if (held) {
            return;
        }
        for (int i = 0; i < slots.length; i++) {
            found[i] = state.read(slots[i]);
        }
        held = true;
    }

    /**
     * Writes back every slot that no longer reads as it was found, in the list's order. Nothing
     * without a {@link #save} before it.
     *
     * @return how many slots were written
     */
    public int restore(Access state) {
        if (!held) {
            return 0;
        }
        held = false;
        int written = 0;
        for (int i = 0; i < slots.length; i++) {
            if (state.read(slots[i]) != found[i]) {
                state.write(slots[i], found[i]);
                written++;
            }
        }
        return written;
    }

    /** How many slots the guard keeps. */
    public int size() {
        return slots.length;
    }
}
