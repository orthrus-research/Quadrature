package cc.orthrus.quadrature.core.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RenderSizeTest {

    @Test
    void twoThirdsOfTheMeasuredOutputs() {
        assertEquals(new RenderSize(1280, 720), RenderSize.of(1920, 1080, 2.0 / 3));
        assertEquals(new RenderSize(1707, 960), RenderSize.of(2560, 1440, 2.0 / 3));
        assertEquals(new RenderSize(960, 540), RenderSize.of(1920, 1080, 0.5));
        assertEquals("1920x1080", RenderSize.of(1920, 1080, 1.0).toString());
    }

    @Test
    void sidesStayBetweenTheSmallestAndTheOutput() {
        assertEquals(new RenderSize(32, 32), RenderSize.of(100, 40, 0.1));
        assertEquals(new RenderSize(20, 32), RenderSize.of(20, 400, 0.05));
    }

    @Test
    void badScalesAndSizesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RenderSize.of(1920, 1080, 0));
        assertThrows(IllegalArgumentException.class, () -> RenderSize.of(1920, 1080, 1.5));
        assertThrows(IllegalArgumentException.class, () -> RenderSize.of(1920, 1080, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> RenderSize.of(0, 1080, 0.5));
    }
}
