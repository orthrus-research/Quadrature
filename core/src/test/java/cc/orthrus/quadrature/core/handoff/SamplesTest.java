package cc.orthrus.quadrature.core.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SamplesTest {

    @Test
    void anEmptyListSummarizesToNothing() {
        assertEquals(Samples.Summary.EMPTY, new Samples().summary());
        assertEquals("no samples", new Samples().summary().millis());
    }

    @Test
    void percentilesAreTakenByNearestRank() {
        Samples samples = new Samples();
        // 1..1000, added backwards so the summary has to sort them; more than the first array holds.
        for (int value = 1000; value >= 1; value--) {
            samples.add(value);
        }
        Samples.Summary summary = samples.summary();
        assertEquals(1000, summary.count());
        assertEquals(500.5, summary.mean(), 1e-9);
        assertEquals(1, summary.min());
        assertEquals(500, summary.median());
        assertEquals(950, summary.p95());
        assertEquals(990, summary.p99());
        assertEquals(1000, summary.max());
    }

    @Test
    void oneValueIsEveryPercentile() {
        Samples samples = new Samples();
        samples.add(2_500_000);
        Samples.Summary summary = samples.summary();
        assertEquals(2_500_000, summary.median());
        assertEquals(2_500_000, summary.p99());
        assertEquals("mean 2.500, median 2.500, p95 2.500, p99 2.500, max 2.500 ms (n=1)", summary.millis());
        samples.clear();
        assertEquals(0, samples.count());
    }
}
