package cc.orthrus.quadrature.core.scene;

import cc.orthrus.quadrature.core.terrain.CubeKey;

/**
 * The light scene in the form a ray can walk: a lookup with an entry for every cube position in a box
 * about an origin cube, and for each cube whose cells differ a brick of bits, one for each cell that
 * blocks light, with a coarser level over it.
 *
 * <p>The lookup is addressed by cube position wrapped by the box's size, so that moving the origin
 * moves nothing: only the entries of cubes that left or entered the box change. An entry says one of
 * three things: nothing is known of the cube; every cell of it holds one state, named in the entry, so
 * a cube of air or of stone needs no brick; or the cube has a brick, at a slot.
 *
 * <p>A brick is 4,096 bits in the scene's cell order, x fastest, then z, then y, followed by 512 bits
 * that say for each block of two cells a side whether any cell in it blocks light, in the same order.
 *
 * <p>This holds the data and says what changed; whoever owns the memory a shader reads copies the
 * changed entries and bricks there, a bounded number a frame. Used from one thread.
 */
public final class SceneBricks implements LightScene.Changes {

    /** An entry for a cube nothing is known of. */
    public static final int NOT_AVAILABLE = 0;
    /** In an entry's two highest bits: every cell holds the state in the low 16 bits. */
    public static final int UNIFORM = 1 << 30;
    /** In an entry's two highest bits: the cube's brick is at the slot in the low 24 bits. */
    public static final int BRICK = 2 << 30;
    private static final int KIND = 3 << 30;

    /** The 64-bit words of a brick's cell bits. */
    public static final int FINE_WORDS = LightScene.CELLS / 64;
    /** The 64-bit words of a brick's coarser bits. */
    public static final int COARSE_WORDS = 8;
    /** The 64-bit words of a brick. */
    public static final int BRICK_WORDS = FINE_WORDS + COARSE_WORDS;
    /** The bytes of a brick. */
    public static final int BRICK_BYTES = BRICK_WORDS * 8;

    private final LightScene scene;
    private final int across;
    private final int upward;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int[] lookup;
    private long[] bricks = new long[BRICK_WORDS * 64];
    private long[] cubeOfSlot = new long[64];
    /** Cubes to take from the scene, a ring. */
    private long[] waiting = new long[1024];
    private int waitingHead;
    private int waitingTail;
    private int[] freeSlots = new int[64];
    private int freeCount;
    private int slots;
    private int bricksHeld;
    private int mostBricksHeld;
    private int originX;
    private int originY;
    private int originZ;
    private boolean placed;
    private long seen = -1;
    private long generation;

    /** Entries and bricks changed and not yet taken. */
    private final long[] entryDirty;
    private int entriesDirty;
    private long[] brickDirty = new long[1];
    private int bricksDirty;
    private int entryCursor;
    private int brickCursor;

    /**
     * @param across the box reaches this many cubes to each side of the origin cube, horizontally
     * @param upward and this many up and down
     */
    public SceneBricks(LightScene scene, int across, int upward) {
        this.scene = scene;
        this.across = across;
        this.upward = upward;
        sizeX = 2 * across + 1;
        sizeY = 2 * upward + 1;
        sizeZ = sizeX;
        lookup = new int[sizeX * sizeY * sizeZ];
        entryDirty = new long[(lookup.length + 63) / 64];
    }

    // The box.

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    /** Where a cube's entry lies in the lookup: its position wrapped by the box's size, x fastest, then z, then y. */
    public int entryIndex(int cubeX, int cubeY, int cubeZ) {
        return (Math.floorMod(cubeY, sizeY) * sizeZ + Math.floorMod(cubeZ, sizeZ)) * sizeX + Math.floorMod(cubeX, sizeX);
    }

    private boolean inside(int cubeX, int cubeY, int cubeZ) {
        return placed && Math.abs(cubeX - originX) <= across && Math.abs(cubeY - originY) <= upward
            && Math.abs(cubeZ - originZ) <= across;
    }

    /**
     * Brings the lookup and bricks up to the scene as it is now, about an origin cube. Cubes that left
     * the box are dropped, cubes that entered are taken from the scene, and every cube the scene
     * changed since the last call is taken again.
     */
    public void update(int originCubeX, int originCubeY, int originCubeZ) {
        update(originCubeX, originCubeY, originCubeZ, Integer.MAX_VALUE);
    }

    /**
     * As {@link #update(int, int, int)}, taking no more than {@code most} cubes from the scene in this
     * call; the rest wait for the next. A cube that left the box or was removed from the scene is
     * dropped at once, whatever the bound, so the lookup never names what it no longer holds.
     */
    public void update(int originCubeX, int originCubeY, int originCubeZ, int most) {
        long before = marks;
        boolean moved = !placed || originCubeX != originX || originCubeY != originY || originCubeZ != originZ;
        if (moved) {
            int oldX = originX;
            int oldY = originY;
            int oldZ = originZ;
            boolean had = placed;
            if (had) {
                // What the new box does not hold is forgotten; its entry now stands for another cube.
                for (int y = oldY - upward; y <= oldY + upward; y++) {
                    for (int z = oldZ - across; z <= oldZ + across; z++) {
                        for (int x = oldX - across; x <= oldX + across; x++) {
                            if (Math.abs(x - originCubeX) > across || Math.abs(y - originCubeY) > upward
                                || Math.abs(z - originCubeZ) > across) {
                                set(entryIndex(x, y, z), NOT_AVAILABLE);
                            }
                        }
                    }
                }
            }
            originX = originCubeX;
            originY = originCubeY;
            originZ = originCubeZ;
            placed = true;
            for (int y = originY - upward; y <= originY + upward; y++) {
                for (int z = originZ - across; z <= originZ + across; z++) {
                    for (int x = originX - across; x <= originX + across; x++) {
                        if (!had || Math.abs(x - oldX) > across || Math.abs(y - oldY) > upward
                            || Math.abs(z - oldZ) > across) {
                            await(x, y, z);
                        }
                    }
                }
            }
        }
        seen = scene.changesSince(seen, this);
        for (int taken = 0; taken < most && waitingHead != waitingTail; taken++) {
            long key = waiting[waitingHead];
            waitingHead = (waitingHead + 1) % waiting.length;
            int x = CubeKey.x(key);
            int y = CubeKey.y(key);
            int z = CubeKey.z(key);
            if (inside(x, y, z)) {
                take(x, y, z);
            }
        }
        if (moved || marks != before) {
            generation++;
        }
    }

    /** Counts every change made to an entry or a brick. */
    private long marks;

    @Override
    public void everything() {
        for (int i = 0; i < lookup.length; i++) {
            set(i, NOT_AVAILABLE);
        }
    }

    @Override
    public void updated(int cubeX, int cubeY, int cubeZ, long cubeGeneration) {
        if (inside(cubeX, cubeY, cubeZ)) {
            await(cubeX, cubeY, cubeZ);
        }
    }

    /** Queues a cube to be taken from the scene. */
    private void await(int cubeX, int cubeY, int cubeZ) {
        int next = (waitingTail + 1) % waiting.length;
        if (next == waitingHead) {
            long[] larger = new long[waiting.length * 2];
            int count = 0;
            for (int i = waitingHead; i != waitingTail; i = (i + 1) % waiting.length) {
                larger[count++] = waiting[i];
            }
            waiting = larger;
            waitingHead = 0;
            waitingTail = count;
            next = count + 1;
        }
        waiting[waitingTail] = CubeKey.of(cubeX, cubeY, cubeZ);
        waitingTail = next;
    }

    /** Cubes queued to be taken from the scene. */
    public int cubesWaiting() {
        return (waitingTail - waitingHead + waiting.length) % waiting.length;
    }

    /** The cube whose brick is at a slot, as {@link CubeKey} packs it. */
    public long cubeOfSlot(int slot) {
        return cubeOfSlot[slot];
    }

    @Override
    public void removed(int cubeX, int cubeY, int cubeZ) {
        if (inside(cubeX, cubeY, cubeZ)) {
            set(entryIndex(cubeX, cubeY, cubeZ), NOT_AVAILABLE);
        }
    }

    /** Sets a cube's entry, and its brick if its cells differ, from the scene. */
    private void take(int cubeX, int cubeY, int cubeZ) {
        int index = entryIndex(cubeX, cubeY, cubeZ);
        if (!scene.has(cubeX, cubeY, cubeZ)) {
            set(index, NOT_AVAILABLE);
            return;
        }
        long[] blocking = scene.blocking(cubeX, cubeY, cubeZ);
        if (blocking == null) {
            set(index, UNIFORM | scene.uniformState(cubeX, cubeY, cubeZ));
            return;
        }
        int entry = lookup[index];
        int slot;
        if ((entry & KIND) == BRICK) {
            slot = entry & ~KIND;
        } else {
            slot = freeCount > 0 ? freeSlots[--freeCount] : slots++;
            bricksHeld++;
            mostBricksHeld = Math.max(mostBricksHeld, bricksHeld);
            if ((slot + 1) * BRICK_WORDS > bricks.length) {
                bricks = java.util.Arrays.copyOf(bricks, Math.max(bricks.length * 2, (slot + 1) * BRICK_WORDS));
            }
            if (slot >= cubeOfSlot.length) {
                cubeOfSlot = java.util.Arrays.copyOf(cubeOfSlot, Math.max(cubeOfSlot.length * 2, slot + 1));
            }
            cubeOfSlot[slot] = CubeKey.of(cubeX, cubeY, cubeZ);
            set(index, BRICK | slot);
        }
        int at = slot * BRICK_WORDS;
        System.arraycopy(blocking, 0, bricks, at, FINE_WORDS);
        java.util.Arrays.fill(bricks, at + FINE_WORDS, at + BRICK_WORDS, 0);
        for (int word = 0; word < FINE_WORDS; word++) {
            long bits = blocking[word];
            while (bits != 0) {
                int cell = word << 6 | Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                // The cell's block of two a side, in the same order with three bits an axis.
                int coarse = (cell >> 9 & 7) << 6 | (cell >> 5 & 7) << 3 | cell >> 1 & 7;
                bricks[at + FINE_WORDS + (coarse >> 6)] |= 1L << coarse;
            }
        }
        if ((brickDirty.length << 6) <= slot) {
            brickDirty = java.util.Arrays.copyOf(brickDirty, Math.max(brickDirty.length * 2, (slot >> 6) + 1));
        }
        if ((brickDirty[slot >> 6] >>> slot & 1) == 0) {
            brickDirty[slot >> 6] |= 1L << slot;
            bricksDirty++;
        }
        marks++;
    }

    private void set(int index, int entry) {
        int old = lookup[index];
        if (old == entry) {
            return;
        }
        if ((old & KIND) == BRICK) {
            int slot = old & ~KIND;
            if (freeCount == freeSlots.length) {
                freeSlots = java.util.Arrays.copyOf(freeSlots, freeCount * 2);
            }
            freeSlots[freeCount++] = slot;
            bricksHeld--;
        }
        lookup[index] = entry;
        if ((entryDirty[index >> 6] >>> index & 1) == 0) {
            entryDirty[index >> 6] |= 1L << index;
            entriesDirty++;
        }
        marks++;
    }

    // What a reader takes.

    /** The lookup: an entry for each cube position of the box, as {@link #entryIndex} places them. */
    public int[] lookup() {
        return lookup;
    }

    /** The entry of a cube, or {@link #NOT_AVAILABLE} when it is outside the box. */
    public int entry(int cubeX, int cubeY, int cubeZ) {
        return inside(cubeX, cubeY, cubeZ) ? lookup[entryIndex(cubeX, cubeY, cubeZ)] : NOT_AVAILABLE;
    }

    /** Every brick's words, {@link #BRICK_WORDS} to a slot. */
    public long[] bricks() {
        return bricks;
    }

    /** Slots ever used: the bricks' words reach this far. */
    public int slots() {
        return slots;
    }

    /** Bricks in use. */
    public int bricksHeld() {
        return bricksHeld;
    }

    /** The most bricks ever in use at once: what room for bricks has to allow for. */
    public int mostBricksHeld() {
        return mostBricksHeld;
    }

    /** Changes whenever the lookup or a brick does. */
    public long generation() {
        return generation;
    }

    public int entriesWaiting() {
        return entriesDirty;
    }

    public int bricksWaiting() {
        return bricksDirty;
    }

    /**
     * Takes up to {@code most} bricks that changed, for copying to where a shader reads them. Take
     * bricks before entries, so that no entry names a brick not yet copied.
     *
     * @param slots filled with the slots taken
     * @return how many were taken
     */
    public int takeBricks(int[] slots, int most) {
        int taken = 0;
        int words = brickDirty.length;
        for (int step = 0; step < words && taken < most && bricksDirty > 0; step++) {
            int word = (brickCursor + step) % words;
            while (brickDirty[word] != 0 && taken < most) {
                int bit = Long.numberOfTrailingZeros(brickDirty[word]);
                brickDirty[word] &= brickDirty[word] - 1;
                slots[taken++] = word << 6 | bit;
                bricksDirty--;
            }
            if (brickDirty[word] != 0) {
                brickCursor = word;
                return taken;
            }
        }
        return taken;
    }

    /**
     * Takes up to {@code most} lookup entries that changed. An entry that names a brick still waiting
     * to be taken is left for later.
     *
     * @param indices filled with where each entry lies in the lookup
     * @return how many were taken
     */
    public int takeEntries(int[] indices, int most) {
        int taken = 0;
        int words = entryDirty.length;
        for (int step = 0; step < words && taken < most && entriesDirty > 0; step++) {
            int word = (entryCursor + step) % words;
            long bits = entryDirty[word];
            while (bits != 0 && taken < most) {
                int bit = Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                int index = word << 6 | bit;
                int entry = lookup[index];
                if ((entry & KIND) == BRICK) {
                    int slot = entry & ~KIND;
                    if ((brickDirty[slot >> 6] >>> slot & 1) != 0) {
                        continue;
                    }
                }
                entryDirty[word] &= ~(1L << bit);
                indices[taken++] = index;
                entriesDirty--;
            }
            if (taken == most) {
                entryCursor = word;
            }
        }
        return taken;
    }
}
