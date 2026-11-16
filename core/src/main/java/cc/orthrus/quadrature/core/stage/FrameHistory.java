package cc.orthrus.quadrature.core.stage;

/**
 * What a shading stage is told of the frame before, and whether what it kept from earlier frames
 * still holds. Kept from frame to frame by the host and written into the {@link FrameBlock}.
 *
 * <p>History is dropped on the first frame, when the window's size or the dimension changes, when the
 * eye has moved farther in one frame than the stage's manifest allows, and whenever the host says so:
 * the stage switched on, or its targets made anew. On such a frame the view-projection given as the
 * frame before's is this frame's own and the eye has not moved.
 *
 * <p>The eye's movement is taken from places held as doubles, so that it is exact far from the
 * world's origin. Cloud drift is a distance in blocks at two speeds, summed from the time between
 * frames while the game ran, and starts again at {@link #DRIFT_WRAP}.
 */
public final class FrameHistory {

    /** Blocks a second of the faster drift. */
    public static final double DRIFT_FAST = 3.1;
    /** Blocks a second of the slower drift. */
    public static final double DRIFT_SLOW = 1.2;
    /** Blocks at which a drift starts again. */
    public static final double DRIFT_WRAP = 1_048_576;

    private final double[] viewProjection = new double[16];
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private int width;
    private int height;
    private int dimension;
    private boolean held;
    private double seconds;
    private boolean timed;
    private double fast;
    private double slow;
    private long resets;

    /** What was kept no longer holds: the next frame drops history. */
    public void drop() {
        held = false;
    }

    /**
     * Takes a frame and writes what the block says of the frame before.
     *
     * @param viewProjectionAtEye this frame's view-projection, the eye at the origin, column-major
     * @param eyeX                the eye's place in the world
     * @param width               the window's width
     * @param dimension           the game's dimension
     * @param teleportBlocks      how far the eye may move in a frame before history is dropped
     * @param seconds             the time the stage animates by, which stands still while the game is paused
     * @param block               the block written to: its previous view-projection, eye movement and cloud drift
     * @return whether this frame drops history
     */
    public boolean advance(double[] viewProjectionAtEye, double eyeX, double eyeY, double eyeZ, int width, int height,
        int dimension, double teleportBlocks, double seconds, FrameBlock block) {
        double movedX = eyeX - this.eyeX;
        double movedY = eyeY - this.eyeY;
        double movedZ = eyeZ - this.eyeZ;
        boolean reset = !held || width != this.width || height != this.height || dimension != this.dimension
            || !(movedX * movedX + movedY * movedY + movedZ * movedZ <= teleportBlocks * teleportBlocks);
        if (reset) {
            resets++;
            for (int i = 0; i < 16; i++) {
                block.previousViewProjection[i] = (float) viewProjectionAtEye[i];
            }
            movedX = 0;
            movedY = 0;
            movedZ = 0;
        } else {
            for (int i = 0; i < 16; i++) {
                block.previousViewProjection[i] = (float) viewProjection[i];
            }
        }
        block.eyeMovement[0] = (float) movedX;
        block.eyeMovement[1] = (float) movedY;
        block.eyeMovement[2] = (float) movedZ;
        block.eyeMovement[3] = 0;

        // Drift goes on across a reset: clouds do not jump back when history is dropped.
        double passed = timed ? Math.max(0, seconds - this.seconds) : 0;
        fast = (fast + passed * DRIFT_FAST) % DRIFT_WRAP;
        slow = (slow + passed * DRIFT_SLOW) % DRIFT_WRAP;
        block.cloudDrift[0] = (float) fast;
        block.cloudDrift[1] = (float) slow;
        block.cloudDrift[2] = 0;
        block.cloudDrift[3] = 0;

        System.arraycopy(viewProjectionAtEye, 0, viewProjection, 0, 16);
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.width = width;
        this.height = height;
        this.dimension = dimension;
        this.seconds = seconds;
        timed = true;
        held = true;
        return reset;
    }

    /** How many frames have dropped history. */
    public long resets() {
        return resets;
    }
}
