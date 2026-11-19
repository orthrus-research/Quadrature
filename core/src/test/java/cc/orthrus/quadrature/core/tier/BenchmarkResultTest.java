package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

final class BenchmarkResultTest {

    private static final double EPSILON = 1e-9;

    @Test
    void bandwidthIsTheMedianOfEachWorkingSetsPasses() {
        BenchmarkRun run = new BenchmarkRun(1280, 720);
        long bytes = TierBenchmark.BYTES_PER_QUERY;
        // 512 MiB in 2, 1 and 4 ms: the median is the pass that took 2 ms.
        for (long nanos : new long[] {2_000_000, 1_000_000, 4_000_000}) {
            run.add(new BenchmarkRun.Bandwidth(TierBenchmark.SMALL_BYTES, bytes, nanos));
        }
        run.add(new BenchmarkRun.Bandwidth(TierBenchmark.LARGE_BYTES, bytes, 2_000_000));
        BenchmarkResult result = BenchmarkResult.of(run);
        assertEquals(bytes / 2_000_000.0, result.smallGBs(), EPSILON);
        assertEquals(bytes / 2_000_000.0, result.largeGBs(), EPSILON);
        assertEquals((bytes / 1e6 - bytes / 4e6) / (bytes / 2e6), result.smallSpread(), EPSILON);
    }

    @Test
    void terrainIsReducedPerResolutionFewestPixelsFirstAndFitted() {
        BenchmarkRun run = new BenchmarkRun(2560, 1440);
        // 1 ms of geometry for 2,000,000 primitives, then 1 ms for every million fragments.
        int[][] sizes = {{2560, 1440}, {1280, 720}, {1920, 1080}};
        for (int[] size : sizes) {
            long fragments = (long) size[0] * size[1];
            for (int rep = 0; rep < 3; rep++) {
                long nanos = Math.round((1.0 + fragments / 1e6) * 1e6) + rep * 1000L;
                run.add(new BenchmarkRun.Terrain(size[0], size[1], nanos, 2_000_000, fragments));
            }
        }
        BenchmarkResult result = BenchmarkResult.of(run);
        List<BenchmarkResult.TerrainPoint> points = result.terrain();
        assertEquals(3, points.size());
        assertEquals(1280, points.get(0).width());
        assertEquals(2560, points.get(2).width());
        assertEquals(2_000_000, points.get(0).primitives());
        TerrainFit fit = result.terrainFit();
        // The median repetition at each resolution is the one 1 microsecond over.
        assertEquals(1.001, fit.interceptMs(), 1e-6);
        assertEquals(1.0, fit.msPerMegaFragment(), 1e-6);
        assertEquals(0.5005, fit.nsPerPrimitive(), 1e-6);
    }

    @Test
    void theLightIsTimedPerPrimitiveAndAnAbsentProbeIsNotANumber() {
        BenchmarkRun run = new BenchmarkRun(1280, 720);
        run.add(new BenchmarkRun.Light(2_000_000, 4_000_000, 1_000_000));
        run.add(new BenchmarkRun.Light(3_000_000, 4_000_000, 3_000_000));
        run.add(new BenchmarkRun.Light(2_500_000, 4_000_000, 2_000_000));
        BenchmarkResult result = BenchmarkResult.of(run);
        assertEquals(2.5, result.lightMs(), EPSILON);
        assertEquals(0.625, result.lightNsPerPrimitive(), EPSILON);
        assertEquals(2_000_000, result.lightFragments(), EPSILON);
        assertTrue(Double.isNaN(result.traceGigaRays()));
        assertTrue(Double.isNaN(result.smallGBs()));
        assertNull(result.terrainFit());
        assertTrue(result.trace().isEmpty());
    }

    @Test
    void aViewWhoseCubesHaveNotArrivedDoesNotMeasureTheCard() {
        // As at radius 20 while a place arrived: a few thousand primitives, the time the probe's fixed part alone.
        BenchmarkRun empty = new BenchmarkRun(1280, 720);
        BenchmarkRun drawn = new BenchmarkRun(1280, 720);
        for (int[] size : new int[][] {{1280, 720}, {2560, 1440}}) {
            empty.add(new BenchmarkRun.Terrain(size[0], size[1], 20_000, 4_922, 50_000));
            drawn.add(new BenchmarkRun.Terrain(size[0], size[1], 1_300_000, 2_050_184, 2_000_000));
        }
        assertTrue(!BenchmarkResult.of(empty).drewEnough());
        assertTrue(BenchmarkResult.of(drawn).drewEnough());
        assertTrue(!BenchmarkResult.of(new BenchmarkRun(1280, 720)).drewEnough());
    }

    @Test
    void theTraceIsRaysANanosecond() {
        BenchmarkRun run = new BenchmarkRun(1280, 720);
        run.add(new BenchmarkRun.Trace(1280, 720, 1_000_000, 921_600));
        run.add(new BenchmarkRun.Trace(1280, 720, 2_000_000, 921_600));
        run.add(new BenchmarkRun.Trace(1280, 720, 1_500_000, 921_600));
        BenchmarkResult result = BenchmarkResult.of(run);
        assertEquals(921_600 / 1_500_000.0, result.traceGigaRays(), EPSILON);
        assertEquals(1.5, result.trace().getFirst().medianMs(), EPSILON);
        assertTrue(result.describe().stream().anyMatch(line -> line.startsWith("indirect trace at 1280 by 720")));
    }
}
