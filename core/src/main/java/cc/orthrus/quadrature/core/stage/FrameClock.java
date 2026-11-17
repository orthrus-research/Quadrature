package cc.orthrus.quadrature.core.stage;

/**
 * The time a shading stage animates by: the real time that has passed in frames while the game ran,
 * in seconds. It stands still while the game is paused and takes no notice of the world's own clock,
 * so setting the time of day does not make it jump. It is the sum of the times between frames, and
 * the time across a pause is not counted.
 */
public final class FrameClock {

    private double seconds;
    private long last;
    private boolean started;
    private boolean wasPaused;

    /**
     * Takes a frame.
     *
     * @param nanos  the real time now, from a clock that only runs forward
     * @param paused whether the game is paused in this frame
     * @return the seconds accumulated so far
     */
    public double frame(long nanos, boolean paused) {
        if (started && !paused && !wasPaused) {
            seconds += Math.max(0, nanos - last) / 1e9;
        }
        started = true;
        wasPaused = paused;
        last = nanos;
        return seconds;
    }

    /** The seconds accumulated so far. */
    public double seconds() {
        return seconds;
    }
}
