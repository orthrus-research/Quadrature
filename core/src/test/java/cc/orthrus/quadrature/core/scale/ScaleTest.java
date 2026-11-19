package cc.orthrus.quadrature.core.scale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.orthrus.quadrature.core.stage.FrameBlock;

import org.junit.jupiter.api.Test;

class ScaleTest {

    // The sizes.

    @Test
    void sidesRoundToWholePixels() {
        assertEquals(1715, RenderScale.side(2560, 0.67));
        assertEquals(965, RenderScale.side(1440, 0.67));
        assertEquals(1971, RenderScale.side(2560, 0.77));
        assertEquals(1109, RenderScale.side(1440, 0.77));
        assertEquals(2560, RenderScale.side(2560, 1));
        assertEquals(1, RenderScale.side(1, 0.5));
    }

    @Test
    void settingsOutsideTheRangeAreRefused() {
        assertEquals(1, RenderScale.parse(null));
        assertEquals(0.67, RenderScale.parse("0.67"));
        assertEquals(1, RenderScale.parse("0.3"));
        assertEquals(1, RenderScale.parse("1.5"));
        assertEquals(1, RenderScale.parse("most"));
        assertTrue(RenderScale.refused("0.3"));
        assertTrue(RenderScale.refused("most"));
        assertFalse(RenderScale.refused("0.77"));
        assertFalse(RenderScale.refused(null));
    }

    @Test
    void anOutputPixelMapsToTheSameFractionOfTheDrawnRectangle() {
        // The first and last output pixel centers sit as far inside the rectangle as inside the output.
        double first = RenderScale.source(0, 2560, 1715);
        double last = RenderScale.source(2559, 2560, 1715);
        assertEquals((0.5 / 2560) * 1715 - 0.5, first, 1e-12);
        assertEquals(1715 - 1 - first, last, 1e-9);
        // At the same size an output pixel is its own texel.
        assertEquals(37, RenderScale.source(37, 640, 640), 1e-12);
    }

    // The jitter.

    @Test
    void haltonIsTheRadicalInverse() {
        assertEquals(0.5, Jitter.halton(1, 2), 1e-15);
        assertEquals(0.25, Jitter.halton(2, 2), 1e-15);
        assertEquals(0.75, Jitter.halton(3, 2), 1e-15);
        assertEquals(1.0 / 3, Jitter.halton(1, 3), 1e-15);
        assertEquals(2.0 / 3, Jitter.halton(2, 3), 1e-15);
        assertEquals(1.0 / 9, Jitter.halton(3, 3), 1e-15);
    }

    @Test
    void phasesKeepEightOffsetsForEveryOutputPixel() {
        // DLSS's guide gives 32 at one half and 18 at two thirds (its 1.5, our 0.6666667 and 0.67).
        assertEquals(32, Jitter.phasesFor(0.5));
        assertEquals(18, Jitter.phasesFor(0.6666667));
        assertEquals(18, Jitter.phasesFor(0.67));
        assertEquals(14, Jitter.phasesFor(0.77));
        assertEquals(8, Jitter.phasesFor(1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Jitter.phasesFor(0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> Jitter.phasesFor(1.5));
    }

    @Test
    void offsetsRepeatEveryCycleAndStayInsideThePixel() {
        int phases = 8;
        double sumX = 0;
        double sumY = 0;
        for (int frame = 0; frame < phases; frame++) {
            double[] offset = Jitter.offset(frame, phases);
            assertArrayEquals(offset, Jitter.offset(frame + phases, phases), 0);
            assertArrayEquals(offset, Jitter.offset(frame - 3L * phases, phases), 0);
            assertTrue(Math.abs(offset[0]) < 0.5 && Math.abs(offset[1]) < 0.5);
            sumX += offset[0];
            sumY += offset[1];
        }
        // The first eight Halton points in base 2 average to 57/128, just left of the pixel's center.
        assertEquals(57.0 / 128 - 0.5, sumX / phases, 1e-12);
        assertEquals(0, sumY / phases, 0.05);
    }

    @Test
    void theOffsetMatrixMovesThePictureAndRemovingItRestoresTheProjection() {
        double[] projection = perspective(70, 16.0 / 9, 0.05, 512);
        float[] offset = Jitter.matrix(0.25, -0.4, 1715, 965);
        double[] jittered = FrameBlock.multiply(FrameBlock.doubles(offset), projection);
        // A point anywhere in depth moves by the same fraction of the picture.
        for (double z : new double[] {-1, -10, -300}) {
            double[] plain = FrameBlock.apply(projection, 1, 2, z, 1);
            double[] moved = FrameBlock.apply(jittered, 1, 2, z, 1);
            assertEquals(2 * 0.25 / 1715, moved[0] / moved[3] - plain[0] / plain[3], 1e-6);
            assertEquals(2 * -0.4 / 965, moved[1] / moved[3] - plain[1] / plain[3], 1e-6);
        }
        Jitter.remove(jittered, offset[12], offset[13]);
        assertArrayEquals(projection, jittered, 1e-6);
    }

    // The reprojection.

    @Test
    void aStillViewReprojectsEveryPixelOntoItself() {
        double[] projection = perspective(70, 16.0 / 9, 0.05, 512);
        double[] view = rotationY(30);
        Reprojection.Frame a = new Reprojection.Frame(projection, view, 100.25, 70, -2000.5, 0);
        Reprojection.Frame b = new Reprojection.Frame(projection.clone(), view.clone(), 100.25, 70, -2000.5, 0);
        double[] matrix = Reprojection.currentToPrevious(a, b);
        assertNotNull(matrix);
        for (double depth : new double[] {0.1, 0.9, 0.9999}) {
            double[] motion = Reprojection.motion(matrix, 400.5, 300.5, depth, false, 1715, 965);
            assertEquals(0, motion[0], 1e-6);
            assertEquals(0, motion[1], 1e-6);
        }
    }

    @Test
    void aViewThatMovesSidewaysMovesNearPointsMoreThanFarOnes() {
        double[] projection = perspective(70, 16.0 / 9, 0.05, 512);
        double[] view = rotationY(0);
        // The viewer steps 0.2 blocks along +x between the frames, looking down -z.
        Reprojection.Frame previous = new Reprojection.Frame(projection, view, 0, 64, 0, 0);
        Reprojection.Frame current = new Reprojection.Frame(projection, view, 0.2, 64, 0, 0);
        double[] matrix = Reprojection.currentToPrevious(previous, current);
        int width = 1920;
        int height = 1080;
        double near = depthAt(projection, -4);
        double far = depthAt(projection, -200);
        double[] nearMotion = Reprojection.motion(matrix, 960, 540, near, false, width, height);
        double[] farMotion = Reprojection.motion(matrix, 960, 540, far, false, width, height);
        // The point at the center 4 blocks away stood 0.2 blocks to the viewer's right a frame ago.
        // The field of view is vertical: across, it is wider by the aspect.
        double expected = 0.2 / (4 * Math.tan(Math.toRadians(35)) * 16 / 9) * width / 2;
        assertEquals(expected, nearMotion[0], 1e-3);
        assertEquals(expected * 4 / 200, farMotion[0], 1e-3);
        assertEquals(0, nearMotion[1], 1e-6);
    }

    @Test
    void reversedDepthReprojectsLikeConventionalDepth() {
        double[] projection = perspective(70, 16.0 / 9, 0.05, 512);
        // The reversal the frame puts below every projection: z' = w - z, from clip z in -w..w to 0..w.
        double[] reversal = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -0.5, 0, 0, 0, 0.5, 1};
        double[] reversed = FrameBlock.multiply(reversal, projection);
        double[] view = rotationY(10);
        double[] matrix = Reprojection.currentToPrevious(new Reprojection.Frame(reversed, view, 0, 64, 0, 0),
            new Reprojection.Frame(reversed, view, 0.1, 64.05, 0.3, 0));
        double[] conventional = Reprojection.currentToPrevious(new Reprojection.Frame(projection, view, 0, 64, 0, 0),
            new Reprojection.Frame(projection, view, 0.1, 64.05, 0.3, 0));
        double[] point = FrameBlock.apply(projection, 0.5, -0.25, -6, 1);
        double depth = point[2] / point[3] * 0.5 + 0.5;
        double[] pointReversed = FrameBlock.apply(reversed, 0.5, -0.25, -6, 1);
        double depthReversed = pointReversed[2] / pointReversed[3];
        double x = (point[0] / point[3] * 0.5 + 0.5) * 1280;
        double y = (point[1] / point[3] * 0.5 + 0.5) * 720;
        assertArrayEquals(Reprojection.motion(conventional, x, y, depth, false, 1280, 720),
            Reprojection.motion(matrix, x, y, depthReversed, true, 1280, 720), 1e-6);
    }

    @Test
    void aTeleportOrAnotherDimensionKeepsNothing() {
        double[] projection = perspective(70, 16.0 / 9, 0.05, 512);
        double[] view = rotationY(0);
        Reprojection.Frame here = new Reprojection.Frame(projection, view, 0, 64, 0, 0);
        assertNull(Reprojection.currentToPrevious(here, new Reprojection.Frame(projection, view, 50, 64, 0, 0)));
        assertNull(Reprojection.currentToPrevious(here, new Reprojection.Frame(projection, view, 0, 64, 0, -1)));
        assertNull(Reprojection.currentToPrevious(null, here));
    }

    /** gluPerspective's matrix, column-major. */
    private static double[] perspective(double fovY, double aspect, double near, double far) {
        double f = 1 / Math.tan(Math.toRadians(fovY) / 2);
        return new double[] {f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + near) / (near - far), -1, 0, 0,
            2 * far * near / (near - far), 0};
    }

    private static double[] rotationY(double degrees) {
        double c = Math.cos(Math.toRadians(degrees));
        double s = Math.sin(Math.toRadians(degrees));
        return new double[] {c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 0, 0, 0, 1};
    }

    /** The stored depth, 0 to 1, of a point straight ahead at eye-space z. */
    private static double depthAt(double[] projection, double z) {
        double[] clip = FrameBlock.apply(projection, 0, 0, z, 1);
        return clip[2] / clip[3] * 0.5 + 0.5;
    }
}
