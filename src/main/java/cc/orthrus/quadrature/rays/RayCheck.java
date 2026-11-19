package cc.orthrus.quadrature.rays;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.scene.BrickTrace;
import cc.orthrus.quadrature.core.scene.LightScene;
import cc.orthrus.quadrature.core.scene.TraceFormat;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL45;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A diagnostic of the ray benchmark, {@code -Dquadrature.rays.check=true}: after each measurement two
 * runs of {@value #RUN} rays and their results are read back, with the scene's generation and box; at
 * the end of the set the copy of the scene's buffers the benchmark made on the GPU at the set's start is
 * read back once, never the scene's own buffers, and each sampled ray traced again by {@link BrickTrace}
 * over it. The results must be the same, word for word: the distance's bits, the cell, the outcome and
 * the nodes visited. A sample traced under another generation than the copy's is passed over and
 * counted, since what it was traced against is not what was copied.
 */
final class RayCheck {

    static final int RUN = 256;
    private static final int RUNS = 2;
    private static final int SHOWN = 5;

    private static final class Sample {
        final String label;
        final long generation;
        final int originX;
        final int originY;
        final int originZ;
        final int sizeX;
        final int sizeY;
        final int[] first = new int[RUNS];
        final float[] rays = new float[RUNS * RUN * TraceFormat.RAY_FLOATS];
        final int[] results = new int[RUNS * RUN * TraceFormat.RESULT_INTS];

        Sample(String label, long generation, int originX, int originY, int originZ, int sizeX, int sizeY) {
            this.label = label;
            this.generation = generation;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
        }
    }

    private final List<Sample> samples = new ArrayList<>();
    private final FloatBuffer rayBack = BufferUtils.createFloatBuffer(RUN * TraceFormat.RAY_FLOATS);
    private final IntBuffer resultBack = BufferUtils.createIntBuffer(RUN * TraceFormat.RESULT_INTS);
    private final IntBuffer blockingBack = BufferUtils.createIntBuffer(LightScene.STATES / 32);
    private IntBuffer lookupBack;
    private IntBuffer brickBack;
    private final BrickTrace reference = new BrickTrace();
    private int set;
    private long checkedRays;
    private long differences;
    private long passedOver;

    void begin(int set) {
        this.set = set;
        samples.clear();
    }

    /**
     * Reads back two runs of a finished trace's rays and results; the trace must be complete. The scene is
     * given as the trace read it: its generation, origin cube and box when the trace was dispatched.
     */
    void sample(String label, int rayBuffer, int resultBuffer, int count, long generation, int originX, int originY,
        int originZ, int sizeX, int sizeY) {
        Sample sample = new Sample(label, generation, originX, originY, originZ, sizeX, sizeY);
        for (int run = 0; run < RUNS; run++) {
            // Two places that move from set to set and measurement to measurement.
            long mixed = (set * 1_000_003L + samples.size() * 7_919L + run * 104_729L) * 2_654_435_761L;
            int first = (int) Math.floorMod(mixed >>> 7, (long) Math.max(1, count - RUN));
            sample.first[run] = first;
            rayBack.clear();
            GL45.glGetNamedBufferSubData(rayBuffer, (long) first * TraceFormat.RAY_BYTES, rayBack);
            rayBack.get(sample.rays, run * RUN * TraceFormat.RAY_FLOATS, RUN * TraceFormat.RAY_FLOATS);
            resultBack.clear();
            GL45.glGetNamedBufferSubData(resultBuffer, (long) first * TraceFormat.RESULT_BYTES, resultBack);
            resultBack.get(sample.results, run * RUN * TraceFormat.RESULT_INTS, RUN * TraceFormat.RESULT_INTS);
        }
        samples.add(sample);
    }

    /**
     * Reads back a copy of the scene's lookup and bricks and traces every sample of the set taken under the
     * copy's generation.
     *
     * @param generation the scene's generation when the copy was made
     */
    void compare(int blockingBuffer, int lookupBuffer, int brickBuffer, long generation, int sizeX, int sizeY, int sizeZ) {
        if (samples.isEmpty() || lookupBuffer == 0) {
            return;
        }
        long began = System.nanoTime();
        int lookupInts = sizeX * sizeY * sizeZ;
        int brickInts = GL45.glGetNamedBufferParameteri(brickBuffer, GL15.GL_BUFFER_SIZE) / 4;
        if (lookupBack == null || lookupBack.capacity() < lookupInts) {
            lookupBack = BufferUtils.createIntBuffer(lookupInts);
        }
        if (brickBack == null || brickBack.capacity() < brickInts) {
            brickBack = BufferUtils.createIntBuffer(brickInts);
        }
        lookupBack.clear().limit(lookupInts);
        GL45.glGetNamedBufferSubData(lookupBuffer, 0, lookupBack);
        brickBack.clear().limit(brickInts);
        GL45.glGetNamedBufferSubData(brickBuffer, 0, brickBack);
        blockingBack.clear();
        GL45.glGetNamedBufferSubData(blockingBuffer, 0, blockingBack);
        int[] lookup = new int[lookupInts];
        lookupBack.get(lookup);
        int[] bricks = new int[brickInts];
        brickBack.get(bricks);
        int[] blocking = new int[LightScene.STATES / 32];
        blockingBack.get(blocking);
        long read = System.nanoTime();

        int traced = 0;
        int same = 0;
        int differ = 0;
        int skipped = 0;
        int shown = 0;
        int[] mine = new int[TraceFormat.RESULT_INTS];
        int[] outcomes = new int[TraceFormat.OUTCOMES];
        for (Sample sample : samples) {
            if (sample.generation != generation || sample.sizeX != sizeX || sample.sizeY != sizeY) {
                skipped += RUNS * RUN;
                continue;
            }
            reference.scene((sample.sizeX - 1) / 2, (sample.sizeY - 1) / 2, sample.originX, sample.originY, sample.originZ,
                lookup, bricks, blocking);
            for (int ray = 0; ray < RUNS * RUN; ray++) {
                reference.trace(sample.rays, ray * TraceFormat.RAY_FLOATS, mine, 0);
                traced++;
                int at = ray * TraceFormat.RESULT_INTS;
                boolean equal = true;
                for (int word = 0; word < TraceFormat.RESULT_INTS; word++) {
                    equal &= mine[word] == sample.results[at + word];
                }
                outcomes[Math.min(TraceFormat.outcome(sample.results[at + 2]), TraceFormat.OUTCOMES - 1)]++;
                if (equal) {
                    same++;
                    continue;
                }
                differ++;
                if (shown++ < SHOWN) {
                    int r = ray * TraceFormat.RAY_FLOATS;
                    Quadrature.LOGGER.error("Ray check set {}, {}: ray {} from ({}, {}, {}) length {} inverse ({}, {}, {}): "
                            + "GPU {} at {} in cell {}, {} nodes; CPU {} at {} in cell {}, {} nodes.", set, sample.label,
                        sample.first[ray / RUN] + ray % RUN, sample.rays[r], sample.rays[r + 1], sample.rays[r + 2],
                        sample.rays[r + 3], sample.rays[r + 4], sample.rays[r + 5], sample.rays[r + 6],
                        describe(sample.results, at), Float.intBitsToFloat(sample.results[at]), cell(sample.results[at + 1]),
                        TraceFormat.visits(sample.results[at + 2]), describe(mine, 0), Float.intBitsToFloat(mine[0]),
                        cell(mine[1]), TraceFormat.visits(mine[2]));
                }
            }
        }
        checkedRays += traced;
        differences += differ;
        passedOver += skipped;
        StringBuilder ends = new StringBuilder();
        for (int outcome = 0; outcome < TraceFormat.OUTCOMES; outcome++) {
            ends.append(outcome == 0 ? "" : ", ").append(outcomes[outcome]).append(' ').append(TraceFormat.outcomeName(outcome));
        }
        String line = String.format("Ray check set %d: %,d rays of %d measurements traced again on the CPU over the copy "
                + "read back, made at generation %d (%s): %,d the same word for word, %,d differ; %,d passed over for another "
                + "generation. Reading back %.1f ms, tracing %.1f ms. In all %,d rays, %,d differ, %,d passed over.", set,
            traced, samples.size(), generation, ends, same, differ, skipped, (read - began) / 1e6,
            (System.nanoTime() - read) / 1e6, checkedRays, differences, passedOver);
        if (differ > 0) {
            Quadrature.LOGGER.error("RAYS CHECK FAILED. " + line);
        } else {
            Quadrature.LOGGER.info(line);
        }
        samples.clear();
    }

    private static String describe(int[] result, int at) {
        return TraceFormat.outcomeName(TraceFormat.outcome(result[at + 2]));
    }

    private static String cell(int packed) {
        return TraceFormat.cellX(packed) + "," + TraceFormat.cellY(packed) + "," + TraceFormat.cellZ(packed);
    }
}
