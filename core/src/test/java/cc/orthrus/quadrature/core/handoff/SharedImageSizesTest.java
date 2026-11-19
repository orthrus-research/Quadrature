package cc.orthrus.quadrature.core.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SharedImageSizesTest {

    private static final double TWO_THIRDS = 0.6666667;

    @Test
    void theMeasuredOutputsAtTwoThirds() {
        // 1920x1080 reads from 1280x720; the game's 24-bit depth takes 4 bytes a texel.
        SharedImageSizes hd = SharedImageSizes.of(1920, 1080, TWO_THIRDS, 4);
        assertEquals(new RenderSize(1280, 720), hd.render());
        assertEquals(1280L * 720 * 8, hd.color());
        assertEquals(1280L * 720 * 4, hd.depth());
        assertEquals(1280L * 720 * 4, hd.motion());
        assertEquals(1920L * 1080 * 8, hd.output());
        assertEquals(31_334_400L, hd.total());
        assertNull(hd.refusal());
        // 2560x1440 reads from 1707x960.
        SharedImageSizes qhd = SharedImageSizes.of(2560, 1440, TWO_THIRDS, 4);
        assertEquals(new RenderSize(1707, 960), qhd.render());
        assertEquals(1707L * 960 * 8, qhd.color());
        assertEquals(2560L * 1440 * 8, qhd.output());
        assertEquals(55_710_720L, qhd.total());
        assertNull(qhd.refusal());
        assertTrue(qhd.toString().contains("53.13 MiB"), qhd.toString());
    }

    @Test
    void theLargestSideIsCountedInLongAndRefusedInAll() {
        // 16384 x 16384 x 8 bytes is 2 GiB: past an int, and past the bound.
        SharedImageSizes huge = SharedImageSizes.of(16384, 16384, 1.0, 4);
        assertEquals(2_147_483_648L, huge.output());
        assertNotNull(huge.refusal());
        // A 4K output at full scale is within it.
        assertNull(SharedImageSizes.of(3840, 2160, 1.0, 8).refusal());
    }

    @Test
    void sidesOutsideTheRangeAreRefusedBeforeAnythingIsComputed() {
        assertThrows(IllegalArgumentException.class, () -> SharedImageSizes.of(0, 1080, TWO_THIRDS, 4));
        assertThrows(IllegalArgumentException.class, () -> SharedImageSizes.of(1920, -1, TWO_THIRDS, 4));
        assertThrows(IllegalArgumentException.class, () -> SharedImageSizes.of(16385, 1080, TWO_THIRDS, 4));
        assertThrows(IllegalArgumentException.class, () -> SharedImageSizes.of(1920, 1080, TWO_THIRDS, 0));
    }

    @Test
    void anAllocationFarBeyondItsTexelsIsRefused() {
        long texels = 1920L * 1080 * 8;
        assertNull(SharedImageSizes.allocationRefusal("output", texels, texels));
        assertNull(SharedImageSizes.allocationRefusal("output", 2 * texels, texels));
        // A small image's alignment is allowed for.
        assertNull(SharedImageSizes.allocationRefusal("depth", 1L << 20, 1024));
        assertNotNull(SharedImageSizes.allocationRefusal("output", 4L << 30, texels));
        assertNotNull(SharedImageSizes.allocationRefusal("output", 5 * texels + (1L << 21), texels));
        assertNotNull(SharedImageSizes.allocationRefusal("output", 0, texels));
    }
}
