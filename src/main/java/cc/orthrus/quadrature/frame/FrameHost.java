package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.frame.CompiledGraph;
import cc.orthrus.quadrature.core.frame.FrameTimeline;
import cc.orthrus.quadrature.core.frame.StageStatistics;

import java.util.HashMap;
import java.util.Map;

/**
 * Receives the boundaries of the game's world pass and feeds them to the frame timeline. Everything
 * here runs on the client thread, called from the injections in the game's world renderer.
 */
public final class FrameHost {

    /** Logs a timing summary every few seconds when set; for development until the overlay exists. */
    private static final boolean LOG = Boolean.getBoolean("quadrature.frameLog");
    private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;

    private static FrameTimeline timeline;
    private static GlGpuTimer gpuTimer;
    private static final Map<String, Integer> seen = new HashMap<>();
    private static final StateAudit audit = StateAudit.ENABLED ? new StateAudit() : null;
    private static long lastLogNanos;

    private FrameHost() {
    }

    /** The world pass begins. */
    public static void beginPass() {
        if (timeline == null) {
            // Created here because the graphics context is certain to be current on this thread.
            CompiledGraph graph = BaselineGraph.compile();
            gpuTimer = new GlGpuTimer(graph.size());
            timeline = new FrameTimeline(graph, System::nanoTime, gpuTimer);
            lastLogNanos = System.nanoTime();
            Quadrature.LOGGER.info("Frame graph compiled: {} hosted stages.", graph.size());
        }
        // A stage handed over in a folder is checked once, before the frame's own work, where asked.
        StageCheck.runOnce();
        StageSelfTest.runOnce();
        seen.clear();
        timeline.beginFrame();
        TemporalScaler.beginPass();
        if (audit != null) {
            audit.beginPass();
        }
    }

    /** The game entered a profiler section inside the world pass. */
    public static void section(String name) {
        if (timeline == null) {
            return;
        }
        int occurrence = seen.merge(name, 1, Integer::sum) - 1;
        String stage = BaselineGraph.stageFor(name, occurrence);
        if (stage != null && timeline.graph().indexOf(stage) != currentStage) {
            currentStage = timeline.graph().indexOf(stage);
            if (Probe.ENABLED && stage.equals(Probe.BEFORE)) {
                // A stage of Quadrature's own, run between two hosted ones.
                if (audit != null) {
                    audit.boundary(Probe.STAGE);
                }
                timeline.enter(Probe.STAGE);
                Probe.run();
            }
            if (stage.equals(StageHost.BEFORE) && cc.orthrus.quadrature.terrain.TerrainHook.targetWritersDue()) {
                // What writes into the targets besides near terrain, before anything reads them. Each
                // writer is a stretch of its own to the state audit, so one that leaves state changed is named.
                timeline.enter(TARGET_WRITERS);
                cc.orthrus.quadrature.terrain.TerrainHook.runTargetWriters(place -> {
                    if (audit != null) {
                        audit.boundary(TARGET_WRITERS + " " + place);
                    }
                });
            }
            if (StageHost.REQUESTED && stage.equals(StageHost.BEFORE)) {
                // The shading stage handed over in a folder: it reads what opaque terrain just drew.
                if (audit != null) {
                    audit.boundary(StageHost.STAGE);
                }
                timeline.enter(StageHost.STAGE);
                StageHost.run();
                cc.orthrus.quadrature.terrain.TerrainHook.writeImagesDue();
            }
            if (TemporalScaler.REQUESTED && stage.equals(TemporalScaler.BEFORE) && TemporalScaler.due()) {
                // The picture at the output's size, made before the mods' last world drawing reads or draws on it.
                if (audit != null) {
                    audit.boundary(TemporalScaler.STAGE);
                }
                timeline.enter(TemporalScaler.STAGE);
            }
            if (TemporalScaler.REQUESTED) {
                TemporalScaler.boundary(stage);
            }
            if (audit != null) {
                audit.boundary(stage);
            }
            timeline.enter(stage);
        }
    }

    private static int currentStage = -1;

    /** The stage in which what writes into the targets for shading besides near terrain is called. */
    static final String TARGET_WRITERS = "target writers";

    /** The world pass ends. */
    public static void endPass() {
        if (timeline == null) {
            return;
        }
        TemporalScaler.endPass();
        if (audit != null) {
            audit.endPass();
        }
        timeline.endFrame();
        if (HandoffHost.REQUESTED) {
            // After the stages are timed, so the handoff's work is not charged to the last of them.
            HandoffHost.endOfWorld();
        }
        currentStage = -1;
        if (LOG && System.nanoTime() - lastLogNanos > LOG_INTERVAL_NANOS) {
            lastLogNanos = System.nanoTime();
            log();
        }
    }

    private static void log() {
        StringBuilder line = new StringBuilder("Stage times, median/95th in ms (CPU | GPU):");
        CompiledGraph graph = timeline.graph();
        long cpuTotal = 0;
        long gpuTotal = 0;
        for (int i = 0; i < graph.size(); i++) {
            StageStatistics.Summary cpu = timeline.statistics().cpu(i);
            StageStatistics.Summary gpu = timeline.statistics().gpu(i);
            if (cpu.samples() == 0) {
                continue;
            }
            cpuTotal += cpu.median();
            gpuTotal += gpu.median();
            line.append(String.format("%n  %-22s %6.3f/%6.3f | %6.3f/%6.3f", graph.stages().get(i).name(),
                cpu.median() / 1e6, cpu.percentile95() / 1e6, gpu.median() / 1e6, gpu.percentile95() / 1e6));
        }
        line.append(String.format("%n  %-22s %6.3f        | %6.3f", "sum of medians", cpuTotal / 1e6, gpuTotal / 1e6));
        line.append(String.format("%n  frames %d, out of order %d, unknown stages %d, outside a frame %d, "
                + "GPU results dropped %d", timeline.frames(), timeline.outOfOrder(), timeline.unknownStages(),
            timeline.outsideFrame(), gpuTimer.dropped()));
        String terrain = cc.orthrus.quadrature.terrain.TerrainHook.summary();
        if (terrain != null) {
            line.append(String.format("%n  %s", terrain));
        }
        String scaler = TemporalScaler.summary();
        if (scaler != null) {
            line.append(String.format("%n  %s", scaler));
        }
        Quadrature.LOGGER.info(line.toString());
    }
}
