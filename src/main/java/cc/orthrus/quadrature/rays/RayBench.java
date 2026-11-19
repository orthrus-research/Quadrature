package cc.orthrus.quadrature.rays;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.scene.PublishedScene;
import cc.orthrus.quadrature.core.scene.TraceFormat;
import cc.orthrus.quadrature.core.view.SunPath;
import cc.orthrus.quadrature.core.view.View;

import net.minecraft.client.Minecraft;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A benchmark, off unless {@code -Dquadrature.rays.bench=true}, which needs the light scene and its
 * buffers ({@code -Dquadrature.scene=true -Dquadrature.scene.gpu=true}). Once the view has stood in one
 * cube with terrain caught up and nothing copied to the scene's buffers for
 * {@code -Dquadrature.rays.bench.settle} seconds (3), it traces two classes of rays, at three lengths,
 * in the batch each tier would trace, timing each trace on the GPU with timestamps:
 *
 * <ul>
 * <li>probes: a grid of probes about the player, each casting its rays in directions spread over the
 * sphere, as indirect light would;</li>
 * <li>shadows: from just above the first thing met straight down at each point of a square 256 blocks
 * across about the player, toward the sun or the moon, side by side and parallel, as far shadows would;</li>
 * <li>low sun: the same, toward a sun {@value #LOW_SUN_DEGREES} degrees above the eastern horizon, whatever
 * the hour, since the hour a tour holds may put the sun overhead.</li>
 * </ul>
 *
 * <p>A set traces in rounds, one trace a frame: each round traces every measurement once, so that every
 * class meets the GPU's clocks as they stand over the whole set, not only at its start or end. Rounds are
 * not timed until {@code -Dquadrature.rays.bench.warm} seconds (2) have passed since the set began, to
 * bring the clocks up. Each measurement is then timed {@value #REPEATS} times reading the scene's own
 * buffers and as many reading a copy of them the set made on the GPU at its start, which the CPU never
 * reads back, in alternate rounds; the medians are logged with a count of how the rays ended. A set needs
 * bricks in use, so that a place nothing has arrived at yet is not taken for a settled one. A set runs at
 * most every {@code -Dquadrature.rays.bench.every} seconds (20) in one place; one stops if the view moves
 * to another cube. With
 * {@code -Dquadrature.rays.check=true} a sample of each trace's rays and results is read back and the
 * rays traced again on the CPU over the scene's buffers as read back ({@link RayCheck}).
 *
 * <p>The traces run inside the frame, after the scene's buffers are brought up to date, so the GPU time
 * of whatever stage is open then includes them: a run with the benchmark on measures rays, not frames.
 */
public final class RayBench {

    public static final boolean REQUESTED = Boolean.getBoolean("quadrature.rays.bench");
    static final boolean CHECK = Boolean.getBoolean("quadrature.rays.check");
    private static final double SETTLE = number("quadrature.rays.bench.settle", 3);
    private static final double EVERY = number("quadrature.rays.bench.every", 20);
    private static final double WARM = number("quadrature.rays.bench.warm", 2);
    static final int REPEATS = 5;
    static final int[] LENGTHS = {16, 64, 256};
    static final String[] CLASSES = {"probes", "shadows", "low sun"};
    private static final int PROBES = 0;
    private static final int SHADOWS = 1;
    /** How high the low sun stands. */
    static final double LOW_SUN_DEGREES = 20;
    /** The celestial angle at which the sun stands that high in the morning. */
    private static final double LOW_SUN_ANGLE = 1 - Math.acos(Math.sin(Math.toRadians(LOW_SUN_DEGREES))) / (2 * Math.PI);
    private static final double SUN_TILT = number("quadrature.sun.tilt", 0);

    /** A tier's batch: cascades of probes, each probes along x, y, z, rays a probe and spacing; and the shadow square's points. */
    static final class Tier {
        final String name;
        final int[][] cascades;
        final int columnsX;
        final int columnsZ;

        Tier(String name, int[][] cascades, int columnsX, int columnsZ) {
            this.name = name;
            this.cascades = cascades;
            this.columnsX = columnsX;
            this.columnsZ = columnsZ;
        }

        int rays() {
            int rays = 0;
            for (int[] cascade : cascades) {
                rays += cascade[0] * cascade[1] * cascade[2] * cascade[3];
            }
            return rays;
        }

        String probes() {
            StringBuilder text = new StringBuilder();
            for (int[] cascade : cascades) {
                text.append(text.length() == 0 ? "" : " and ").append(String.format("%dx%dx%d probes %d apart with %d rays",
                    cascade[0], cascade[1], cascade[2], cascade[4], cascade[3]));
            }
            return text.toString();
        }
    }

    static final Tier[] TIERS = {
        new Tier("MEDIUM", new int[][] {{16, 8, 16, 32, 8}}, 256, 256),
        new Tier("HIGH", new int[][] {{32, 8, 32, 64, 8}}, 1024, 512),
        new Tier("MAX", new int[][] {{32, 8, 32, 128, 4}, {32, 8, 32, 128, 16}}, 2048, 1024),
    };
    private static final int MOST_RAYS = TIERS[TIERS.length - 1].rays();

    /** The two buffers a measurement is timed reading: the scene's own, and the set's copy of them on the GPU. */
    static final String[] WAYS = {"the scene's buffers", "a copy on the GPU"};
    private static final int SCENE = 0;
    private static final int COPY = 1;

    /** One measurement: a class, a tier, a length, and the times of its repeats, alternately from the two buffers. */
    private static final class Step {
        final int rayClass;
        final Tier tier;
        final int length;
        final double[][] millis = new double[2][REPEATS];
        int[] census;
        /** The scene as the latest repeat's trace read it: its generation, origin cube and box. */
        long generation;
        int originX;
        int originY;
        int originZ;
        int sizeX;
        int sizeY;

        Step(int rayClass, Tier tier, int length) {
            this.rayClass = rayClass;
            this.tier = tier;
            this.length = length;
        }

        String label() {
            return String.format("%s, %s (%,d rays), length %d", CLASSES[rayClass], tier.name, tier.rays(), length);
        }
    }

    private final RayTracer tracer = RayTracer.get();
    private final ComputeState state = new ComputeState();
    private final RayCheck check = CHECK ? new RayCheck() : null;
    private final IntBuffer censusBack = BufferUtils.createIntBuffer(BenchPrograms.CENSUS_WORDS);
    private boolean prepared;
    private boolean failed;
    private boolean warned;
    /** The copy of the scene's lookup and bricks a set traces besides the scene's own, and what it was copied from. */
    private int lookupCopy;
    private int brickCopy;
    private long lookupCopyBytes;
    private long brickCopyBytes;
    private long copyGeneration = -1;
    private int copySizeX;
    private int copySizeY;
    private int copySizeZ;
    private int probesProgram;
    private int columnsProgram;
    private int shadowsProgram;
    private int censusProgram;
    private int rayBuffer;
    private int resultBuffer;
    private int censusBuffer;

    private long stillSince = System.nanoTime();
    private int lastX = Integer.MIN_VALUE;
    private int lastY;
    private int lastZ;
    private long lastGeneration = -1;
    private int setX = Integer.MIN_VALUE;
    private int setY;
    private int setZ;
    private long lastSetEnd;

    private int sets;
    private long setStart;
    private final List<Step> steps = new ArrayList<>();
    /** Whether the set's rounds are still untimed, the timed round, and the measurement within it. */
    private boolean warming;
    private int round;
    private int index;
    private Step running;
    /** The repeat the trace in flight is timed as, or -1 in the untimed round. */
    private int runningRepeat;
    private RayTrace.Timed timing;
    private long fence;
    private final double[] toward = new double[3];
    private final double[] lowSun = new double[3];

    /** The benchmark where it is asked for, or null. */
    public static RayBench create() {
        return REQUESTED ? new RayBench() : null;
    }

    private RayBench() {
        Quadrature.LOGGER.info("Ray benchmark on: a set after {} s standing with terrain caught up, at most every {} s in one place; "
            + "check {}.", SETTLE, EVERY, CHECK ? "on" : "off");
    }

    /**
     * Runs a frame's part: waits for the last trace's time, starts the next, or starts a set where the
     * view has settled. Call after the scene's buffers are brought up to date for the frame.
     *
     * @param caughtUp whether every cube held is meshed as it stands
     */
    public void frame(View view, double partialTicks, boolean caughtUp) {
        PublishedScene scene = tracer.scene();
        if (scene == null) {
            if (!warned) {
                warned = true;
                Quadrature.LOGGER.error(tracer.failed() ? "Ray benchmark: the trace program did not build; nothing is traced."
                    : "Ray benchmark: no light scene on the GPU to trace; it needs -Dquadrature.scene=true and "
                    + "-Dquadrature.scene.gpu=true.");
            }
            return;
        }
        if (!prepare()) {
            return;
        }
        long now = System.nanoTime();
        int x = scene.originX();
        int y = scene.originY();
        int z = scene.originZ();
        boolean moved = x != lastX || y != lastY || z != lastZ;
        if (moved || scene.generation() != lastGeneration || !caughtUp) {
            stillSince = now;
            lastX = x;
            lastY = y;
            lastZ = z;
            lastGeneration = scene.generation();
        }
        if (!steps.isEmpty()) {
            if (x != setX || y != setY || z != setZ) {
                Quadrature.LOGGER.info("Ray benchmark set {} stopped {}: the view moved to cube ({}, {}, {}).", sets,
                    warming ? "while warming" : "in timed round " + (round + 1) + " of " + runs(), x, y, z);
                endSet(now, false);
                return;
            }
            step(scene, view, partialTicks);
            return;
        }
        boolean settled = now - stillSince >= SETTLE * 1e9 && scene.slotsInUse() > 0;
        boolean due = x != setX || y != setY || z != setZ || now - lastSetEnd >= EVERY * 1e9;
        if (settled && due) {
            beginSet(scene, view, partialTicks, now);
        }
    }

    private boolean prepare() {
        if (!prepared && !failed) {
            prepared = true;
            probesProgram = TraceProgram.link("probes", BenchPrograms.PROBES_COMPUTE);
            columnsProgram = TraceProgram.link("columns", BenchPrograms.COLUMNS_COMPUTE);
            shadowsProgram = TraceProgram.link("shadows", BenchPrograms.SHADOWS_COMPUTE);
            censusProgram = TraceProgram.link("census", BenchPrograms.CENSUS_COMPUTE);
            failed = probesProgram == 0 || columnsProgram == 0 || shadowsProgram == 0 || censusProgram == 0;
            if (failed) {
                Quadrature.LOGGER.error("Ray benchmark: a program did not build; nothing is traced.");
                return false;
            }
            rayBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(rayBuffer, (long) MOST_RAYS * TraceFormat.RAY_BYTES, 0);
            resultBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(resultBuffer, (long) MOST_RAYS * TraceFormat.RESULT_BYTES, 0);
            censusBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(censusBuffer, BenchPrograms.CENSUS_WORDS * 4L, 0);
            Quadrature.LOGGER.info("Ray benchmark buffers: room for {} rays, {} MiB, and a copy of the scene's lookup and bricks.",
                String.format("%,d", MOST_RAYS), String.format("%.1f", benchBytes() / 1048576.0));
        }
        return !failed;
    }

    private static long benchBytes() {
        return (long) MOST_RAYS * (TraceFormat.RAY_BYTES + TraceFormat.RESULT_BYTES) + BenchPrograms.CENSUS_WORDS * 4L
            + 8192;
    }

    private void beginSet(PublishedScene scene, View view, double partialTicks, long now) {
        sets++;
        setStart = now;
        setX = scene.originX();
        setY = scene.originY();
        setZ = scene.originZ();
        steps.clear();
        warming = true;
        round = 0;
        index = 0;
        copy(scene);
        for (int rayClass = 0; rayClass < CLASSES.length; rayClass++) {
            for (int length : LENGTHS) {
                for (Tier tier : TIERS) {
                    steps.add(new Step(rayClass, tier, length));
                }
            }
        }
        lightDirection(partialTicks);
        Quadrature.LOGGER.info("Ray benchmark set {} begins at ({}, {}, {}) in cube ({}, {}, {}); the box {} by {} by {} cubes; "
                + "scene buffers {} MiB, {} brick slots in use and {} at most; toward the light ({}, {}, {}), the low sun ({}, {}, "
                + "{}); {} timed traces after {} s of untimed ones.",
            sets, String.format("%.1f", view.originX()), String.format("%.1f", view.originY()),
            String.format("%.1f", view.originZ()), setX, setY, setZ, scene.sizeX(), scene.sizeY(), scene.sizeZ(),
            String.format("%.2f", scene.bytes() / 1048576.0), scene.slotsInUse(), scene.mostSlotsInUse(),
            String.format("%.3f", toward[0]), String.format("%.3f", toward[1]), String.format("%.3f", toward[2]),
            String.format("%.3f", lowSun[0]), String.format("%.3f", lowSun[1]), String.format("%.3f", lowSun[2]),
            steps.size() * runs(), WARM);
        if (check != null) {
            check.begin(sets);
        }
        step(scene, view, partialTicks);
    }

    /** Copies the scene's lookup and bricks on the GPU into the set's own buffers, made anew when their size changes. */
    private void copy(PublishedScene scene) {
        long lookupBytes = GL45.glGetNamedBufferParameteri(scene.lookupBuffer(), org.lwjgl.opengl.GL15.GL_BUFFER_SIZE);
        long brickBytes = GL45.glGetNamedBufferParameteri(scene.brickBuffer(), org.lwjgl.opengl.GL15.GL_BUFFER_SIZE);
        if (lookupBytes != lookupCopyBytes || brickBytes != brickCopyBytes) {
            if (lookupCopy != 0) {
                org.lwjgl.opengl.GL15.glDeleteBuffers(lookupCopy);
                org.lwjgl.opengl.GL15.glDeleteBuffers(brickCopy);
            }
            lookupCopy = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(lookupCopy, lookupBytes, 0);
            brickCopy = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(brickCopy, brickBytes, 0);
            lookupCopyBytes = lookupBytes;
            brickCopyBytes = brickBytes;
            Quadrature.LOGGER.info("Ray benchmark copy of the scene: {} KiB of lookup and {} MiB of bricks.", lookupBytes >> 10,
                String.format("%.1f", brickBytes / 1048576.0));
        }
        GL45.glCopyNamedBufferSubData(scene.lookupBuffer(), lookupCopy, 0, 0, lookupBytes);
        GL45.glCopyNamedBufferSubData(scene.brickBuffer(), brickCopy, 0, 0, brickBytes);
        copyGeneration = scene.generation();
        copySizeX = scene.sizeX();
        copySizeY = scene.sizeY();
        copySizeZ = scene.sizeZ();
    }

    private void lightDirection(double partialTicks) {
        SunPath.towardLight(Minecraft.getMinecraft().world.getCelestialAngle((float) partialTicks), SUN_TILT, toward);
        SunPath.towardSun(LOW_SUN_ANGLE, SUN_TILT, lowSun);
    }

    /** Waits for the trace in flight, then starts the next. */
    private void step(PublishedScene scene, View view, double partialTicks) {
        if (running != null) {
            if (timing == null) {
                Quadrature.LOGGER.error("Ray benchmark set {} stopped: the scene could not be traced.", sets);
                endSet(System.nanoTime(), false);
                return;
            }
            if (!timing.done()) {
                return;
            }
            int status = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0);
            if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
                return;
            }
            GL32.glDeleteSync(fence);
            fence = 0;
            int repeat = runningRepeat;
            if (repeat >= 0) {
                int way = way(repeat);
                running.millis[way][repeat / runsPerRepeat()] = timing.gpuMillis();
                if (check != null && repeat >= runs() - runsPerRepeat()) {
                    // The last repeat of each way: its rays and results, set beside the CPU's. A trace of the copy
                    // read what the scene held when the copy was made.
                    check.sample(running.label() + " from " + WAYS[way], rayBuffer, resultBuffer, running.tier.rays(),
                        way == COPY ? copyGeneration : running.generation, running.originX, running.originY, running.originZ,
                        running.sizeX, running.sizeY);
                }
                if (repeat == runs() - 1) {
                    censusBack.clear();
                    GL45.glGetNamedBufferSubData(censusBuffer, 0, censusBack);
                    running.census = new int[BenchPrograms.CENSUS_WORDS];
                    censusBack.get(running.census);
                }
            }
            running = null;
            timing = null;
            if (++index == steps.size()) {
                index = 0;
                if (warming) {
                    warming = System.nanoTime() - setStart < WARM * 1e9;
                } else {
                    round++;
                }
            }
        }
        if (round >= runs()) {
            for (Step done : steps) {
                report(done);
            }
            endSet(System.nanoTime(), true);
            return;
        }
        running = steps.get(index);
        runningRepeat = warming ? -1 : round;
        dispatch(running, scene, view, runningRepeat);
    }

    /** Prepares a measurement's rays and traces them, timed as a repeat, or untimed where the repeat is -1. */
    private void dispatch(Step step, PublishedScene scene, View view, int repeat) {
        int rays = step.tier.rays();
        // The player relative to the origin cube's lowest corner, where rays are given.
        float px = (float) (view.originX() - scene.originX() * 16.0);
        float py = (float) (view.originY() - scene.originY() * 16.0);
        float pz = (float) (view.originZ() - scene.originZ() * 16.0);
        state.save();
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, rayBuffer);
        if (step.rayClass == PROBES) {
            int first = 0;
            for (int[] cascade : step.tier.cascades) {
                int count = cascade[0] * cascade[1] * cascade[2] * cascade[3];
                float spacing = cascade[4];
                GL41.glProgramUniform4i(probesProgram, 0, cascade[0], cascade[1], cascade[2], cascade[3]);
                GL41.glProgramUniform4f(probesProgram, 1, corner(px, cascade[0], spacing), corner(py, cascade[1], spacing),
                    corner(pz, cascade[2], spacing), spacing);
                GL41.glProgramUniform3ui(probesProgram, 2, first, count, 0x9E3779B9 * sets);
                GL41.glProgramUniform1f(probesProgram, 3, step.length);
                GL20.glUseProgram(probesProgram);
                GL43.glDispatchCompute((count + TraceProgram.GROUP - 1) / TraceProgram.GROUP, 1, 1);
                first += count;
            }
            state.restore();
        } else {
            int upward = (scene.sizeY() - 1) / 2;
            float top = (upward + 1) * 16 - 0.5f;
            float stepX = 256.0f / step.tier.columnsX;
            float stepZ = 256.0f / step.tier.columnsZ;
            GL41.glProgramUniform3i(columnsProgram, 0, step.tier.columnsX, step.tier.columnsZ, rays);
            // A sixteenth of a block off the whole numbers, so that no column runs down the faces of cells.
            GL41.glProgramUniform4f(columnsProgram, 1, px - 128 + 0.0625f, pz - 128 + 0.0625f, stepX, stepZ);
            GL41.glProgramUniform2f(columnsProgram, 2, top, scene.sizeY() * 16);
            GL20.glUseProgram(columnsProgram);
            GL43.glDispatchCompute((rays + TraceProgram.GROUP - 1) / TraceProgram.GROUP, 1, 1);
            state.restore();
            tracer.traceUntimed(rayBuffer, resultBuffer, rays);
            state.save();
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, rayBuffer);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, resultBuffer);
            GL41.glProgramUniform3i(shadowsProgram, 0, step.tier.columnsX, step.tier.columnsZ, rays);
            GL41.glProgramUniform4f(shadowsProgram, 1, px - 128 + 0.0625f, pz - 128 + 0.0625f, stepX, stepZ);
            double[] light = step.rayClass == SHADOWS ? toward : lowSun;
            GL41.glProgramUniform4f(shadowsProgram, 2, (float) light[0], (float) light[1], (float) light[2], step.length);
            GL41.glProgramUniform1f(shadowsProgram, 3, top);
            GL20.glUseProgram(shadowsProgram);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            GL43.glDispatchCompute((rays + TraceProgram.GROUP - 1) / TraceProgram.GROUP, 1, 1);
            state.restore();
        }
        timing = repeat >= 0 && way(repeat) == COPY ? tracer.trace(rayBuffer, resultBuffer, rays, lookupCopy, brickCopy)
            : tracer.trace(rayBuffer, resultBuffer, rays, RayTracer.STORAGE);
        step.generation = scene.generation();
        step.originX = scene.originX();
        step.originY = scene.originY();
        step.originZ = scene.originZ();
        step.sizeX = scene.sizeX();
        step.sizeY = scene.sizeY();
        if (repeat == runs() - 1) {
            GL45.glClearNamedBufferData(censusBuffer, GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT,
                (java.nio.ByteBuffer) null);
            state.save();
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, resultBuffer);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, censusBuffer);
            GL41.glProgramUniform1ui(censusProgram, 0, rays);
            GL20.glUseProgram(censusProgram);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            GL43.glDispatchCompute((rays + TraceProgram.GROUP - 1) / TraceProgram.GROUP, 1, 1);
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            state.restore();
        }
        fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /** Timed traces of a measurement: each repeat once from each buffer. */
    private int runs() {
        return REPEATS * runsPerRepeat();
    }

    private int runsPerRepeat() {
        return 2;
    }

    /** The buffers a timed trace of a measurement reads: alternately, the scene's own first. */
    private int way(int run) {
        return run % 2 == 0 ? SCENE : COPY;
    }

    /** The lowest probe of a grid about a place, at the middle of a cell, on whole multiples of the spacing. */
    private static float corner(float at, int probes, float spacing) {
        return (float) (Math.floor(at / spacing) * spacing - probes / 2 * spacing + 0.5);
    }

    private static double median(double[] millis) {
        double[] sorted = millis.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static double least(double[] millis) {
        return Arrays.stream(millis).min().orElse(Double.NaN);
    }

    private static double most(double[] millis) {
        return Arrays.stream(millis).max().orElse(Double.NaN);
    }

    private void report(Step step) {
        double[] times = step.millis[SCENE];
        double median = median(times);
        int rays = step.tier.rays();
        int[] census = step.census;
        double share = 100.0 / rays;
        String other = String.format(" (from a copy on the GPU %.3f ms, least %.3f, most %.3f)", median(step.millis[COPY]),
            least(step.millis[COPY]), most(step.millis[COPY]));
        Quadrature.LOGGER.info("Ray benchmark set {}, {}: GPU {} ms median of {} (least {}, most {}){}; {} million rays a second; "
                + "hit {}% ({}% began in a cell that blocks), missed {}%, left the box {}%, not available {}%, out of steps {}%; "
                + "{} nodes a ray, {} at most; {} blocks to a hit on average. {}.",
            sets, step.label(), String.format("%.3f", median), REPEATS, String.format("%.3f", least(times)),
            String.format("%.3f", most(times)), other, String.format("%.1f", rays / median / 1000.0),
            String.format("%.1f", census[TraceFormat.HIT] * share), String.format("%.1f", census[BenchPrograms.CENSUS_INSIDE] * share),
            String.format("%.1f", census[TraceFormat.MISS] * share), String.format("%.1f", census[TraceFormat.LEFT] * share),
            String.format("%.1f", census[TraceFormat.NOT_AVAILABLE] * share), String.format("%.1f", census[TraceFormat.STEPS] * share),
            String.format("%.1f", (census[BenchPrograms.CENSUS_VISITS] & 0xFFFFFFFFL) / (double) rays),
            census[BenchPrograms.CENSUS_MOST],
            String.format("%.1f", census[TraceFormat.HIT] == 0 ? 0.0 : (census[BenchPrograms.CENSUS_DISTANCE] & 0xFFFFFFFFL)
                / (double) census[TraceFormat.HIT]),
            step.rayClass == PROBES ? step.tier.probes() : String.format("%d by %d points %.3f by %.3f blocks apart",
                step.tier.columnsX, step.tier.columnsZ, 256.0 / step.tier.columnsX, 256.0 / step.tier.columnsZ));
    }

    private void endSet(long now, boolean whole) {
        if (whole) {
            StringBuilder line = new StringBuilder();
            for (int rayClass = 0; rayClass < CLASSES.length; rayClass++) {
                line.append("; ").append(CLASSES[rayClass]).append(", ms at lengths");
                for (int length : LENGTHS) {
                    line.append(' ').append(length);
                }
                for (Tier tier : TIERS) {
                    line.append(", ").append(tier.name);
                    for (Step step : steps) {
                        if (step.rayClass == rayClass && step.tier == tier) {
                            line.append(' ').append(String.format("%.3f", median(step.millis[SCENE])));
                        }
                    }
                }
            }
            Quadrature.LOGGER.info("Ray benchmark set {} done in {} s{}.", sets, String.format("%.1f", (now - setStart) / 1e9),
                line);
            if (check != null) {
                check.compare(tracer.blockingBuffer(), lookupCopy, brickCopy, copyGeneration, copySizeX, copySizeY, copySizeZ);
            }
        } else if (fence != 0) {
            GL32.glDeleteSync(fence);
            fence = 0;
        }
        if (running != null && timing != null) {
            // Let its queries be read and freed.
            timing.done();
        }
        running = null;
        timing = null;
        steps.clear();
        warming = false;
        round = 0;
        index = 0;
        lastSetEnd = now;
    }

    private static double number(String property, double otherwise) {
        try {
            return Double.parseDouble(System.getProperty(property, Double.toString(otherwise)));
        } catch (NumberFormatException notANumber) {
            return otherwise;
        }
    }
}
