package cc.orthrus.quadrature.core.scene;

import cc.orthrus.quadrature.core.terrain.CubeKey;

import java.util.HashMap;
import java.util.Map;

/**
 * The world as lighting needs it, independent of any view: for every cell of every cube that has been
 * taken, which block state it holds, and for every block state, how it treats light.
 *
 * <p>A cell holds one number, the state's identity, 0 for air. What a state does to light is the same
 * wherever it stands, so it is kept once for the state: whether it blocks light as a whole cube does,
 * how much it emits and how much it holds back, each on the game's scale of 0 to 15.
 *
 * <p>Three answers are kept apart for a cell: it is air, it holds something, or nothing is known of it.
 * A cube that has not been taken, because it is not loaded or has not come up yet, is not known; it is
 * never reported as empty.
 *
 * <p>Every change advances one counter, and the cube changed takes the counter's value as its
 * generation. A consumer remembers the value it last saw and is told which cubes changed since, each
 * once. The unit of change is the cube.
 *
 * <p>Used from one thread.
 */
public final class LightScene {

    /** Cells along a cube's edge. */
    public static final int EDGE = 16;
    /** Cells in a cube. */
    public static final int CELLS = EDGE * EDGE * EDGE;
    /** The state that is air. */
    public static final int AIR = 0;
    /** How many distinct states a cell's number can name. */
    public static final int STATES = 1 << 16;

    /** What is known of a cell. */
    public enum Cell {
        /** The cell's cube has not been taken: it is not loaded, or has not come up yet. */
        NOT_AVAILABLE,
        /** The cell holds air. */
        KNOWN_AIR,
        /** The cell holds a block; the state says what it does to light. */
        RESIDENT_SOLID
    }

    /** Receives what changed. */
    public interface Changes {
        /**
         * More changed than the scene remembers: forget everything held. Every cube now in the scene
         * follows as {@link #updated}.
         */
        void everything();

        /** A cube's cells were taken or replaced. */
        void updated(int cubeX, int cubeY, int cubeZ, long generation);

        /** A cube is no longer known. */
        void removed(int cubeX, int cubeY, int cubeZ);
    }

    private static final int DEFINED = 0x8000;
    private static final int BLOCKS = 0x0100;
    private static final byte UPDATED = 0;
    private static final byte REMOVED = 1;

    private static final class Cube {
        /** The cells, or null when all hold {@link #uniform}. */
        short[] cells;
        short uniform;
        /** A bit for each cell that blocks light, or null when none or all do. */
        long[] blocking;
        long generation;
    }

    /** For each state: defined, blocks light, emission and transmission. */
    private final short[] states = new short[STATES];
    private final Map<Long, Cube> cubes = new HashMap<>();
    private final Map<Long, Long> removals = new HashMap<>();
    private final long[] logKey;
    private final long[] logStamp;
    private final byte[] logKind;
    /** Changes ever logged; the log holds the last {@code logKey.length} of them. */
    private long logged;
    private long version;
    private int mixed;
    private long statesDefined;

    public LightScene() {
        this(16_384);
    }

    /** @param remembered how many changes are kept for consumers that ask later */
    public LightScene(int remembered) {
        logKey = new long[remembered];
        logStamp = new long[remembered];
        logKind = new byte[remembered];
        // Air is known from the start: it emits nothing and holds nothing back.
        states[AIR] = (short) DEFINED;
    }

    // Block states.

    /**
     * Says what a state does to light.
     *
     * @param emission     the light it gives, 0 to 15
     * @param transmission how much light it holds back, 0 for none to 15 for all
     * @param blocks       whether it blocks light as an opaque whole cube does
     */
    public void defineState(int state, int emission, int transmission, boolean blocks) {
        if (emission < 0 || emission > 15 || transmission < 0 || transmission > 15) {
            throw new IllegalArgumentException("Emission and transmission run from 0 to 15");
        }
        states[state] = (short) (DEFINED | (blocks ? BLOCKS : 0) | emission << 4 | transmission);
        statesDefined++;
    }

    /** How many times a state has been defined: when this moves on, the table has changed. */
    public long statesDefined() {
        return statesDefined;
    }

    public boolean stateDefined(int state) {
        return (states[state] & DEFINED) != 0;
    }

    /** The light the state gives, 0 to 15. */
    public int emission(int state) {
        return states[state] >> 4 & 15;
    }

    /** How much light passes through the state, 0 to 15. */
    public int transmission(int state) {
        return states[state] & 15;
    }

    /** Whether the state stops light. */
    public boolean blocksLight(int state) {
        return (states[state] & BLOCKS) != 0;
    }

    /** The table as a consumer would copy it: for each state, emission in bits 4 to 7, transmission in 0 to 3, blocks in bit 8. */
    public short[] stateTable() {
        return states;
    }

    // Cubes.

    /** Where a cell lies in a cube's array: x fastest, then z, then y. */
    public static int index(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | x & 15;
    }

    /**
     * Takes a cube's cells, replacing what was held. The array is copied, unless every cell holds the
     * same state, in which case only that state is kept.
     *
     * @param cells    the state of each cell, as {@link #index} orders them
     * @param blocking a bit for each cell that blocks light, in the same order, 64 words
     */
    public void put(int cubeX, int cubeY, int cubeZ, short[] cells, long[] blocking) {
        short first = cells[0];
        boolean same = true;
        for (int i = 1; i < CELLS && same; i++) {
            same = cells[i] == first;
        }
        if (same) {
            putUniform(cubeX, cubeY, cubeZ, first & 0xFFFF);
            return;
        }
        Cube cube = cube(cubeX, cubeY, cubeZ);
        if (cube.cells == null) {
            cube.cells = new short[CELLS];
            cube.blocking = new long[CELLS / 64];
            mixed++;
        }
        System.arraycopy(cells, 0, cube.cells, 0, CELLS);
        System.arraycopy(blocking, 0, cube.blocking, 0, CELLS / 64);
        changed(CubeKey.of(cubeX, cubeY, cubeZ), cube);
    }

    /** Takes a cube all of whose cells hold one state, as a cube of air does. */
    public void putUniform(int cubeX, int cubeY, int cubeZ, int state) {
        Cube cube = cube(cubeX, cubeY, cubeZ);
        if (cube.cells != null) {
            cube.cells = null;
            cube.blocking = null;
            mixed--;
        }
        cube.uniform = (short) state;
        changed(CubeKey.of(cubeX, cubeY, cubeZ), cube);
    }

    private Cube cube(int cubeX, int cubeY, int cubeZ) {
        return cubes.computeIfAbsent(CubeKey.of(cubeX, cubeY, cubeZ), key -> new Cube());
    }

    private void changed(long key, Cube cube) {
        cube.generation = ++version;
        removals.remove(key);
        log(key, version, UPDATED);
    }

    /** Forgets a cube, as when it is unloaded. */
    public void remove(int cubeX, int cubeY, int cubeZ) {
        long key = CubeKey.of(cubeX, cubeY, cubeZ);
        Cube cube = cubes.remove(key);
        if (cube == null) {
            return;
        }
        if (cube.cells != null) {
            mixed--;
        }
        removals.put(key, ++version);
        log(key, version, REMOVED);
    }

    /** Forgets every cube, as when the world is left. A consumer is told to forget everything too. */
    public void clear() {
        cubes.clear();
        removals.clear();
        mixed = 0;
        version++;
        // Nothing before this is remembered, so a consumer that saw any of it starts again.
        logged += logKey.length;
        java.util.Arrays.fill(logStamp, version);
        java.util.Arrays.fill(logKind, (byte) -1);
    }

    private void log(long key, long stamp, byte kind) {
        int at = (int) (logged % logKey.length);
        if (logged >= logKey.length && logKind[at] == REMOVED) {
            // The removal being forgotten need not be remembered as the cube's last word either.
            Long last = removals.get(logKey[at]);
            if (last != null && last == logStamp[at]) {
                removals.remove(logKey[at]);
            }
        }
        logKey[at] = key;
        logStamp[at] = stamp;
        logKind[at] = kind;
        logged++;
    }

    // Queries.

    /** What is known of the cell at a block position. */
    public Cell cell(int blockX, int blockY, int blockZ) {
        int state = state(blockX, blockY, blockZ);
        return state < 0 ? Cell.NOT_AVAILABLE : state == AIR ? Cell.KNOWN_AIR : Cell.RESIDENT_SOLID;
    }

    /** The state at a block position, or -1 when the cell is not available. */
    public int state(int blockX, int blockY, int blockZ) {
        Cube cube = cubes.get(CubeKey.of(blockX >> 4, blockY >> 4, blockZ >> 4));
        if (cube == null) {
            return -1;
        }
        return (cube.cells == null ? cube.uniform : cube.cells[index(blockX, blockY, blockZ)]) & 0xFFFF;
    }

    /** Whether a cube's cells are held. */
    public boolean has(int cubeX, int cubeY, int cubeZ) {
        return cubes.containsKey(CubeKey.of(cubeX, cubeY, cubeZ));
    }

    /** A cube's generation, or -1 when it is not held. */
    public long generation(int cubeX, int cubeY, int cubeZ) {
        Cube cube = cubes.get(CubeKey.of(cubeX, cubeY, cubeZ));
        return cube == null ? -1 : cube.generation;
    }

    /**
     * A cube's cells as {@link #index} orders them, or null when the cube is not held or holds one state
     * throughout, which {@link #uniformState} gives. The array is the scene's own: read it, do not keep it.
     */
    public short[] cells(int cubeX, int cubeY, int cubeZ) {
        Cube cube = cubes.get(CubeKey.of(cubeX, cubeY, cubeZ));
        return cube == null ? null : cube.cells;
    }

    /** The state every cell of a cube holds, or -1 when the cube is not held or its cells differ. */
    public int uniformState(int cubeX, int cubeY, int cubeZ) {
        Cube cube = cubes.get(CubeKey.of(cubeX, cubeY, cubeZ));
        return cube == null || cube.cells != null ? -1 : cube.uniform & 0xFFFF;
    }

    /**
     * A bit for each cell of a cube that blocks light, 64 words, or null when the cube is not held or
     * holds one state throughout. The array is the scene's own.
     */
    public long[] blocking(int cubeX, int cubeY, int cubeZ) {
        Cube cube = cubes.get(CubeKey.of(cubeX, cubeY, cubeZ));
        return cube == null ? null : cube.blocking;
    }

    /** The counter's value now: what a consumer that has seen everything remembers. */
    public long version() {
        return version;
    }

    /**
     * Tells a consumer what changed after the counter stood at {@code seen}: each cube once, as it is
     * now.
     *
     * @return the value to pass next time
     */
    public long changesSince(long seen, Changes changes) {
        if (seen >= version) {
            return version;
        }
        long held = Math.min(logged, logKey.length);
        long oldest = logged - held;
        int first = (int) (oldest % logKey.length);
        // Everything, if the consumer has never asked, or if a change it has not seen is no longer
        // remembered: one pushed out of the log, or everything from before the scene was cleared.
        boolean all = seen < 0 || held == 0 || oldest > 0 && logStamp[first] > seen + 1
            || logKind[first] < 0 && logStamp[first] > seen;
        if (all) {
            changes.everything();
            for (Map.Entry<Long, Cube> entry : cubes.entrySet()) {
                long key = entry.getKey();
                changes.updated(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key), entry.getValue().generation);
            }
            return version;
        }
        for (long i = oldest; i < logged; i++) {
            int at = (int) (i % logKey.length);
            long stamp = logStamp[at];
            if (stamp <= seen) {
                continue;
            }
            long key = logKey[at];
            if (logKind[at] == UPDATED) {
                // Only a cube's latest state is told: an older entry for it is passed over.
                Cube cube = cubes.get(key);
                if (cube != null && cube.generation == stamp) {
                    changes.updated(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key), stamp);
                }
            } else if (logKind[at] == REMOVED) {
                Long last = removals.get(key);
                if (last != null && last == stamp && !cubes.containsKey(key)) {
                    changes.removed(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key));
                }
            }
        }
        return version;
    }

    // Accounting.

    /** Cubes held. */
    public int cubes() {
        return cubes.size();
    }

    /** Cubes held whose cells differ, each with an array of its own. */
    public int mixedCubes() {
        return mixed;
    }

    /** The bytes the cells and blocking bits of the cubes held take. */
    public long cellBytes() {
        return (long) mixed * (CELLS * 2 + CELLS / 8);
    }
}
