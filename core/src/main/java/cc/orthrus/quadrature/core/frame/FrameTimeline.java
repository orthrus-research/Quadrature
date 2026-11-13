package cc.orthrus.quadrature.core.frame;

import java.util.function.LongSupplier;

/**
 * Follows one frame through the compiled graph's stages as their boundaries are reported, and times
 * each stage on the CPU and the GPU.
 *
 * <p>Hosted stages are run by the game, not by Quadrature, so the timeline is told where each begins
 * instead of running them. It does not assume the reports are well formed: another mod may reorder or
 * skip the game's code. A stage reported out of the graph's order is still timed, and counted.
 */
public final class FrameTimeline {

    private static final int NONE = -1;

    private final CompiledGraph graph;
    private final LongSupplier nanoClock;
    private final GpuTimer gpuTimer;
    private final StageStatistics statistics;
    private final GpuTimer.Sink gpuSink;

    private boolean inFrame;
    private int current = NONE;
    private int highestEntered = NONE;
    private long currentStartNanos;
    private long frames;
    private long outOfOrder;
    private long unknownStages;
    private long outsideFrame;

    public FrameTimeline(CompiledGraph graph, LongSupplier nanoClock, GpuTimer gpuTimer) {
        this.graph = graph;
        this.nanoClock = nanoClock;
        this.gpuTimer = gpuTimer;
        this.statistics = new StageStatistics(graph.size());
        this.gpuSink = statistics::gpuNanos;
    }

    public void beginFrame() {
        if (inFrame) {
            // The previous frame never reported its end: close it so its last stage is not left open.
            endFrame();
        }
        inFrame = true;
        highestEntered = NONE;
        gpuTimer.beginFrame(gpuSink);
    }

    /** The named stage begins now, and the stage before it, if any, ends. */
    public void enter(String stageName) {
        if (!inFrame) {
            outsideFrame++;
            return;
        }
        closeCurrent();
        int index = graph.indexOf(stageName);
        if (index < 0) {
            unknownStages++;
            return;
        }
        if (index < highestEntered) {
            outOfOrder++;
        }
        highestEntered = Math.max(highestEntered, index);
        current = index;
        currentStartNanos = nanoClock.getAsLong();
        gpuTimer.begin(index);
    }

    public void endFrame() {
        if (!inFrame) {
            outsideFrame++;
            return;
        }
        closeCurrent();
        inFrame = false;
        frames++;
    }

    private void closeCurrent() {
        if (current != NONE) {
            gpuTimer.end();
            statistics.cpuNanos(current, nanoClock.getAsLong() - currentStartNanos);
            current = NONE;
        }
    }

    public CompiledGraph graph() {
        return graph;
    }

    public StageStatistics statistics() {
        return statistics;
    }

    public long frames() {
        return frames;
    }

    /** Stages entered after a stage that the graph orders later. */
    public long outOfOrder() {
        return outOfOrder;
    }

    /** Stage names reported that the graph does not contain. */
    public long unknownStages() {
        return unknownStages;
    }

    /** Boundaries reported while no frame was open. */
    public long outsideFrame() {
        return outsideFrame;
    }
}
