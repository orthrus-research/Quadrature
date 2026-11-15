package cc.orthrus.quadrature.core.terrain;

import java.util.Arrays;

/**
 * One slot for every loaded cube, kept for as long as the cube stays loaded, and what the cube's mesh
 * occupies: its block in the arena and, for each layer, where its vertices start in the block's page
 * and how many there are. A slot's number is the cube's index into any table kept beside this one.
 *
 * <p>Not safe for use from more than one thread. Allocates only when it grows.
 */
public final class CubeSlots {

    /** The layers a mesh has. */
    public static final int LAYERS = 3;

    private static final long EMPTY = Long.MIN_VALUE;

    // Open addressing from cube key to slot.
    private long[] keys = new long[1024];
    private int[] values = new int[1024];
    private int held;

    private long[] slotKey = new long[512];
    private long[] block = new long[512];
    private int[] bytes = new int[512];
    private int[] first = new int[512 * LAYERS];
    private int[] count = new int[512 * LAYERS];
    private int[] spare = new int[64];
    private int spares;
    private int slots;

    public CubeSlots() {
        Arrays.fill(keys, EMPTY);
    }

    /** The cube's slot, taking one for it if it has none. */
    public int acquire(long key) {
        int found = find(key);
        if (found >= 0) {
            return found;
        }
        int slot;
        if (spares > 0) {
            slot = spare[--spares];
        } else {
            slot = slots++;
            if (slot == slotKey.length) {
                int size = slot * 2;
                slotKey = Arrays.copyOf(slotKey, size);
                block = Arrays.copyOf(block, size);
                bytes = Arrays.copyOf(bytes, size);
                first = Arrays.copyOf(first, size * LAYERS);
                count = Arrays.copyOf(count, size * LAYERS);
            }
        }
        slotKey[slot] = key;
        block[slot] = -1;
        bytes[slot] = 0;
        Arrays.fill(count, slot * LAYERS, slot * LAYERS + LAYERS, 0);
        if ((held + 1) * 2 > keys.length) {
            rehash(keys.length * 2);
        }
        put(key, slot);
        held++;
        return slot;
    }

    /** The cube's slot, or -1 if it has none. */
    public int find(long key) {
        int mask = keys.length - 1;
        for (int at = index(key, mask);; at = at + 1 & mask) {
            if (keys[at] == key) {
                return values[at];
            }
            if (keys[at] == EMPTY) {
                return -1;
            }
        }
    }

    /**
     * Gives the cube's slot up for another cube to take. Whatever the slot's mesh occupies is the
     * caller's to free first.
     *
     * @return the slot it had, or -1
     */
    public int release(long key) {
        int mask = keys.length - 1;
        int at = index(key, mask);
        while (keys[at] != key) {
            if (keys[at] == EMPTY) {
                return -1;
            }
            at = at + 1 & mask;
        }
        int slot = values[at];
        // Close the gap, so that every key after it is still found.
        for (int next = at + 1 & mask; keys[next] != EMPTY; next = next + 1 & mask) {
            int home = index(keys[next], mask);
            if ((next - home & mask) >= (next - at & mask)) {
                keys[at] = keys[next];
                values[at] = values[next];
                at = next;
            }
        }
        keys[at] = EMPTY;
        held--;
        if (spares == spare.length) {
            spare = Arrays.copyOf(spare, spares * 2);
        }
        spare[spares++] = slot;
        block[slot] = -1;
        Arrays.fill(count, slot * LAYERS, slot * LAYERS + LAYERS, 0);
        return slot;
    }

    /**
     * Records what a slot's mesh occupies.
     *
     * @param block the mesh's block in the arena
     * @param bytes the size the block was asked for with
     */
    public void set(int slot, long block, int bytes, int first0, int count0, int first1, int count1, int first2,
        int count2) {
        this.block[slot] = block;
        this.bytes[slot] = bytes;
        int at = slot * LAYERS;
        first[at] = first0;
        count[at] = count0;
        first[at + 1] = first1;
        count[at + 1] = count1;
        first[at + 2] = first2;
        count[at + 2] = count2;
    }

    /** Records that a slot has no mesh. */
    public void clear(int slot) {
        block[slot] = -1;
        bytes[slot] = 0;
        Arrays.fill(count, slot * LAYERS, slot * LAYERS + LAYERS, 0);
    }

    /** The slot's block in the arena, or -1 if it has no mesh. */
    public long block(int slot) {
        return block[slot];
    }

    public int bytes(int slot) {
        return bytes[slot];
    }

    /** The first vertex of a layer of the slot's mesh, counted from the start of the block's page. */
    public int first(int slot, int layer) {
        return first[slot * LAYERS + layer];
    }

    /** How many vertices a layer of the slot's mesh has. */
    public int count(int slot, int layer) {
        return count[slot * LAYERS + layer];
    }

    public long key(int slot) {
        return slotKey[slot];
    }

    /** How many cubes hold a slot. */
    public int held() {
        return held;
    }

    /** One more than the highest slot ever taken: the size a table kept beside this one needs. */
    public int capacity() {
        return slots;
    }

    private void put(long key, int slot) {
        int mask = keys.length - 1;
        int at = index(key, mask);
        while (keys[at] != EMPTY) {
            at = at + 1 & mask;
        }
        keys[at] = key;
        values[at] = slot;
    }

    private void rehash(int size) {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[size];
        values = new int[size];
        Arrays.fill(keys, EMPTY);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static int index(long key, int mask) {
        long mixed = key * 0x9E3779B97F4A7C15L;
        return (int) (mixed >>> 32) & mask;
    }
}
