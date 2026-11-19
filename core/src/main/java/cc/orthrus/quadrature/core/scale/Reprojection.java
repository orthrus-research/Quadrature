package cc.orthrus.quadrature.core.scale;

import cc.orthrus.quadrature.core.stage.FrameBlock;

/**
 * Where what a pixel shows stood in the frame before: the matrix that takes a point from this frame's
 * clip space to the previous frame's, for terrain and everything else that does not move. The motion
 * pass applies it to each pixel's depth.
 *
 * <p>Each frame's matrices are the ones the driver held for the world, the projection with the sub-pixel
 * offset taken out, and its model-view places points relative to the frame's origin, the viewer's
 * interpolated position in double precision. Between two frames the origin moves, so a point's place
 * relative to the origin moves by the difference; the composition is made in double precision and only
 * the result is rounded to single.
 */
public final class Reprojection {

    /**
     * A frame's view: its projection without the offset, its model-view and its origin. The arrays are
     * kept by reference; give each frame its own.
     */
    public record Frame(double[] projection, double[] modelView, double originX, double originY, double originZ,
        int dimension) {

        /** The matrix from a place relative to the origin to clip space. */
        double[] viewProjection() {
            return FrameBlock.multiply(projection, modelView);
        }
    }

    /**
     * How far a view may move in a frame before what it kept is dropped: farther is a teleport, after
     * which nothing of the previous frame lies where the matrix would look for it.
     */
    public static final double TELEPORT_BLOCKS = 8;

    private Reprojection() {
    }

    /**
     * The matrix from this frame's clip space to the previous frame's, column-major.
     *
     * @return the matrix, or null where the previous frame cannot be reached: none kept, another
     *         dimension, a teleport, or a matrix with no inverse
     */
    public static double[] currentToPrevious(Frame previous, Frame current) {
        if (previous == null || current == null || previous.dimension() != current.dimension()) {
            return null;
        }
        double dx = current.originX() - previous.originX();
        double dy = current.originY() - previous.originY();
        double dz = current.originZ() - previous.originZ();
        if (dx * dx + dy * dy + dz * dz > TELEPORT_BLOCKS * TELEPORT_BLOCKS) {
            return null;
        }
        double[] inverse = FrameBlock.inverse(current.viewProjection());
        if (inverse == null) {
            return null;
        }
        // A place relative to this frame's origin is the same place relative to the previous one moved
        // by how far the origin came.
        double[] move = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, dx, dy, dz, 1};
        return FrameBlock.multiply(previous.viewProjection(), FrameBlock.multiply(move, inverse));
    }

    /**
     * A pixel's motion as the motion pass computes it, for tests and diagnostics: where the point a
     * pixel center shows lay in the previous frame, minus where it lies now, in pixels.
     *
     * @param matrix    from {@link #currentToPrevious}
     * @param x         the pixel's center, without the offset, in pixels from the left
     * @param y         the pixel's center, in pixels from the bottom
     * @param depth     the depth as stored, 0 to 1
     * @param zeroToOne whether clip depth runs 0 to 1 (reversed depth) rather than -1 to 1
     */
    public static double[] motion(double[] matrix, double x, double y, double depth, boolean zeroToOne, int width,
        int height) {
        double ndcX = x / width * 2 - 1;
        double ndcY = y / height * 2 - 1;
        double ndcZ = zeroToOne ? depth : depth * 2 - 1;
        double[] previous = FrameBlock.apply(matrix, ndcX, ndcY, ndcZ, 1);
        double px = (previous[0] / previous[3] * 0.5 + 0.5) * width;
        double py = (previous[1] / previous[3] * 0.5 + 0.5) * height;
        return new double[] {px - x, py - y};
    }
}
