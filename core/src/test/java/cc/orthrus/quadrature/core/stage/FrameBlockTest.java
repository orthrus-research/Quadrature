package cc.orthrus.quadrature.core.stage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class FrameBlockTest {

    @Test
    void theBlockIsLaidOutAsStd140Has() {
        // By hand: a mat4 is four vec4 columns, 64 bytes; an array of four, 256; four vec4, 64; then
        // seven members of 16 bytes each.
        assertEquals(0, FrameBlock.OFFSET_INVERSE_VIEW_PROJECTION);
        assertEquals(64, FrameBlock.OFFSET_LIGHT_VIEWS);
        assertEquals(64 + 4 * 64, FrameBlock.OFFSET_LIGHT_SCALES);
        assertEquals(320 + 4 * 16, FrameBlock.OFFSET_SUN);
        assertEquals(400, FrameBlock.OFFSET_LIGHT);
        assertEquals(416, FrameBlock.OFFSET_EYE_BLOCK);
        assertEquals(432, FrameBlock.OFFSET_EYE_FRACTION);
        assertEquals(448, FrameBlock.OFFSET_TARGET_SIZE);
        assertEquals(464, FrameBlock.OFFSET_WEATHER);
        assertEquals(480, FrameBlock.OFFSET_FRAME);
        assertEquals(496, FrameBlock.OFFSET_PREVIOUS_VIEW_PROJECTION);
        assertEquals(560, FrameBlock.OFFSET_EYE_MOVEMENT);
        assertEquals(576, FrameBlock.OFFSET_CLOUD_DRIFT);
        assertEquals(576 + 16, FrameBlock.BYTES);
        // By the names a driver gives the members, an array's with its first index and any with the block's name before it.
        assertEquals(0, FrameBlock.offsetOf("qInverseViewProjection"));
        assertEquals(64, FrameBlock.offsetOf("qShadowViewProjection[0]"));
        assertEquals(320, FrameBlock.offsetOf("QFrame.qShadowScale[0]"));
        assertEquals(480, FrameBlock.offsetOf("qFrame"));
        assertEquals(576, FrameBlock.offsetOf("qCloudDrift"));
        assertEquals(-1, FrameBlock.offsetOf("qShadowScale[1]"));
        assertEquals(-1, FrameBlock.offsetOf("qSomethingElse"));

        FrameBlock block = new FrameBlock();
        for (int i = 0; i < 16; i++) {
            block.inverseViewProjection[i] = 100 + i;
            block.lightViews[1][i] = 200 + i;
        }
        block.lightScales[1][0] = 0.125f;
        block.lightScales[1][1] = 544f;
        block.sun[1] = 0.5f;
        block.light[2] = -0.25f;
        block.eye(-12.25, 70.5, 3.0);
        block.targetSize(1280, 720);
        block.weather(0.5f, 0.25f, 1f, 3600 + 12.5);
        block.frame(77, true, -1, 1);
        ByteBuffer raw = ByteBuffer.allocate(FrameBlock.BYTES + 8).order(ByteOrder.BIG_ENDIAN);
        raw.position(4);
        block.write(raw);
        assertEquals(4 + FrameBlock.BYTES, raw.position());
        ByteBuffer read = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        // Column-major: the second number of the matrix is the first column's second row.
        assertEquals(100f, read.getFloat(4), 0f);
        assertEquals(101f, read.getFloat(4 + 4), 0f);
        assertEquals(115f, read.getFloat(4 + 60), 0f);
        // The first light view is all zeros; the second begins 64 bytes after the first.
        assertEquals(0f, read.getFloat(4 + 64), 0f);
        assertEquals(200f, read.getFloat(4 + 64 + 64), 0f);
        assertEquals(215f, read.getFloat(4 + 64 + 64 + 60), 0f);
        assertEquals(0.125f, read.getFloat(4 + 320 + 16), 0f);
        assertEquals(544f, read.getFloat(4 + 320 + 16 + 4), 0f);
        assertEquals(0.5f, read.getFloat(4 + 384 + 4), 0f);
        assertEquals(-0.25f, read.getFloat(4 + 400 + 8), 0f);
        assertEquals(-13, read.getInt(4 + 416));
        assertEquals(70, read.getInt(4 + 416 + 4));
        assertEquals(3, read.getInt(4 + 416 + 8));
        assertEquals(0.75f, read.getFloat(4 + 432), 0f);
        assertEquals(0.5f, read.getFloat(4 + 432 + 4), 0f);
        assertEquals(0f, read.getFloat(4 + 432 + 8), 0f);
        assertEquals(1280f, read.getFloat(4 + 448), 0f);
        assertEquals(720f, read.getFloat(4 + 448 + 4), 0f);
        assertEquals(1f / 1280, read.getFloat(4 + 448 + 8), 0f);
        assertEquals(1f / 720, read.getFloat(4 + 448 + 12), 0f);
        assertEquals(0.5f, read.getFloat(4 + 464), 0f);
        assertEquals(0.25f, read.getFloat(4 + 464 + 4), 0f);
        assertEquals(1f, read.getFloat(4 + 464 + 8), 0f);
        assertEquals(12.5f, read.getFloat(4 + 464 + 12), 0f);
        assertEquals(77, read.getInt(4 + 480));
        assertEquals(1, read.getInt(4 + 480 + 4));
        assertEquals(-1, read.getInt(4 + 480 + 8));
        // The count of light views is the frame member's last number, at byte 492.
        assertEquals(1, read.getInt(4 + 492));
    }

    @Test
    void anUnknownDimensionIsGivenAsTheOverworldAndTimeWraps() {
        FrameBlock block = new FrameBlock();
        block.frame(1, false, 7, 0);
        assertEquals(0, block.frame[2]);
        assertEquals(0, block.frame[1]);
        block.frame(1, false, 1, 9);
        assertEquals(1, block.frame[2]);
        // No more light views are counted than the block has room for, and none where no depth is drawn.
        assertEquals(FrameBlock.LIGHT_VIEWS, block.frame[3]);
        block.frame(1, false, 0, 0);
        assertEquals(0, block.frame[3]);
        block.weather(0, 0, 1, 7199.5);
        assertEquals(3599.5f, block.weather[3], 0f);
        block.weather(0, 0, 1, -1);
        assertEquals(3599f, block.weather[3], 0f);
    }

    @Test
    void aMatrixTimesItsInverseIsNothingChanged() {
        double[] matrix = {2, 0, 0, 0, 0, 3, 1, 0, 0, -1, 4, -1, 5, 6, 7, 1};
        double[] product = FrameBlock.multiply(matrix, FrameBlock.inverse(matrix));
        double[] same = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        assertArrayEquals(same, product, 1e-12);
        assertNull(FrameBlock.inverse(new double[16]));
    }

    /** A projection as the game's with depth reversed: the near plane at depth 1, the far plane at 0. */
    private static float[] reversedProjection(double near, double far, double halfWidth, double halfHeight) {
        double[] p = new double[16];
        p[0] = near / halfWidth;
        p[5] = near / halfHeight;
        // Conventional clip z = (-(f + n) z - 2 f n) / (f - n), w = -z; reversed z' = (w - z) / 2.
        double a = -(far + near) / (far - near);
        double b = -2 * far * near / (far - near);
        p[10] = 0.5 * (-1 - a);
        p[14] = 0.5 * -b;
        p[11] = -1;
        return FrameBlock.floats(p);
    }

    @Test
    void theInverseViewProjectionGivesBackAPlaceRelativeToTheCamera() {
        float[] projection = reversedProjection(0.05, 300, 0.05, 0.03);
        // Turned a quarter about the upward axis, and moved: the move must not show in the result.
        double c = Math.cos(0.8);
        double s = Math.sin(0.8);
        float[] modelView = FrameBlock.floats(new double[] {c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, 3, -1.62, 9, 1});
        double[] inverse = FrameBlock.inverseViewProjectionAtEye(projection, modelView);
        // A place 20 blocks before the camera and a little aside, along the world's axes.
        double[] world = {4, -3, -20};
        double[] rotation = FrameBlock.doubles(modelView);
        rotation[12] = 0;
        rotation[13] = 0;
        rotation[14] = 0;
        double[] eye = FrameBlock.apply(rotation, world[0], world[1], world[2], 1);
        double[] clip = FrameBlock.apply(FrameBlock.doubles(projection), eye[0], eye[1], eye[2], 1);
        double depth = clip[2] / clip[3];
        // Reversed: nearer than the far plane, farther than the near.
        assertEquals(true, depth > 0 && depth < 1);
        double[] back = FrameBlock.apply(inverse, clip[0] / clip[3], clip[1] / clip[3], depth, 1);
        assertEquals(world[0], back[0] / back[3], 1e-3);
        assertEquals(world[1], back[1] / back[3], 1e-3);
        assertEquals(world[2], back[2] / back[3], 1e-3);
    }

    @Test
    void aLightViewIsRetoldFromTheEyeWithDepthFromNothingToOne() {
        // A box 100 blocks a side looking straight down: x and z across, depth along y, nearest at the top.
        float[] drawn = FrameBlock.floats(new double[] {
            1 / 50.0, 0, 0, 0,
            0, 0, -1 / 50.0, 0,
            0, 1 / 50.0, 0, 0,
            0, 0, 0, 1});
        // The eye stands 10 east, 1.62 above and 5 south of the light view's origin.
        double[] matrix = FrameBlock.lightViewFromEye(drawn, 10, 1.62, 5);
        // The eye itself: the origin of what the matrix takes.
        double[] atEye = FrameBlock.apply(matrix, 0, 0, 0, 1);
        assertEquals(10 / 50.0, atEye[0], 1e-6);
        assertEquals(5 / 50.0, atEye[1], 1e-6);
        // Drawn depth -1.62 / 50 from -1 to 1 becomes 0.5 - 0.0162 from 0 to 1.
        assertEquals(0.5 - 1.62 / 100, atEye[2], 1e-6);
        assertEquals(1.0, atEye[3], 1e-12);
        // The top of the box, 50 above the origin, is nearest the light: depth 0. Its floor, depth 1.
        assertEquals(0.0, FrameBlock.apply(matrix, 0, 50 - 1.62, 0, 1)[2], 1e-6);
        assertEquals(1.0, FrameBlock.apply(matrix, 0, -50 - 1.62, 0, 1)[2], 1e-6);
    }

    @Test
    void timeIsTheRealTimeOfFramesWhileTheGameRuns() {
        FrameClock clock = new FrameClock();
        long second = 1_000_000_000L;
        // The first frame has nothing before it.
        assertEquals(0.0, clock.frame(50 * second, false), 0);
        assertEquals(0.5, clock.frame(50 * second + second / 2, false), 1e-12);
        assertEquals(1.5, clock.frame(51 * second + second / 2, false), 1e-12);
        // Paused: it stands, however long the pause.
        assertEquals(1.5, clock.frame(60 * second, true), 1e-12);
        assertEquals(1.5, clock.frame(90 * second, true), 1e-12);
        // The first frame after the pause does not count the pause.
        assertEquals(1.5, clock.frame(91 * second, false), 1e-12);
        assertEquals(1.75, clock.frame(91 * second + second / 4, false), 1e-12);
        // A clock that steps back adds nothing.
        assertEquals(1.75, clock.frame(80 * second, false), 1e-12);
        // In the block it wraps at an hour.
        FrameBlock block = new FrameBlock();
        block.weather(0, 0, 1, 3600 + clock.seconds());
        assertEquals(1.75f, block.weather[3], 0f);
    }
}