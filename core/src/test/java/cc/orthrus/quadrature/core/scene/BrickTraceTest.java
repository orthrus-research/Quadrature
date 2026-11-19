package cc.orthrus.quadrature.core.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.orthrus.quadrature.core.terrain.CubeKey;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

class BrickTraceTest {

    private static final int STONE = 16;
    private static final int GLASS = 320;
    private static final int LOG = 273;

    /**
     * A world made up for a test: for each cube, nothing known, one state, or cells. It answers for a
     * cell from its own maps, never from the bricks, so a walk asking it tests the bricks' layout too.
     */
    private static final class World {
        final LightScene scene = new LightScene();
        final Map<Long, short[]> cells = new HashMap<>();
        final Map<Long, Integer> uniform = new HashMap<>();

        World() {
            scene.defineState(STONE, 0, 15, true);
            scene.defineState(GLASS, 0, 0, false);
            scene.defineState(LOG, 0, 15, true);
        }

        void uniform(int x, int y, int z, int state) {
            uniform.put(CubeKey.of(x, y, z), state);
            cells.remove(CubeKey.of(x, y, z));
            scene.putUniform(x, y, z, state);
        }

        void cells(int x, int y, int z, short[] states) {
            long[] blocking = new long[LightScene.CELLS / 64];
            boolean same = true;
            for (int i = 0; i < LightScene.CELLS; i++) {
                if (scene.blocksLight(states[i] & 0xFFFF)) {
                    blocking[i >> 6] |= 1L << i;
                }
                same &= states[i] == states[0];
            }
            if (same) {
                uniform(x, y, z, states[0]);
                return;
            }
            cells.put(CubeKey.of(x, y, z), states.clone());
            uniform.remove(CubeKey.of(x, y, z));
            scene.put(x, y, z, states, blocking);
        }

        /** -1 where nothing is known, 1 where the cell blocks light, 0 where it does not. */
        int at(int bx, int by, int bz) {
            long key = CubeKey.of(bx >> 4, by >> 4, bz >> 4);
            short[] held = cells.get(key);
            if (held != null) {
                return scene.blocksLight(held[LightScene.index(bx, by, bz)] & 0xFFFF) ? 1 : 0;
            }
            Integer state = uniform.get(key);
            if (state == null) {
                return -1;
            }
            return scene.blocksLight(state) ? 1 : 0;
        }
    }

    /** The world's bricks as the GPU holds them, about an origin cube. */
    private static BrickTrace trace(World world, int across, int upward, int ox, int oy, int oz) {
        SceneBricks bricks = new SceneBricks(world.scene, across, upward);
        bricks.update(ox, oy, oz);
        return trace(world, bricks, across, upward, ox, oy, oz);
    }

    private static BrickTrace trace(World world, SceneBricks bricks, int across, int upward, int ox, int oy, int oz) {
        BrickTrace trace = new BrickTrace();
        trace.scene(across, upward, ox, oy, oz, bricks.lookup().clone(), TraceFormat.brickInts(bricks.bricks(),
            bricks.slots()), TraceFormat.blockingStates(world.scene));
        return trace;
    }

    /**
     * The walk of one cell at a time, crossing the nearest face each step, in the same arithmetic as
     * the trace, asking the world for each cell.
     */
    private static int[] fineWalk(World world, int across, int upward, int ox, int oy, int oz, float[] ray) {
        float[] o = {ray[0], ray[1], ray[2]};
        float length = ray[3];
        float[] inv = new float[3];
        int[] s = new int[3];
        int[] c = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            inv[axis] = Math.min(Math.max(ray[4 + axis], -BrickTrace.MOST_INVERSE), BrickTrace.MOST_INVERSE);
            s[axis] = inv[axis] > 0.0f ? 1 : -1;
            c[axis] = (int) Math.floor(o[axis]);
        }
        float t = 0.0f;
        int outcome = TraceFormat.STEPS;
        float[] next = new float[3];
        for (int step = 0; step < 1_000_000; step++) {
            if (Math.abs(c[0] >> 4) > across || Math.abs(c[2] >> 4) > across || Math.abs(c[1] >> 4) > upward) {
                outcome = TraceFormat.LEFT;
                break;
            }
            int held = world.at(ox * 16 + c[0], oy * 16 + c[1], oz * 16 + c[2]);
            if (held < 0) {
                outcome = TraceFormat.NOT_AVAILABLE;
                break;
            }
            if (held > 0) {
                outcome = TraceFormat.HIT;
                break;
            }
            for (int axis = 0; axis < 3; axis++) {
                next[axis] = ((float) (s[axis] > 0 ? c[axis] + 1 : c[axis]) - o[axis]) * inv[axis];
            }
            int axis = 0;
            float leave = next[0];
            if (next[1] < leave) {
                axis = 1;
                leave = next[1];
            }
            if (next[2] < leave) {
                axis = 2;
                leave = next[2];
            }
            if (leave > length) {
                outcome = TraceFormat.MISS;
                t = length;
                break;
            }
            c[axis] += s[axis];
            t = leave;
        }
        return new int[] {Float.floatToRawIntBits(t), TraceFormat.cell(c[0], c[1], c[2]), outcome};
    }

    /** Ground of stone, a surface of mixed cubes with trees of logs and glass, air above, and cubes not known. */
    private static World terrain(Random random, int across, int upward, int ox, int oy, int oz) {
        World world = new World();
        for (int y = oy - upward; y <= oy + upward; y++) {
            for (int z = oz - across; z <= oz + across; z++) {
                for (int x = ox - across; x <= ox + across; x++) {
                    if (random.nextInt(40) == 0) {
                        continue;
                    }
                    if (y < oy) {
                        world.uniform(x, y, z, random.nextInt(10) == 0 ? GLASS : STONE);
                    } else if (y > oy) {
                        world.uniform(x, y, z, LightScene.AIR);
                    } else {
                        world.cells(x, y, z, surface(random));
                    }
                }
            }
        }
        return world;
    }

    private static short[] surface(Random random) {
        short[] states = new short[LightScene.CELLS];
        int[] height = new int[256];
        int base = 2 + random.nextInt(8);
        for (int i = 0; i < 256; i++) {
            height[i] = Math.max(0, Math.min(15, base + random.nextInt(3) - 1));
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int h = height[z * 16 + x];
                    short state = y <= h ? (short) STONE : 0;
                    if (y > h && random.nextInt(60) == 0) {
                        state = (short) (random.nextBoolean() ? LOG : GLASS);
                    }
                    states[LightScene.index(x, y, z)] = state;
                }
            }
        }
        return states;
    }

    /** Cubes of scattered cells, some of one state, some not known. */
    private static World scattered(Random random, int across, int upward, int ox, int oy, int oz, double fill) {
        World world = new World();
        for (int y = oy - upward; y <= oy + upward; y++) {
            for (int z = oz - across; z <= oz + across; z++) {
                for (int x = ox - across; x <= ox + across; x++) {
                    int kind = random.nextInt(10);
                    if (kind == 0) {
                        continue;
                    }
                    if (kind == 1) {
                        world.uniform(x, y, z, STONE);
                    } else if (kind == 2) {
                        world.uniform(x, y, z, GLASS);
                    } else if (kind < 6) {
                        world.uniform(x, y, z, LightScene.AIR);
                    } else {
                        short[] states = new short[LightScene.CELLS];
                        for (int i = 0; i < LightScene.CELLS; i++) {
                            double roll = random.nextDouble();
                            states[i] = roll < fill ? (short) STONE : roll < fill * 1.5 ? (short) GLASS : 0;
                        }
                        world.cells(x, y, z, states);
                    }
                }
            }
        }
        return world;
    }

    /** A ray from anywhere in the box in any direction, some of them along axes, along diagonals or from a whole block. */
    private static float[] ray(Random random, int across, int upward, float length) {
        float x = (float) ((random.nextDouble() * (2 * across + 1) - across) * 16);
        float y = (float) ((random.nextDouble() * (2 * upward + 1) - upward) * 16);
        float z = (float) ((random.nextDouble() * (2 * across + 1) - across) * 16);
        float dx;
        float dy;
        float dz;
        int kind = random.nextInt(8);
        if (kind == 0) {
            // Along an axis, with zeros of either sign.
            int axis = random.nextInt(3);
            float sign = random.nextBoolean() ? 1.0f : -1.0f;
            float zero = random.nextBoolean() ? 0.0f : -0.0f;
            dx = axis == 0 ? sign : zero;
            dy = axis == 1 ? sign : zero;
            dz = axis == 2 ? sign : zero;
        } else if (kind == 1) {
            // Along a diagonal, through edges and corners.
            float third = (float) (1 / Math.sqrt(3));
            dx = random.nextBoolean() ? third : -third;
            dy = random.nextBoolean() ? third : -third;
            dz = random.nextBoolean() ? third : -third;
        } else {
            double u = random.nextDouble() * 2 - 1;
            double phi = random.nextDouble() * 2 * Math.PI;
            double r = Math.sqrt(1 - u * u);
            dx = (float) (r * Math.cos(phi));
            dy = (float) u;
            dz = (float) (r * Math.sin(phi));
        }
        if (random.nextInt(4) == 0) {
            // From a whole block, or half of one: faces and edges met exactly.
            x = Math.round(x * 2) / 2.0f;
            y = Math.round(y * 2) / 2.0f;
            z = Math.round(z * 2) / 2.0f;
        }
        float[] ray = new float[TraceFormat.RAY_FLOATS];
        TraceFormat.ray(ray, 0, x, y, z, length, dx, dy, dz);
        return ray;
    }

    /** Traces rays both ways and requires the same outcome, distance to the bit, and cell. */
    private static int[] sameAsFineWalk(World world, int across, int upward, int ox, int oy, int oz, Random random,
        int rays) {
        BrickTrace trace = trace(world, across, upward, ox, oy, oz);
        int[] result = new int[TraceFormat.RESULT_INTS];
        int[] outcomes = new int[TraceFormat.OUTCOMES];
        for (int i = 0; i < rays; i++) {
            float length = new float[] {16, 64, 256, 1000}[random.nextInt(4)];
            float[] ray = ray(random, across, upward, length);
            trace.trace(ray, 0, result, 0);
            int[] fine = fineWalk(world, across, upward, ox, oy, oz, ray);
            int outcome = TraceFormat.outcome(result[2]);
            String what = String.format("ray %d from (%s, %s, %s) inverse (%s, %s, %s) length %s", i, ray[0], ray[1],
                ray[2], ray[4], ray[5], ray[6], ray[3]);
            assertEquals(fine[2], outcome, what);
            assertEquals(Float.intBitsToFloat(fine[0]), Float.intBitsToFloat(result[0]), what);
            assertEquals(fine[0], result[0], what);
            if (outcome != TraceFormat.MISS) {
                assertEquals(fine[1], result[1], what);
            }
            assertEquals(0, result[3]);
            outcomes[outcome]++;
        }
        assertEquals(0, outcomes[TraceFormat.STEPS]);
        return outcomes;
    }

    @Test
    void endsWhereAWalkOfOneCellAtATimeEndsOverTerrain() {
        Random random = new Random(7);
        World world = terrain(random, 4, 3, 0, 0, 0);
        int[] outcomes = sameAsFineWalk(world, 4, 3, 0, 0, 0, random, 20_000);
        // Every way of ending is exercised.
        assertTrue(outcomes[TraceFormat.HIT] > 1000);
        assertTrue(outcomes[TraceFormat.MISS] > 500);
        assertTrue(outcomes[TraceFormat.LEFT] > 500);
        assertTrue(outcomes[TraceFormat.NOT_AVAILABLE] > 100);
    }

    @Test
    void endsWhereAWalkOfOneCellAtATimeEndsThroughScatteredCells() {
        Random random = new Random(11);
        for (double fill : new double[] {0.002, 0.03, 0.3}) {
            World world = scattered(random, 3, 2, 0, 0, 0, fill);
            sameAsFineWalk(world, 3, 2, 0, 0, 0, random, 8_000);
        }
    }

    @Test
    void readsTheLookupWrappedAboutAnyOrigin() {
        Random random = new Random(13);
        World world = terrain(random, 3, 2, -37, 5, 1000);
        sameAsFineWalk(world, 3, 2, -37, 5, 1000, random, 8_000);
    }

    @Test
    void readsTheLookupAfterTheBoxHasMoved() {
        Random random = new Random(17);
        World world = scattered(random, 5, 3, 2, 0, 0, 0.01);
        SceneBricks bricks = new SceneBricks(world.scene, 3, 2);
        bricks.update(0, 0, 0);
        bricks.update(2, -1, 1);
        BrickTrace trace = trace(world, bricks, 3, 2, 2, -1, 1);
        int[] result = new int[TraceFormat.RESULT_INTS];
        for (int i = 0; i < 4_000; i++) {
            float[] ray = ray(random, 3, 2, 256);
            trace.trace(ray, 0, result, 0);
            int[] fine = fineWalk(world, 3, 2, 2, -1, 1, ray);
            assertEquals(fine[2], TraceFormat.outcome(result[2]));
            assertEquals(fine[0], result[0]);
            if (fine[2] != TraceFormat.MISS) {
                assertEquals(fine[1], result[1]);
            }
        }
    }

    @Test
    void readsNoCoarserBit() {
        Random random = new Random(29);
        World world = terrain(random, 3, 2, 0, 0, 0);
        SceneBricks bricks = new SceneBricks(world.scene, 3, 2);
        bricks.update(0, 0, 0);
        int[] ints = TraceFormat.brickInts(bricks.bricks(), bricks.slots());
        int[] cleared = ints.clone();
        for (int slot = 0; slot < bricks.slots(); slot++) {
            java.util.Arrays.fill(cleared, slot * PublishedScene.BRICK_INTEGERS + 128,
                (slot + 1) * PublishedScene.BRICK_INTEGERS, 0);
        }
        int[] blocking = TraceFormat.blockingStates(world.scene);
        BrickTrace with = new BrickTrace();
        with.scene(3, 2, 0, 0, 0, bricks.lookup().clone(), ints, blocking);
        BrickTrace without = new BrickTrace();
        without.scene(3, 2, 0, 0, 0, bricks.lookup().clone(), cleared, blocking);
        int[] a = new int[TraceFormat.RESULT_INTS];
        int[] b = new int[TraceFormat.RESULT_INTS];
        for (int i = 0; i < 4_000; i++) {
            float[] ray = ray(random, 3, 2, 256);
            with.trace(ray, 0, a, 0);
            without.trace(ray, 0, b, 0);
            assertTrue(java.util.Arrays.equals(a, b), "ray " + i);
        }
    }

    @Test
    void passesEmptyCubesWholeAndVisitsFewNodes() {
        World world = new World();
        for (int y = -2; y <= 2; y++) {
            for (int z = -3; z <= 3; z++) {
                for (int x = -3; x <= 3; x++) {
                    world.uniform(x, y, z, LightScene.AIR);
                }
            }
        }
        BrickTrace trace = trace(world, 3, 2, 0, 0, 0);
        float[] ray = new float[TraceFormat.RAY_FLOATS];
        int[] result = new int[TraceFormat.RESULT_INTS];
        TraceFormat.ray(ray, 0, 8.5f, 8.5f, 8.5f, 1000, 1, 0, 0);
        trace.trace(ray, 0, result, 0);
        assertEquals(TraceFormat.LEFT, TraceFormat.outcome(result[2]));
        // From the middle of the origin cube to the first cell past the box: 4 cubes and the cell outside.
        assertEquals(4 * 16 - 8.5f, Float.intBitsToFloat(result[0]));
        assertEquals(TraceFormat.cell(64, 8, 8), result[1]);
        assertEquals(5, TraceFormat.visits(result[2]));
    }

    @Test
    void stopsAtACubeNothingIsKnownOfAndAtABlockingCubeOfOneState() {
        World world = new World();
        world.uniform(0, 0, 0, LightScene.AIR);
        world.uniform(1, 0, 0, GLASS);
        world.uniform(0, 0, 1, STONE);
        BrickTrace trace = trace(world, 2, 1, 0, 0, 0);
        float[] ray = new float[TraceFormat.RAY_FLOATS];
        int[] result = new int[TraceFormat.RESULT_INTS];
        // Through air and glass into the cube past them, which is not known.
        TraceFormat.ray(ray, 0, 0.5f, 4.5f, 4.5f, 256, 1, 0, 0);
        trace.trace(ray, 0, result, 0);
        assertEquals(TraceFormat.NOT_AVAILABLE, TraceFormat.outcome(result[2]));
        assertEquals(31.5f, Float.intBitsToFloat(result[0]));
        assertEquals(TraceFormat.cell(32, 4, 4), result[1]);
        // Into stone: the first cell entered.
        TraceFormat.ray(ray, 0, 3.25f, 4.5f, 2.5f, 256, 0, 0, 1);
        trace.trace(ray, 0, result, 0);
        assertEquals(TraceFormat.HIT, TraceFormat.outcome(result[2]));
        assertEquals(13.5f, Float.intBitsToFloat(result[0]));
        assertEquals(TraceFormat.cell(3, 4, 16), result[1]);
        // Shorter than the way to it: a miss at the length.
        TraceFormat.ray(ray, 0, 3.25f, 4.5f, 2.5f, 13, 0, 0, 1);
        trace.trace(ray, 0, result, 0);
        assertEquals(TraceFormat.MISS, TraceFormat.outcome(result[2]));
        assertEquals(13f, Float.intBitsToFloat(result[0]));
    }

    @Test
    void aRayBeginningInACellThatBlocksHitsAtNoDistance() {
        World world = new World();
        world.uniform(0, -1, 0, STONE);
        BrickTrace trace = trace(world, 2, 1, 0, 0, 0);
        float[] ray = new float[TraceFormat.RAY_FLOATS];
        int[] result = new int[TraceFormat.RESULT_INTS];
        TraceFormat.ray(ray, 0, 0.5f, -8.5f, 0.5f, 64, 0, 1, 0);
        trace.trace(ray, 0, result, 0);
        assertEquals(TraceFormat.HIT, TraceFormat.outcome(result[2]));
        assertEquals(0, result[0]);
        assertEquals(TraceFormat.cell(0, -9, 0), result[1]);
    }

    /**
     * Against points along the ray in double precision, a sixty-fourth of a block apart: the same
     * outcome and cell, except where one of the two cells is crossed in less than two steps of the
     * points, which the points can pass over.
     */
    @Test
    void agreesWithPointsAlongTheRayInDoublePrecision() {
        Random random = new Random(23);
        World world = terrain(random, 3, 2, 0, 0, 0);
        BrickTrace trace = trace(world, 3, 2, 0, 0, 0);
        int[] result = new int[TraceFormat.RESULT_INTS];
        double step = 1.0 / 64;
        int slivers = 0;
        int rays = 3_000;
        for (int i = 0; i < rays; i++) {
            float[] ray = ray(random, 3, 2, 48);
            trace.trace(ray, 0, result, 0);
            double[] o = {ray[0], ray[1], ray[2]};
            double[] d = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                double inverse = Math.min(Math.max(ray[4 + axis], -BrickTrace.MOST_INVERSE), BrickTrace.MOST_INVERSE);
                d[axis] = 1.0 / inverse;
            }
            int outcome = TraceFormat.MISS;
            int[] cell = new int[3];
            double at = 0;
            for (double t = 0; t <= ray[3]; t += step) {
                for (int axis = 0; axis < 3; axis++) {
                    cell[axis] = (int) Math.floor(o[axis] + t * d[axis]);
                }
                if (Math.abs(cell[0] >> 4) > 3 || Math.abs(cell[2] >> 4) > 3 || Math.abs(cell[1] >> 4) > 2) {
                    outcome = TraceFormat.LEFT;
                } else {
                    int held = world.at(cell[0], cell[1], cell[2]);
                    outcome = held < 0 ? TraceFormat.NOT_AVAILABLE : held > 0 ? TraceFormat.HIT : TraceFormat.MISS;
                }
                at = t;
                if (outcome != TraceFormat.MISS) {
                    break;
                }
            }
            int traced = TraceFormat.outcome(result[2]);
            if (outcome == TraceFormat.MISS || traced == TraceFormat.MISS) {
                if (outcome != traced) {
                    // Only where the deciding cell is entered within a step of the length.
                    assertTrue(Math.abs(at - ray[3]) <= 2 * step
                        || Math.abs(Float.intBitsToFloat(result[0]) - ray[3]) <= 2 * step, "ray " + i);
                    slivers++;
                }
                continue;
            }
            int packed = TraceFormat.cell(cell[0], cell[1], cell[2]);
            if (traced == outcome && packed == result[1]) {
                assertTrue(Math.abs(Float.intBitsToFloat(result[0]) - at) <= step + 1e-4, "ray " + i);
                continue;
            }
            double tracedSpan = span(o, d, TraceFormat.cellX(result[1]), TraceFormat.cellY(result[1]),
                TraceFormat.cellZ(result[1]));
            double pointSpan = span(o, d, cell[0], cell[1], cell[2]);
            assertTrue(Math.min(tracedSpan, pointSpan) < 2 * step, "ray " + i + ": traced " + TraceFormat.outcomeName(traced)
                + " at cell " + TraceFormat.cellX(result[1]) + "," + TraceFormat.cellY(result[1]) + ","
                + TraceFormat.cellZ(result[1]) + ", points " + TraceFormat.outcomeName(outcome) + " at " + cell[0] + ","
                + cell[1] + "," + cell[2]);
            slivers++;
        }
        assertTrue(slivers < rays / 50, slivers + " slivers");
    }

    /** How long the ray stays in a cell. */
    private static double span(double[] o, double[] d, int x, int y, int z) {
        double enter = Double.NEGATIVE_INFINITY;
        double leave = Double.POSITIVE_INFINITY;
        int[] cell = {x, y, z};
        for (int axis = 0; axis < 3; axis++) {
            double a = (cell[axis] - o[axis]) / d[axis];
            double b = (cell[axis] + 1 - o[axis]) / d[axis];
            enter = Math.max(enter, Math.min(a, b));
            leave = Math.min(leave, Math.max(a, b));
        }
        return Math.max(0, leave - enter);
    }
}
