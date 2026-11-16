package cc.orthrus.quadrature.core.scene;

/**
 * A cube's cells as a shader reads their states: for each cell one byte, an index into the cube's own
 * list of the states it holds, at most 256 of them.
 *
 * <p>A cube holding more than 256 states is clamped: the cells of every state past the 255th listed
 * are given the last place in the list, which holds the 256th state met. A cube has 4,096 cells and
 * terrain rarely puts more than a few dozen states in one, so this is counted, not designed around.
 */
public final class ScenePalette {

    /** The most states a cube's list holds. */
    public static final int STATES = 256;
    /** The bytes of a cube's indices and list together: a byte a cell, then two bytes a state. */
    public static final int BYTES = LightScene.CELLS + STATES * 2;

    private static final int TABLE = 1024;

    private final short[] keys = new short[TABLE];
    private final short[] values = new short[TABLE];
    private final boolean[] used = new boolean[TABLE];
    /** Where each state met was put in the table; half the table at most, past which states are no longer told apart. */
    private final int[] touched = new int[TABLE / 2];

    /**
     * Lists the states a cube holds, in the order they are met, and gives each cell its state's place.
     *
     * @param cells   the state of each cell, in the scene's order
     * @param indices filled with a place in the list for each cell
     * @param states  filled with the list; entries past the count returned are zeroed
     * @return how many states the cube holds: more than {@link #STATES} if it was clamped, and past 512
     *         a count that is at least the true one
     */
    public int build(short[] cells, byte[] indices, short[] states) {
        int count = 0;
        int distinct = 0;
        short lastState = 0;
        int lastIndex = -1;
        for (int cell = 0; cell < LightScene.CELLS; cell++) {
            short state = cells[cell];
            if (lastIndex >= 0 && state == lastState) {
                // Neighboring cells mostly hold the same state.
                indices[cell] = (byte) lastIndex;
                continue;
            }
            int at = (state * 0x9E37 >>> 3) & TABLE - 1;
            while (used[at] && keys[at] != state) {
                at = at + 1 & TABLE - 1;
            }
            int index;
            if (used[at]) {
                index = values[at] & 0xFFFF;
            } else {
                index = count < STATES ? count : STATES - 1;
                if (count < STATES) {
                    states[count++] = state;
                }
                if (distinct < touched.length) {
                    // Remembered even when it found no place of its own, so that it is counted once.
                    used[at] = true;
                    keys[at] = state;
                    values[at] = (short) index;
                    touched[distinct] = at;
                }
                distinct++;
            }
            indices[cell] = (byte) index;
            lastState = state;
            lastIndex = index;
        }
        for (int i = 0, kept = Math.min(distinct, touched.length); i < kept; i++) {
            used[touched[i]] = false;
        }
        java.util.Arrays.fill(states, count, STATES, (short) 0);
        return distinct;
    }
}
