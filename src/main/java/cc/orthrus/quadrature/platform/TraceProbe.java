package cc.orthrus.quadrature.platform;

/**
 * The tier benchmark's indirect-trace probe (D22): one frame's rays traced through the scene for
 * lighting at a render resolution. The trace that indirect light will use supplies it, through
 * {@link TierBench#traceProbe}; until one is given, the benchmark runs without it and the trace's groups
 * are scaled by the other probes.
 *
 * <p>Called on the client thread with the graphics context current, after near terrain's opaque
 * layers. The benchmark brackets {@link #dispatch} with a GPU timer query, so the call issues the
 * trace's work and nothing else that takes GPU time; it binds what it needs and puts back what it
 * changes, leaving no program, image or storage buffer bound.
 */
public interface TraceProbe {

    /** A short name for the log. */
    String name();

    /** Whether the trace can run now: its scene is on the GPU and its programs are built. */
    boolean ready();

    /**
     * Traces one frame's rays at a render resolution and returns how many rays it traced.
     *
     * @param width the render resolution's width in pixels
     * @param height its height
     */
    long dispatch(int width, int height);

    /** No trace: the benchmark runs without the probe. */
    TraceProbe NONE = new TraceProbe() {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public long dispatch(int width, int height) {
            return 0;
        }
    };
}
