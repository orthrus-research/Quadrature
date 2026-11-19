package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.platform.GpuMemory;
import cc.orthrus.quadrature.core.platform.GpuMemorySource;
import cc.orthrus.quadrature.core.platform.PlatformProfile;
import cc.orthrus.quadrature.core.scene.PublishedScene;
import cc.orthrus.quadrature.core.tier.BenchmarkResult;
import cc.orthrus.quadrature.core.tier.BenchmarkRun;
import cc.orthrus.quadrature.core.tier.ClockSettle;
import cc.orthrus.quadrature.core.tier.EvictionWatch;
import cc.orthrus.quadrature.core.tier.StoredTiers;
import cc.orthrus.quadrature.core.tier.Tier;
import cc.orthrus.quadrature.core.tier.TierBenchmark;
import cc.orthrus.quadrature.core.tier.TierCalibration;
import cc.orthrus.quadrature.core.tier.TierChoice;
import cc.orthrus.quadrature.core.tier.TierGroup;
import cc.orthrus.quadrature.core.tier.TierSelection;
import cc.orthrus.quadrature.core.tier.TerrainSettle;
import cc.orthrus.quadrature.core.terrain.SearchEdge;
import cc.orthrus.quadrature.core.view.View;
import cc.orthrus.quadrature.depth.DepthCalls;
import cc.orthrus.quadrature.terrain.TerrainHook;

import com.google.gson.GsonBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.BlockRenderLayer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tier benchmark's host (D22). Off unless asked for:
 *
 * <ul>
 * <li>{@code -Dquadrature.tierBench=calibrate} runs the benchmark wherever the player holds still with near
 * terrain caught up, {@code quadrature.tierBench.repeat} times at each place (3), at the heights
 * {@code quadrature.tierBench.heights} (720, 1080, 1440 and 2160, at the window's aspect), and logs, stores
 * and appends each result to the client's {@code quadrature-tier-runs.jsonl};
 * <li>{@code -Dquadrature.tierBench=once} runs it once at the tiers' render scales of the window;
 * <li>{@code -Dquadrature.tierBench.firstLaunch=true} is the path a player will take: it runs once where
 * nothing is stored under this GPU, driver and benchmark version, and otherwise takes the stored tiers.
 * It stays off by default until {@link TierCalibration#CURRENT} is measured.
 * </ul>
 *
 * <p>The benchmark takes about 70 frames, one probe repetition a frame after near terrain's opaque layers,
 * so that no frame is held long: the bandwidth kernel first, which also brings the GPU's clock up, then
 * the opaque terrain probe at each resolution in turn, the light view's box, and the indirect trace where a
 * {@link TraceProbe} is given. It is abandoned, and begun again later, if the view moves or terrain falls
 * behind while it runs. Every query result is read only once the driver reports it ready.
 *
 * <p>With a tier chosen, the frame's 1% lows and the driver's evictions are watched in windows of two
 * seconds ({@link EvictionWatch}), and the memory tier is lowered a step where both say memory is short.
 * The player's tier for any group is read from {@code override <group> = <tier>} lines of the stored file
 * and from {@code -Dquadrature.tier.<group>=<tier>}.
 */
public final class TierBench {

    static final String SWITCH = "quadrature.tierBench";
    private static final String MODE = System.getProperty(SWITCH, "off");
    private static final boolean CALIBRATE = MODE.equals("calibrate");
    private static final boolean ONCE = MODE.equals("once");
    /** The first-launch path, off by default until the calibration is measured. */
    private static final boolean FIRST_LAUNCH = Boolean.getBoolean(SWITCH + ".firstLaunch");
    private static final int REPEAT = Integer.getInteger(SWITCH + ".repeat", 3);
    /** Frames the view must hold still, with nothing waiting and no mesh coming or going, before a run begins. */
    private static final int STILL_FRAMES = Integer.getInteger(SWITCH + ".stillFrames", 240);
    /** Frames between runs at one place. */
    private static final int BETWEEN_RUNS = 30;
    private static final String HEIGHTS = System.getProperty(SWITCH + ".heights", "720,1080,1440,2160");
    /**
     * An enforced memory budget for a test: the card is taken to have this many MiB, the memory in use at the
     * first screen counted against it (D22: the lowest tier's budgets are measured under an 8 GB budget).
     */
    private static final long MEMORY_LIMIT_MIB = Long.getLong("quadrature.tier.memoryLimitMiB", 0L);
    /** Memory the world takes beyond Quadrature's own once it is entered: the game's textures, models and buffers. */
    private static final double WORLD_HEADROOM_MIB = 512;
    private static final String FILE = "quadrature-tiers.txt";
    private static final String RUNS = "quadrature-tier-runs.jsonl";
    private static final long WINDOW_NANOS = 2_000_000_000L;

    private static TraceProbe trace = TraceProbe.NONE;
    private static TierBench instance;

    private final String gpu;
    private final String driver;
    private final String key;
    private final GpuMemorySource memory;
    private final GpuMemory atGate;
    private StoredTiers stored;
    private Map<TierGroup, Tier> choice;
    private Tier speedTier;
    private Tier memoryTier;
    private boolean finished;

    // Where the view stood last frame, and for how long it has stood still with nothing waiting.
    private final float[] lastModelView = new float[16];
    private final double[] lastOrigin = new double[3];
    private int stillFrames;
    private final TerrainSettle settle = new TerrainSettle();
    private boolean settleSaid;
    /** Whether this place's wait on the light scene's copy, near terrain caught up, has been logged. */
    private boolean sceneBehindSaid;
    private int runsHere;
    private int sinceRun;
    private int runs;

    private Run running;

    // The run-time watch over evictions and 1% lows.
    private final EvictionWatch watch = new EvictionWatch(0.2, 2, 5);
    private final long[] intervals = new long[2048];
    private int intervalCount;
    private long lastFrameNanos;
    private long windowStart;

    private TierBench(String gpu, String driver, GpuMemorySource memory, GpuMemory atGate) {
        this.gpu = gpu;
        this.driver = driver;
        this.key = TierBenchmark.key(gpu, driver);
        this.memory = memory;
        this.atGate = atGate;
        this.stored = read();
    }

    static boolean enabled() {
        return CALIBRATE || ONCE || FIRST_LAUNCH;
    }

    /**
     * Gives the benchmark the indirect trace to time. Call on the client thread; null puts back the
     * absence of one.
     */
    public static void traceProbe(TraceProbe probe) {
        trace = probe == null ? TraceProbe.NONE : probe;
    }

    /** The tier of every group, or null before one is chosen. */
    public static Map<TierGroup, Tier> choice() {
        return instance == null ? null : instance.choice;
    }

    /**
     * At the first screen, once the gate has passed: reads what the key is made of and the GPU memory free
     * before any world, and waits for a world. Call on the thread that owns the context.
     */
    static void atGate(PlatformProfile profile, GpuMemorySource memory) {
        if (instance != null) {
            return;
        }
        byte[] deviceUuid = null;
        byte[] driverUuid = null;
        if (profile.extensions().contains("GL_EXT_memory_object")) {
            // Interop's identifiers name the device and the driver build exactly; only their digest is kept.
            ByteBuffer bytes = BufferUtils.createByteBuffer(EXTMemoryObject.GL_UUID_SIZE_EXT);
            EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, 0, bytes);
            deviceUuid = new byte[EXTMemoryObject.GL_UUID_SIZE_EXT];
            bytes.get(deviceUuid);
            bytes.clear();
            EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObject.GL_DRIVER_UUID_EXT, bytes);
            driverUuid = new byte[EXTMemoryObject.GL_UUID_SIZE_EXT];
            bytes.get(driverUuid);
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                // A driver that lists the extension but refuses the query leaves an error: the digests still hold.
            }
        }
        String gpu = TierBenchmark.gpuDigest(profile.vendor(), profile.renderer(), deviceUuid);
        String driver = TierBenchmark.driverDigest(profile.versionString(), driverUuid);
        if (trace == TraceProbe.NONE && BrickTraceProbe.REQUESTED) {
            trace = new BrickTraceProbe();
        }
        instance = new TierBench(gpu, driver, memory, memory.read());
        TerrainHook.afterOpaque(instance::frame);
        Quadrature.LOGGER.info("Tier benchmark: {}, benchmark {}, key {} (GPU {}, driver {}); {}; stored: {}.", MODE.equals("off")
                ? "first-launch path" : MODE, TierBenchmark.VERSION, instance.key, gpu, driver,
            TierCalibration.CURRENT.calibrated() ? "calibrated" : "not calibrated", instance.stored == null ? "nothing"
                : "key " + instance.stored.key() + ", speed " + instance.stored.speed() + ", memory " + instance.stored.memory());
        StoredTiers stored = instance.stored;
        Quadrature.LOGGER.info("Tier benchmark: the first-launch path {}{}.", StoredTiers.needsRun(stored, instance.key)
                ? "runs the benchmark: " + (stored == null ? "nothing is stored" : "the stored result is under another key, "
                    + (stored.gpu().equals(gpu) ? "the same GPU" : "another GPU") + (stored.driver().equals(driver)
                    ? "" : ", another driver") + (stored.benchmark() != TierBenchmark.VERSION ? ", another benchmark version" : ""))
                : "takes the stored tiers",
            StoredTiers.previousSpeed(stored, gpu) != null ? "; the band holds to speed " + stored.speed() + " and memory "
                + stored.memory() : "");
        if (FIRST_LAUNCH && !CALIBRATE && !ONCE && !StoredTiers.needsRun(stored, instance.key)) {
            instance.adopt(stored.speed(), stored.memory(), "stored");
            instance.finished = true;
        }
    }

    /** Once a frame, after near terrain's opaque layers. */
    private void frame() {
        try {
            long now = System.nanoTime();
            if (choice != null) {
                watchFrame(now);
            }
            TerrainHook.Probes probes = TerrainHook.probes();
            View view = TerrainHook.mainView();
            if (probes == null || view == null) {
                return;
            }
            boolean viewStill = still(view);
            if (!viewStill) {
                settle.arrived(now);
                settleSaid = false;
                sceneBehindSaid = false;
            }
            // A place still arriving can hold its count of meshes for seconds between batches: the view's search must
            // also meet no cube that is not loaded, or find their count standing still; and the light scene's copy,
            // which the trace walks, must have caught up with the scene (TerrainSettle).
            SearchEdge edge = TerrainHook.searchEdge();
            PublishedScene published = TerrainHook.publishedScene();
            boolean steady = settle.frame(now, probes.waiting(), probes.held(), edge != null && edge.searched(),
                edge == null ? -1 : edge.unloaded(), TerrainHook.sceneCopyWaiting(),
                published == null ? 0 : published.slotsInUse());
            if (settle.sceneBehind() && !sceneBehindSaid) {
                sceneBehindSaid = true;
                Quadrature.LOGGER.info("Tier benchmark: near terrain has caught up, the light scene's copy not: {} cubes, bricks "
                    + "and entries to take and copy, {} bricks held; nothing is timed until it has.", settle.sceneWaiting(),
                    settle.sceneBricks());
            }
            if (steady && !settleSaid) {
                settleSaid = true;
                double sceneSeconds = settle.secondsTerrainCaughtUp(now);
                Quadrature.LOGGER.info("Tier benchmark: near terrain caught up {} s after the view came to rest, with {} meshes "
                    + "held; {}{}.", fixed(settle.secondsSinceArrival(now) - sceneSeconds), probes.held(), settle.byPlateau()
                    ? settle.unloaded() + " cubes of the view's box not loaded, their count unchanged for 15 s"
                    : "no cube of the view's box not loaded", settle.sceneWaiting() < 0 ? ""
                    : "; the light scene's copy caught up " + fixed(sceneSeconds) + " s after it, with " + settle.sceneBricks()
                    + " bricks held");
            }
            boolean still = viewStill && steady;
            if (running != null) {
                if (!still) {
                    Quadrature.LOGGER.info("Tier benchmark: run abandoned at its step {} of {}: {}.", running.next,
                        running.steps.size(), steady ? "the view moved" : "terrain or the light scene's copy changed");
                    running.delete();
                    running = null;
                    stillFrames = 0;
                    return;
                }
                if (running.settling) {
                    running.settleFrame();
                } else if (running.next < running.steps.size()) {
                    running.issue(probes, running.steps.get(running.next++));
                } else if (running.ready()) {
                    finish(running);
                    running.delete();
                    running = null;
                    sinceRun = 0;
                }
                return;
            }
            if (!still) {
                stillFrames = 0;
                runsHere = 0;
                return;
            }
            stillFrames++;
            sinceRun++;
            if (finished || stillFrames < STILL_FRAMES || runsHere >= REPEAT || runsHere > 0 && sinceRun < BETWEEN_RUNS) {
                return;
            }
            running = new Run(CALIBRATE ? calibrationSizes() : tierSizes());
            runsHere++;
        } catch (RuntimeException e) {
            Quadrature.LOGGER.error("The tier benchmark failed and is stopped; the game continues without it.", e);
            // A step may have stopped with its own target or program bound.
            GL20.glUseProgram(0);
            Minecraft minecraft = Minecraft.getMinecraft();
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER,
                OpenGlHelper.isFramebufferEnabled() ? minecraft.getFramebuffer().framebufferObject : 0);
            GlStateManager.viewport(0, 0, minecraft.displayWidth, minecraft.displayHeight);
            if (running != null) {
                running.delete();
                running = null;
            }
            finished = true;
            TerrainHook.afterOpaque(null);
        }
    }

    /** Whether the view stands where it stood last frame, turned the same way; remembers it for the next. */
    private boolean still(View view) {
        float[] modelView = view.modelView();
        boolean same = Math.abs(view.originX() - lastOrigin[0]) < 1e-3 && Math.abs(view.originY() - lastOrigin[1]) < 1e-3
            && Math.abs(view.originZ() - lastOrigin[2]) < 1e-3;
        for (int i = 0; i < 16 && same; i++) {
            same = Math.abs(modelView[i] - lastModelView[i]) < 1e-5f;
        }
        System.arraycopy(modelView, 0, lastModelView, 0, 16);
        lastOrigin[0] = view.originX();
        lastOrigin[1] = view.originY();
        lastOrigin[2] = view.originZ();
        return same;
    }

    /** The calibration's resolutions: the heights asked for, at the window's aspect. */
    private static int[][] calibrationSizes() {
        Minecraft minecraft = Minecraft.getMinecraft();
        double aspect = (double) minecraft.displayWidth / minecraft.displayHeight;
        String[] heights = HEIGHTS.split(",");
        int[][] sizes = new int[heights.length][];
        for (int i = 0; i < heights.length; i++) {
            int height = Integer.parseInt(heights[i].trim());
            sizes[i] = new int[] {(int) Math.round(height * aspect / 2) * 2, height};
        }
        return sizes;
    }

    /** The player's render resolutions: the window at each distinct render scale of the tiers. */
    private static int[][] tierSizes() {
        Minecraft minecraft = Minecraft.getMinecraft();
        double[] scales = Arrays.stream(TierBenchmark.RENDER_SCALES).distinct().toArray();
        int[][] sizes = new int[scales.length][];
        for (int i = 0; i < scales.length; i++) {
            sizes[i] = new int[] {(int) Math.round(minecraft.displayWidth * scales[i]),
                (int) Math.round(minecraft.displayHeight * scales[i])};
        }
        return sizes;
    }

    /** Reduces a finished run, chooses the tiers, logs and stores them. */
    private void finish(Run run) {
        Minecraft minecraft = Minecraft.getMinecraft();
        BenchmarkRun samples = run.samples(minecraft.displayWidth, minecraft.displayHeight);
        BenchmarkResult result = BenchmarkResult.of(samples);
        runs++;
        View view = TerrainHook.mainView();
        String where = String.format(Locale.ROOT, "%.1f, %.1f, %.1f", view.originX(), view.originY(), view.originZ());
        for (String line : result.describe()) {
            Quadrature.LOGGER.info("Tier benchmark run {} at {}: {}", runs, where, line);
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("run", runs);
        record.put("key", key);
        record.put("at", where);
        record.put("frames", run.steps.size() + run.spins.size());
        record.put("settleFrames", run.spins.size());
        record.put("settled", run.settle.settled() && run.memorySettle.settled());
        record.put("spinMs", run.settle.latestMedian());
        record.put("settleReadMs", run.memorySettle.latestMedian());
        record.put("time", java.time.Instant.now().toString());
        record.put("result", json(result));
        double available = availableMiB();
        if (!result.drewEnough()) {
            Quadrature.LOGGER.info("Tier benchmark run {}: the view drew fewer than {} primitives, too few to measure the card; "
                + "the run chooses nothing.", runs, TierBenchmark.LEAST_PRIMITIVES);
            record.put("counted", false);
            append(record);
            if (!CALIBRATE) {
                runsHere = 0;
                stillFrames = 0;
            }
            return;
        }
        TierSelection.Outcome outcome = TierCalibration.CURRENT.calibrated() ? TierSelection.choose(result,
            TierCalibration.CURRENT, available, StoredTiers.previousSpeed(stored, gpu), StoredTiers.previousMemory(stored, gpu))
            : null;
        if (outcome != null && !outcome.traceAgrees()) {
            Quadrature.LOGGER.info("Tier benchmark run {}: the trace's ratio {} stands {} times from the other probes' "
                    + "(terrain {}, light {}, bandwidth {}), past the {} allowed: it timed something other than the card, "
                    + "and the run chooses nothing.", runs, fixed(outcome.ratios().trace()),
                fixed(outcome.ratios().traceApart()), fixed(outcome.ratios().terrain()), fixed(outcome.ratios().light()),
                fixed(outcome.ratios().bandwidth()), fixed(TierSelection.TRACE_LEEWAY));
            record.put("counted", false);
            record.put("traceApart", outcome.ratios().traceApart());
            append(record);
            if (!CALIBRATE) {
                runsHere = 0;
                stillFrames = 0;
            }
            return;
        }
        record.put("counted", true);
        if (outcome != null) {
            Quadrature.LOGGER.info("Tier benchmark run {}: score {} against the reference; ratios terrain {}, light {}, trace {}, "
                    + "bandwidth {}; predicted ms {}, fits {}; speed tier {} ({} without the band); memory {} MiB available, "
                    + "needs {}, memory tier {} ({} without the band).", runs, fixed(outcome.score()),
                fixed(outcome.ratios().terrain()), fixed(outcome.ratios().light()), fixed(outcome.ratios().trace()),
                fixed(outcome.ratios().bandwidth()), fixed(outcome.predictedMs()), fixed(outcome.speedFits()),
                outcome.speed(), outcome.plainSpeed(), Math.round(available), fixed(outcome.needMiB()), outcome.memory(),
                outcome.plainMemory());
            record.put("score", outcome.score());
            record.put("predictedMs", outcome.predictedMs());
            record.put("speedFits", outcome.speedFits());
            record.put("speed", outcome.speed().name());
            record.put("plainSpeed", outcome.plainSpeed().name());
            record.put("availableMiB", available);
            record.put("memory", outcome.memory().name());
            record.put("dlssMs", outcome.dlssMs());
            record.put("dlssFit", outcome.dlssFit());
            Quadrature.LOGGER.info("Tier benchmark run {}: DLSS ({}) would take {} ms here, the handoff {}; the speed tier {} room "
                    + "for it (fit {}).", runs, TierCalibration.CURRENT.dlss().what(), fixed(outcome.dlssMs()),
                Double.isFinite(TierCalibration.CURRENT.dlss().handoffMs()) ? "counted" : "not yet measured and not counted",
                outcome.dlssOffered() ? "leaves" : "leaves no", fixed(outcome.dlssFit()));
            store(result, outcome);
            adopt(outcome.speed(), outcome.memory(), "benchmark run " + runs);
        } else {
            Quadrature.LOGGER.info("Tier benchmark run {}: the calibration is not measured; no tier is chosen ({} MiB available).",
                runs, Math.round(available));
            record.put("availableMiB", available);
        }
        append(record);
        if (!CALIBRATE) {
            finished = true;
        }
    }

    /** What Quadrature may hold: the memory free at the first screen, under the enforced budget where one is set. */
    private double availableMiB() {
        GpuMemory figures = atGate.currentAvailableKiB() >= 0 ? atGate : memory.read();
        if (figures.currentAvailableKiB() < 0) {
            return Double.POSITIVE_INFINITY;
        }
        double free = figures.currentAvailableKiB() / 1024.0;
        if (MEMORY_LIMIT_MIB > 0 && figures.dedicatedKiB() > 0) {
            double used = (figures.dedicatedKiB() - figures.currentAvailableKiB()) / 1024.0;
            free = Math.min(free, MEMORY_LIMIT_MIB - used);
        }
        return free - WORLD_HEADROOM_MIB;
    }

    private void adopt(Tier speed, Tier memoryChosen, String from) {
        speedTier = speed;
        memoryTier = memoryChosen;
        choice = TierChoice.choose(speed, memoryChosen, overrides());
        Quadrature.LOGGER.info("Tiers from {}: speed {}, memory {}; by group {}.", from, speed, memoryChosen, choice);
    }

    /** The player's tier for any group: the stored file's override lines, then the command line's. */
    private Map<TierGroup, Tier> overrides() {
        Map<TierGroup, Tier> overrides = new EnumMap<>(TierGroup.class);
        if (stored != null) {
            overrides.putAll(stored.overrides());
        }
        for (TierGroup group : TierGroup.values()) {
            Tier tier = Tier.parse(System.getProperty("quadrature.tier." + group.key()));
            if (tier != null) {
                overrides.put(group, tier);
            }
        }
        return overrides;
    }

    private void store(BenchmarkResult result, TierSelection.Outcome outcome) {
        Map<String, String> figures = new LinkedHashMap<>();
        figures.put("bandwidth small GB/s", fixed(result.smallGBs()));
        figures.put("bandwidth large GB/s", fixed(result.largeGBs()));
        if (result.terrainFit() != null) {
            figures.put("terrain ns a primitive", fixed(result.terrainFit().nsPerPrimitive()));
            figures.put("terrain ns a fragment", fixed(result.terrainFit().nsPerFragment()));
        }
        figures.put("light ns a primitive", fixed(result.lightNsPerPrimitive()));
        figures.put("trace billion rays a second", fixed(result.traceGigaRays()));
        figures.put("score", fixed(outcome.score()));
        StoredTiers next = new StoredTiers(TierBenchmark.VERSION, key, gpu, driver, result.outputWidth(),
            result.outputHeight(), outcome.speed(), outcome.memory(), figures,
            stored != null ? stored.overrides() : Map.of());
        Path target = Minecraft.getMinecraft().gameDir.toPath().resolve(FILE);
        try {
            Files.write(target, next.text());
            stored = next;
        } catch (IOException e) {
            Quadrature.LOGGER.error("The tier benchmark's result could not be written to {}.", target, e);
        }
    }

    private static StoredTiers read() {
        Path source = Minecraft.getMinecraft().gameDir.toPath().resolve(FILE);
        try {
            return Files.exists(source) ? StoredTiers.parse(Files.readAllBytes(source)) : null;
        } catch (IOException e) {
            Quadrature.LOGGER.warn("The tier benchmark's stored result could not be read from {}; it runs again.", source, e);
            return null;
        }
    }

    private static void append(Map<String, Object> record) {
        if (!CALIBRATE) {
            return;
        }
        Path target = Minecraft.getMinecraft().gameDir.toPath().resolve(RUNS);
        String line = new GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(record) + "\n";
        try {
            Files.write(target, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Quadrature.LOGGER.error("The tier benchmark's run could not be appended to {}.", target, e);
        }
    }

    private static Map<String, Object> json(BenchmarkResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", result.version());
        out.put("output", new int[] {result.outputWidth(), result.outputHeight()});
        out.put("smallGBs", result.smallGBs());
        out.put("largeGBs", result.largeGBs());
        out.put("smallSpread", result.smallSpread());
        out.put("largeSpread", result.largeSpread());
        List<Map<String, Object>> terrain = new ArrayList<>();
        for (BenchmarkResult.TerrainPoint point : result.terrain()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("size", new int[] {point.width(), point.height()});
            entry.put("ms", point.medianMs());
            entry.put("spread", point.spread());
            entry.put("fragments", point.fragments());
            entry.put("primitives", point.primitives());
            terrain.add(entry);
        }
        out.put("terrain", terrain);
        if (result.terrainFit() != null) {
            out.put("terrainInterceptMs", result.terrainFit().interceptMs());
            out.put("terrainMsPerMegaFragment", result.terrainFit().msPerMegaFragment());
            out.put("terrainNsPerPrimitive", result.terrainFit().nsPerPrimitive());
        }
        out.put("lightMs", result.lightMs());
        out.put("lightSpread", result.lightSpread());
        out.put("lightPrimitives", result.lightPrimitives());
        out.put("lightFragments", result.lightFragments());
        List<Map<String, Object>> traced = new ArrayList<>();
        for (BenchmarkResult.TracePoint point : result.trace()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("size", new int[] {point.width(), point.height()});
            entry.put("ms", point.medianMs());
            entry.put("spread", point.spread());
            entry.put("rays", point.rays());
            traced.add(entry);
        }
        out.put("trace", traced);
        out.put("traceGigaRays", result.traceGigaRays());
        return out;
    }

    private static String fixed(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String fixed(double[] values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            out.append(i == 0 ? "" : ", ").append(fixed(values[i]));
        }
        return out.append(']').toString();
    }

    /** Collects the frame's interval and, every two seconds, gives the watch a window. */
    private void watchFrame(long now) {
        if (lastFrameNanos != 0 && intervalCount < intervals.length) {
            intervals[intervalCount++] = now - lastFrameNanos;
        }
        lastFrameNanos = now;
        if (windowStart == 0) {
            windowStart = now;
        }
        if (now - windowStart < WINDOW_NANOS || intervalCount == 0) {
            return;
        }
        long[] sorted = Arrays.copyOf(intervals, intervalCount);
        Arrays.sort(sorted);
        double onePercentLow = 1e9 / sorted[(intervalCount - 1) * 99 / 100];
        intervalCount = 0;
        windowStart = now;
        long evictions = memory.read().evictions();
        if (evictions >= 0 && watch.window(evictions, onePercentLow) && memoryTier != null && memoryTier != Tier.LOW) {
            Tier lowered = memoryTier.down();
            Quadrature.LOGGER.warn("Tiers: the driver keeps moving memory off the card ({} evictions since the system started) "
                + "while the 1% low fell to {} frames a second from {}: the memory tier goes down a step, to {}.", evictions,
                fixed(onePercentLow), fixed(watch.baseline()), lowered);
            adopt(speedTier, lowered, "the eviction watch");
        }
    }

    // A run: its steps, one a frame, and the queries they were timed by.

    private enum Kind { BANDWIDTH, TERRAIN, LIGHT, TRACE }

    /** One frame's work: a probe at one setting, kept or a warm-up. */
    private static final class Step {
        final Kind kind;
        final int setting;
        final boolean kept;
        int start;
        int end;
        int primitives;
        int samples;
        boolean issued;
        long rays;

        Step(Kind kind, int setting, boolean kept) {
            this.kind = kind;
            this.setting = setting;
            this.kept = kept;
        }
    }

    /** The most frames the settling load may run before the probes begin regardless. */
    private static final int MOST_SETTLE_FRAMES = 300;
    /**
     * Untimed passes of the arithmetic load in each frame of settling, and after each probe repetition: one
     * probe a frame leaves the GPU idle for most of it, and an idle GPU drops its clocks.
     */
    private static final int FILLER = 2;

    private final class Run {
        final int[][] sizes;
        final List<Step> steps = new ArrayList<>();
        final BandwidthKernel kernel = new BandwidthKernel();
        final int[] framebuffers;
        final int[] textures;
        int next;
        boolean lightRefused;
        boolean traceRefused;
        /** Whether the settling load still runs, ahead of every step. */
        boolean settling = true;
        final ClockSettle settle = new ClockSettle();
        final ClockSettle memorySettle = new ClockSettle();
        final List<int[]> spins = new ArrayList<>();
        int spinsRead;

        Run(int[][] sizes) {
            this.sizes = sizes;
            framebuffers = new int[sizes.length];
            textures = new int[sizes.length * 2];
            for (int round = 0; round < TierBenchmark.WARM_UP + TierBenchmark.REPETITIONS; round++) {
                boolean kept = round >= TierBenchmark.WARM_UP;
                steps.add(new Step(Kind.BANDWIDTH, 0, kept));
                steps.add(new Step(Kind.BANDWIDTH, 1, kept));
            }
            for (int round = 0; round < TierBenchmark.WARM_UP + TierBenchmark.REPETITIONS; round++) {
                // Each round in another order, so that what comes every few frames falls on another size each time.
                for (int i = 0; i < sizes.length; i++) {
                    steps.add(new Step(Kind.TERRAIN, (i + round) % sizes.length, round >= TierBenchmark.WARM_UP));
                }
            }
            for (int round = 0; round < TierBenchmark.WARM_UP + TierBenchmark.LIGHT_REPETITIONS; round++) {
                steps.add(new Step(Kind.LIGHT, 0, round >= TierBenchmark.WARM_UP));
            }
            if (trace.ready()) {
                // At the window's size: the trace's rays need not follow the render resolution.
                for (int round = 0; round < TierBenchmark.WARM_UP + TierBenchmark.REPETITIONS; round++) {
                    steps.add(new Step(Kind.TRACE, -1, round >= TierBenchmark.WARM_UP));
                }
            }
            long targetBytes = 0;
            for (int[] size : sizes) {
                targetBytes += (long) size[0] * size[1] * 8;
            }
            // Said before it is taken, so that a run that fails for memory names what it asked for.
            Quadrature.LOGGER.info("Tier benchmark: taking {} MiB of GPU memory for the bandwidth kernel's buffers and {} MiB "
                + "for the probe's targets, for the run only.", (TierBenchmark.SMALL_BYTES + TierBenchmark.LARGE_BYTES) >> 20,
                targetBytes >> 20);
            kernel.create(TierBenchmark.SMALL_BYTES, TierBenchmark.LARGE_BYTES);
            for (int i = 0; i < sizes.length; i++) {
                int color = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
                GL45.glTextureStorage2D(color, 1, GL11.GL_RGBA8, sizes[i][0], sizes[i][1]);
                int depth = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
                GL45.glTextureStorage2D(depth, 1, GL30.GL_DEPTH_COMPONENT32F, sizes[i][0], sizes[i][1]);
                framebuffers[i] = GL45.glCreateFramebuffers();
                GL45.glNamedFramebufferTexture(framebuffers[i], GL30.GL_COLOR_ATTACHMENT0, color, 0);
                GL45.glNamedFramebufferTexture(framebuffers[i], GL30.GL_DEPTH_ATTACHMENT, depth, 0);
                textures[i * 2] = color;
                textures[i * 2 + 1] = depth;
                int status = GL45.glCheckNamedFramebufferStatus(framebuffers[i], GL30.GL_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Tier benchmark target incomplete: 0x" + Integer.toHexString(status));
                }
            }
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                throw new IllegalStateException(String.format(Locale.ROOT, "GL error 0x%04X making the benchmark's buffers "
                    + "and targets", error));
            }
            Quadrature.LOGGER.info("Tier benchmark: a run of {} frames begins, at {}{}.", steps.size(), describe(sizes),
                trace.ready() ? ", with the trace " + trace.name() : ", without a trace probe");
        }

        /**
         * One frame of the settling load ({@link ClockSettle}): issues it, takes the times the driver has ready, in
         * order, and ends the phase once they agree or after {@value #MOST_SETTLE_FRAMES} frames.
         */
        void settleFrame() {
            int[] stamps = new int[3];
            stamps[0] = stamp();
            kernel.spin();
            stamps[1] = stamp();
            kernel.read(1, 1);
            stamps[2] = stamp();
            // Untimed, so that the load is heavy enough for the GPU to take its top state, memory clock included.
            for (int i = 0; i < FILLER; i++) {
                kernel.spin();
            }
            spins.add(stamps);
            while (spinsRead < spins.size()) {
                int[] read = spins.get(spinsRead);
                if (!available(read[0]) || !available(read[1]) || !available(read[2])) {
                    break;
                }
                long first = GL33.glGetQueryObjectui64(read[0], GL15.GL_QUERY_RESULT);
                long second = GL33.glGetQueryObjectui64(read[1], GL15.GL_QUERY_RESULT);
                settle.time((second - first) / 1e6);
                memorySettle.time((GL33.glGetQueryObjectui64(read[2], GL15.GL_QUERY_RESULT) - second) / 1e6);
                spinsRead++;
            }
            boolean settled = settle.settled() && memorySettle.settled();
            if (settled || spins.size() >= MOST_SETTLE_FRAMES) {
                settling = false;
                Quadrature.LOGGER.info("Tier benchmark: the clocks {} after {} frames of the settling load; its last times "
                    + "{} ms for the arithmetic and {} ms for a pass over the large set, at the median.",
                    settled ? "settled" : "did not settle", spins.size(), fixed(settle.latestMedian()),
                    fixed(memorySettle.latestMedian()));
            }
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                throw new IllegalStateException(String.format(Locale.ROOT, "GL error 0x%04X after the settling load", error));
            }
        }

        /**
         * Issues one step. Times are taken by a pair of timestamps, never a time-elapsed query: the frame's own
         * stage timer holds one open around opaque terrain, where this runs, and only one may be open.
         */
        void issue(TerrainHook.Probes probes, Step step) {
            for (int error = GL11.glGetError(), drained = 0; error != GL11.GL_NO_ERROR && drained < 16;
                 error = GL11.glGetError(), drained++) {
                // Raised before this step by something else: said here, since taking it hides it from the game's check.
                Quadrature.LOGGER.warn(String.format(Locale.ROOT, "Tier benchmark: GL error 0x%04X was pending before a "
                    + "%s step; it is not the benchmark's.", error, step.kind));
            }
            switch (step.kind) {
                case BANDWIDTH -> {
                    int passes = (int) Math.max(1, TierBenchmark.BYTES_PER_QUERY / kernel.bytes(step.setting));
                    step.start = stamp();
                    kernel.read(step.setting, passes);
                    step.end = stamp();
                    step.issued = true;
                }
                case TERRAIN -> terrain(probes, step);
                case LIGHT -> {
                    if (lightRefused) {
                        return;
                    }
                    step.primitives = GL15.glGenQueries();
                    step.samples = GL15.glGenQueries();
                    boolean drawn = probes.lightAgain(() -> {
                        GL15.glBeginQuery(GL30.GL_PRIMITIVES_GENERATED, step.primitives);
                        GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, step.samples);
                        step.start = stamp();
                    }, () -> {
                        step.end = stamp();
                        GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
                        GL15.glEndQuery(GL30.GL_PRIMITIVES_GENERATED);
                    });
                    step.issued = drawn;
                    if (!drawn) {
                        lightRefused = true;
                        Quadrature.LOGGER.info("Tier benchmark: the light view's box is not drawn (the light's depth is off, or a box "
                            + "is being drawn over frames); the run goes on without it.");
                    }
                }
                case TRACE -> {
                    if (traceRefused || !trace.ready()) {
                        traceRefused = true;
                        return;
                    }
                    Minecraft minecraft = Minecraft.getMinecraft();
                    step.start = stamp();
                    step.rays = trace.dispatch(minecraft.displayWidth, minecraft.displayHeight);
                    step.end = stamp();
                    if (step.rays == 0) {
                        traceRefused = true;
                        Quadrature.LOGGER.info("Tier benchmark: the trace {} traced nothing; the run goes on without it.",
                            trace.name());
                    }
                    step.issued = true;
                }
            }
            // After the step's own stamps: keeps the GPU loaded, and the next step's first stamp waits for it.
            for (int i = 0; i < FILLER; i++) {
                kernel.spin();
            }
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                throw new IllegalStateException(String.format(Locale.ROOT, "GL error 0x%04X after a %s step of the benchmark",
                    error, step.kind));
            }
        }

        /** A timestamp query, issued now. */
        private int stamp() {
            int query = GL15.glGenQueries();
            GL33.glQueryCounter(query, GL33.GL_TIMESTAMP);
            return query;
        }

        /**
         * The three opaque layers again, into the probe's own target of the step's size, each under the state
         * the game draws it with: the solid layer without the alpha test, both cutout layers with it, the last
         * without the block texture's mipmaps. What the game holds after its cutout layer is put back.
         */
        private void terrain(TerrainHook.Probes probes, Step step) {
            int framebuffer = framebuffers[step.setting];
            int width = sizes[step.setting][0];
            int height = sizes[step.setting][1];
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GlStateManager.depthMask(true);
            GL45.glClearNamedFramebufferfv(framebuffer, GL11.GL_COLOR, 0, new float[] {0, 0, 0, 1});
            GL45.glClearNamedFramebufferfv(framebuffer, GL11.GL_DEPTH, 0, new float[] {DepthCalls.active() ? 0f : 1f});
            // The game sets the block texture's filtering on the texture bound, which is the block texture here.
            ITextureObject blocks = Minecraft.getMinecraft().getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.bindTexture(blocks.getGlTextureId());
            step.primitives = GL15.glGenQueries();
            step.samples = GL15.glGenQueries();
            GL15.glBeginQuery(GL30.GL_PRIMITIVES_GENERATED, step.primitives);
            GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, step.samples);
            step.start = stamp();
            boolean drawn = true;
            blocks.restoreLastBlurMipmap();
            GlStateManager.disableAlpha();
            drawn &= layer(probes, BlockRenderLayer.SOLID, framebuffer, width, height);
            GlStateManager.enableAlpha();
            drawn &= layer(probes, BlockRenderLayer.CUTOUT_MIPPED, framebuffer, width, height);
            blocks.setBlurMipmap(false, false);
            drawn &= layer(probes, BlockRenderLayer.CUTOUT, framebuffer, width, height);
            step.end = stamp();
            GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            GL15.glEndQuery(GL30.GL_PRIMITIVES_GENERATED);
            step.issued = drawn;
            if (!drawn) {
                throw new IllegalStateException("near terrain's draws cannot be issued again (drawn by a call a cube)");
            }
        }

        private boolean layer(TerrainHook.Probes probes, BlockRenderLayer layer, int framebuffer, int width, int height) {
            // The terrain program's end binds the game's framebuffer back: each layer binds the probe's again.
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glViewport(0, 0, width, height);
            return probes.opaqueAgain(layer);
        }

        /** Whether every issued query's result is ready. */
        boolean ready() {
            for (Step step : steps) {
                if (step.issued && !(available(step.start) && available(step.end) && available(step.primitives)
                    && available(step.samples))) {
                    return false;
                }
            }
            return true;
        }

        private boolean available(int query) {
            return query == 0 || GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) != 0;
        }

        BenchmarkRun samples(int outputWidth, int outputHeight) {
            BenchmarkRun run = new BenchmarkRun(outputWidth, outputHeight);
            for (Step step : steps) {
                if (!step.issued || !step.kept) {
                    continue;
                }
                long nanos = GL33.glGetQueryObjectui64(step.end, GL15.GL_QUERY_RESULT)
                    - GL33.glGetQueryObjectui64(step.start, GL15.GL_QUERY_RESULT);
                switch (step.kind) {
                    case BANDWIDTH -> {
                        long passes = Math.max(1, TierBenchmark.BYTES_PER_QUERY / kernel.bytes(step.setting));
                        run.add(new BenchmarkRun.Bandwidth(kernel.bytes(step.setting), passes * kernel.bytes(step.setting), nanos));
                    }
                    case TERRAIN -> run.add(new BenchmarkRun.Terrain(sizes[step.setting][0], sizes[step.setting][1], nanos,
                        GL33.glGetQueryObjectui64(step.primitives, GL15.GL_QUERY_RESULT),
                        GL33.glGetQueryObjectui64(step.samples, GL15.GL_QUERY_RESULT)));
                    case LIGHT -> run.add(new BenchmarkRun.Light(nanos,
                        GL33.glGetQueryObjectui64(step.primitives, GL15.GL_QUERY_RESULT),
                        GL33.glGetQueryObjectui64(step.samples, GL15.GL_QUERY_RESULT)));
                    case TRACE -> {
                        if (step.rays > 0) {
                            Minecraft minecraft = Minecraft.getMinecraft();
                            run.add(new BenchmarkRun.Trace(minecraft.displayWidth, minecraft.displayHeight, nanos, step.rays));
                        }
                    }
                }
            }
            return run;
        }

        void delete() {
            for (int[] stamps : spins) {
                for (int query : stamps) {
                    GL15.glDeleteQueries(query);
                }
            }
            for (Step step : steps) {
                for (int query : new int[] {step.start, step.end, step.primitives, step.samples}) {
                    if (query != 0) {
                        GL15.glDeleteQueries(query);
                    }
                }
            }
            kernel.delete();
            for (int framebuffer : framebuffers) {
                if (framebuffer != 0) {
                    GL30.glDeleteFramebuffers(framebuffer);
                }
            }
            for (int texture : textures) {
                if (texture != 0) {
                    GL11.glDeleteTextures(texture);
                }
            }
        }
    }

    private static String describe(int[][] sizes) {
        StringBuilder out = new StringBuilder();
        for (int[] size : sizes) {
            out.append(out.length() == 0 ? "" : ", ").append(size[0]).append(" by ").append(size[1]);
        }
        return out.toString();
    }
}
