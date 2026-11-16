package cc.orthrus.quadrature.core.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FrameHistoryTest {

    private static double[] matrix(double first) {
        double[] out = new double[16];
        for (int i = 0; i < 16; i++) {
            out[i] = first + i;
        }
        return out;
    }

    @Test
    void theFrameBeforeIsToldAndTheEyesMovementIsExactFarFromTheOrigin() {
        FrameHistory history = new FrameHistory();
        FrameBlock block = new FrameBlock();
        // The first frame drops history: the matrix given is its own and the eye has not moved.
        assertTrue(history.advance(matrix(100), 30_000_000.25, 64, -30_000_000.5, 1280, 720, 0, 64, 0, block));
        assertEquals(100f, block.previousViewProjection[0], 0f);
        assertEquals(115f, block.previousViewProjection[15], 0f);
        assertEquals(0f, block.eyeMovement[0], 0f);
        // The next is told of the first, and how far the eye moved: this eye less that one.
        assertFalse(history.advance(matrix(200), 30_000_000.375, 65.5, -30_000_001.0, 1280, 720, 0, 64, 0.5, block));
        assertEquals(100f, block.previousViewProjection[0], 0f);
        assertEquals(0.125f, block.eyeMovement[0], 0f);
        assertEquals(1.5f, block.eyeMovement[1], 0f);
        assertEquals(-0.5f, block.eyeMovement[2], 0f);
        assertEquals(0f, block.eyeMovement[3], 0f);
        assertFalse(history.advance(matrix(300), 30_000_000.375, 65.5, -30_000_001.0, 1280, 720, 0, 64, 1, block));
        assertEquals(200f, block.previousViewProjection[0], 0f);
        assertEquals(0f, block.eyeMovement[1], 0f);
        assertEquals(1, history.resets());
    }

    @Test
    void historyIsDroppedWhenItNoLongerHolds() {
        FrameHistory history = new FrameHistory();
        FrameBlock block = new FrameBlock();
        assertTrue(history.advance(matrix(1), 0, 0, 0, 1280, 720, 0, 64, 0, block));
        assertFalse(history.advance(matrix(2), 0, 0, 0, 1280, 720, 0, 64, 0, block));
        // The window's size.
        assertTrue(history.advance(matrix(3), 0, 0, 0, 1281, 720, 0, 64, 0, block));
        assertEquals(3f, block.previousViewProjection[0], 0f);
        assertFalse(history.advance(matrix(4), 0, 0, 0, 1281, 720, 0, 64, 0, block));
        // The dimension.
        assertTrue(history.advance(matrix(5), 0, 0, 0, 1281, 720, -1, 64, 0, block));
        // Exactly as far as is allowed is not a teleport; farther is, and the eye is then said not to have moved.
        assertFalse(history.advance(matrix(6), 64, 0, 0, 1281, 720, -1, 64, 0, block));
        assertEquals(64f, block.eyeMovement[0], 0f);
        assertTrue(history.advance(matrix(7), 64, 48, 48, 1281, 720, -1, 64, 0, block));
        assertEquals(0f, block.eyeMovement[1], 0f);
        assertEquals(7f, block.previousViewProjection[0], 0f);
        // The host says so.
        assertFalse(history.advance(matrix(8), 64, 48, 48, 1281, 720, -1, 64, 0, block));
        history.drop();
        assertTrue(history.advance(matrix(9), 64, 48, 48, 1281, 720, -1, 64, 0, block));
        // A place that is not a number is never near the last.
        assertTrue(history.advance(matrix(10), Double.NaN, 48, 48, 1281, 720, -1, 64, 0, block));
        assertEquals(6, history.resets());
    }

    @Test
    void cloudsDriftByTheTimeBetweenFramesAndStartAgain() {
        FrameHistory history = new FrameHistory();
        FrameBlock block = new FrameBlock();
        history.advance(matrix(1), 0, 0, 0, 1280, 720, 0, 64, 100, block);
        // Nothing has passed before the first frame, whatever the time it is given.
        assertEquals(0f, block.cloudDrift[0], 0f);
        history.advance(matrix(1), 0, 0, 0, 1280, 720, 0, 64, 110, block);
        assertEquals(31f, block.cloudDrift[0], 1e-4f);
        assertEquals(12f, block.cloudDrift[1], 1e-4f);
        assertEquals(0f, block.cloudDrift[2], 0f);
        // Time that stands still, as while the game is paused, moves nothing; nor does time that steps back.
        history.advance(matrix(1), 0, 0, 0, 1280, 720, 0, 64, 110, block);
        history.advance(matrix(1), 0, 0, 0, 1280, 720, 0, 64, 90, block);
        assertEquals(31f, block.cloudDrift[0], 1e-4f);
        // Drift goes on across a frame that drops history.
        history.drop();
        history.advance(matrix(1), 0, 0, 0, 1280, 720, 0, 64, 100, block);
        assertEquals(62f, block.cloudDrift[0], 1e-4f);
        // And starts again at the wrap.
        history.advance(matrix(1), 0, 0, 0, 1280, 720, 0, 64, 100 + FrameHistory.DRIFT_WRAP / FrameHistory.DRIFT_FAST, block);
        assertEquals(62f, block.cloudDrift[0], 1e-2f);
    }
}
