package cc.orthrus.quadrature.core.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

final class FrameTimelineTest {

    private static CompiledGraph graph() {
        return new PassGraph().importResource("frame")
            .add(StageDefinition.hosted("sky", "frame"))
            .add(StageDefinition.hosted("terrain", "frame"))
            .add(StageDefinition.hosted("entities", "frame"))
            .compile();
    }

    /** A clock the test advances by hand. */
    private static final class Clock {
        long now;
    }

    /** Records what the timeline asks of the GPU timer and answers each stage with a fixed time. */
    private static final class RecordingGpuTimer implements GpuTimer {
        final List<String> calls = new ArrayList<>();

        @Override
        public void beginFrame(Sink sink) {
            calls.add("frame");
            sink.gpuNanos(1, 700);
        }

        @Override
        public void begin(int stageIndex) {
            calls.add("begin " + stageIndex);
        }

        @Override
        public void end() {
            calls.add("end");
        }
    }

    @Test
    void eachStageIsTimedFromItsEntryToTheNext() {
        Clock clock = new Clock();
        FrameTimeline timeline = new FrameTimeline(graph(), () -> clock.now, GpuTimer.NONE);
        timeline.beginFrame();
        timeline.enter("sky");
        clock.now = 100;
        timeline.enter("terrain");
        clock.now = 450;
        timeline.enter("entities");
        clock.now = 500;
        timeline.endFrame();

        assertEquals(100, timeline.statistics().cpu(0).mean());
        assertEquals(350, timeline.statistics().cpu(1).mean());
        assertEquals(50, timeline.statistics().cpu(2).mean());
        assertEquals(1, timeline.frames());
        assertEquals(0, timeline.outOfOrder());
    }

    @Test
    void theGpuTimerBracketsEveryStageAndItsResultsAreRecorded() {
        RecordingGpuTimer gpu = new RecordingGpuTimer();
        FrameTimeline timeline = new FrameTimeline(graph(), () -> 0, gpu);
        timeline.beginFrame();
        timeline.enter("sky");
        timeline.enter("terrain");
        timeline.endFrame();

        assertEquals(List.of("frame", "begin 0", "end", "begin 1", "end"), gpu.calls);
        assertEquals(700, timeline.statistics().gpu(1).mean());
        assertEquals(0, timeline.statistics().gpu(0).samples());
    }

    @Test
    void aStageEnteredOutOfOrderIsTimedAndCounted() {
        Clock clock = new Clock();
        FrameTimeline timeline = new FrameTimeline(graph(), () -> clock.now, GpuTimer.NONE);
        timeline.beginFrame();
        timeline.enter("entities");
        clock.now = 10;
        timeline.enter("sky");
        clock.now = 30;
        timeline.endFrame();

        assertEquals(1, timeline.outOfOrder());
        assertEquals(20, timeline.statistics().cpu(0).mean());
    }

    @Test
    void anUnknownStageEndsTheCurrentOneAndIsCounted() {
        Clock clock = new Clock();
        FrameTimeline timeline = new FrameTimeline(graph(), () -> clock.now, GpuTimer.NONE);
        timeline.beginFrame();
        timeline.enter("sky");
        clock.now = 40;
        timeline.enter("not a stage");
        clock.now = 90;
        timeline.endFrame();

        assertEquals(1, timeline.unknownStages());
        assertEquals(40, timeline.statistics().cpu(0).mean());
    }

    @Test
    void boundariesOutsideAFrameAreIgnoredAndCounted() {
        FrameTimeline timeline = new FrameTimeline(graph(), () -> 0, GpuTimer.NONE);
        timeline.enter("sky");
        timeline.endFrame();
        assertEquals(2, timeline.outsideFrame());
        assertEquals(0, timeline.frames());
    }

    @Test
    void aFrameThatNeverEndedIsClosedByTheNext() {
        Clock clock = new Clock();
        FrameTimeline timeline = new FrameTimeline(graph(), () -> clock.now, GpuTimer.NONE);
        timeline.beginFrame();
        timeline.enter("sky");
        clock.now = 25;
        timeline.beginFrame();
        assertEquals(1, timeline.frames());
        assertEquals(25, timeline.statistics().cpu(0).mean());
    }

    @Test
    void statisticsKeepOnlyTheMostRecentWindow() {
        StageStatistics statistics = new StageStatistics(1);
        for (int i = 0; i < StageStatistics.WINDOW; i++) {
            statistics.cpuNanos(0, 1_000_000);
        }
        for (int i = 0; i < StageStatistics.WINDOW; i++) {
            statistics.cpuNanos(0, 10);
        }
        StageStatistics.Summary summary = statistics.cpu(0);
        assertEquals(StageStatistics.WINDOW, summary.samples());
        assertEquals(10, summary.max());
    }

    @Test
    void summaryReportsMedianAndPercentile() {
        StageStatistics statistics = new StageStatistics(1);
        for (int i = 1; i <= 100; i++) {
            statistics.cpuNanos(0, i);
        }
        StageStatistics.Summary summary = statistics.cpu(0);
        assertEquals(100, summary.samples());
        assertEquals(50, summary.median());
        assertEquals(95, summary.percentile95());
        assertEquals(100, summary.max());
    }
}
