package cc.orthrus.quadrature.rays;

import cc.orthrus.quadrature.core.scene.PublishedScene;
import cc.orthrus.quadrature.core.scene.TraceFormat;

/**
 * Rays traced through the light scene's occupancy bricks on the GPU: rays in a buffer go in, a result
 * for each comes out in another, and the time the trace took on the GPU is read later without waiting.
 *
 * <p>Rays and results lie as {@link TraceFormat} says: a ray's origin is relative to the lowest corner
 * of the box's origin cube as {@link #scene()} gives it in the frame the trace is called in. The trace
 * reads what the scene's buffers hold in that frame, binds nothing it does not put back, and needs no
 * state set by the caller. Call it on the client thread, inside a frame.
 */
public interface RayTrace {

    /** Bytes to a ray. */
    int RAY_BYTES = TraceFormat.RAY_BYTES;
    /** Bytes to a result. */
    int RESULT_BYTES = TraceFormat.RESULT_BYTES;

    /** The scene this frame's trace reads, or null when there is none to trace. */
    PublishedScene scene();

    /**
     * Traces the first {@code count} rays of one buffer into the first {@code count} results of another,
     * between two GPU timestamps.
     *
     * @return the timing, to be polled in a later frame; null when there is no scene to trace
     */
    Timed trace(int rayBuffer, int resultBuffer, int count);

    /** The GPU time a trace took, read when the driver has it. */
    interface Timed {
        /** Whether the time is known; asks the driver without waiting. */
        boolean done();

        /** Milliseconds between the timestamps around the trace, or NaN until {@link #done()}. */
        double gpuMillis();

        /** The rays traced. */
        int count();
    }
}
