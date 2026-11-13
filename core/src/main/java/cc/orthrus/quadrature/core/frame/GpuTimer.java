package cc.orthrus.quadrature.core.frame;

/**
 * Times stages on the GPU. Results arrive some frames after the work they measure, and only when
 * reading them cannot stall the frame; a result that is not ready in time is dropped, never waited for.
 */
public interface GpuTimer {

    /** Called once per frame before any stage: delivers whatever earlier results are ready. */
    void beginFrame(Sink sink);

    /** Starts timing the stage at this index. At most one stage is timed at a time. */
    void begin(int stageIndex);

    /** Stops timing the stage begun last. */
    void end();

    interface Sink {
        void gpuNanos(int stageIndex, long nanos);
    }

    /** A timer that measures nothing, for a platform or a test without one. */
    GpuTimer NONE = new GpuTimer() {
        @Override
        public void beginFrame(Sink sink) {
        }

        @Override
        public void begin(int stageIndex) {
        }

        @Override
        public void end() {
        }
    };
}
