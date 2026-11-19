package cc.orthrus.quadrature.core.scale;

/**
 * The sub-pixel offset the world's projection is moved by each frame, so that over a few frames every
 * pixel is sampled at different places inside it and the temporal pass can average them.
 *
 * <p>The offsets follow the Halton sequence in bases 2 and 3, a low-discrepancy sequence: any run of
 * consecutive offsets covers the pixel evenly, so the average converges without a pattern. They repeat
 * every {@code phases} frames and are centered on the pixel, each coordinate in -0.5 to 0.5.
 */
public final class Jitter {

    private Jitter() {
    }

    /** The radical inverse of {@code index} in {@code base}: its digits mirrored about the point, in 0 to 1. */
    public static double halton(int index, int base) {
        double result = 0;
        double fraction = 1.0 / base;
        int rest = index;
        while (rest > 0) {
            result += fraction * (rest % base);
            rest /= base;
            fraction /= base;
        }
        return result;
    }

    /**
     * The offset for a frame, in pixels of the drawn rectangle.
     *
     * @param frame  any count of frames; only its remainder by {@code phases} matters
     * @param phases how many offsets the cycle holds, at least 1
     * @return x and y, each in -0.5 to 0.5
     */
    public static double[] offset(long frame, int phases) {
        // Index 0 of the sequence is the corner; the cycle starts at 1.
        int index = (int) Math.floorMod(frame, (long) Math.max(1, phases)) + 1;
        return new double[] {halton(index, 2) - 0.5, halton(index, 3) - 0.5};
    }

    /**
     * The matrix that moves clip space by an offset, column-major, to be multiplied in on the left of a
     * projection: {@code x' = x + dx w} and {@code y' = y + dy w}, so the picture moves by {@code dx} and
     * {@code dy} in normalized device coordinates whatever the depth.
     *
     * <p>It commutes with a reversal of depth, which changes z alone, so it may stand on either side of
     * one.
     *
     * @param pixelsX the offset in pixels
     * @param width   the drawn rectangle's width in pixels
     */
    public static float[] matrix(double pixelsX, double pixelsY, int width, int height) {
        float[] matrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        matrix[12] = (float) (2 * pixelsX / width);
        matrix[13] = (float) (2 * pixelsY / height);
        return matrix;
    }

    /**
     * Takes an offset back out of a projection the driver holds: the projection the game built, with
     * the offset's matrix on its left, becomes the projection without it.
     *
     * @param projection column-major, changed in place
     * @param ndcX       the offset in normalized device coordinates, {@code 2 * pixels / width}
     */
    public static void remove(double[] projection, double ndcX, double ndcY) {
        for (int column = 0; column < 4; column++) {
            double w = projection[column * 4 + 3];
            projection[column * 4] -= ndcX * w;
            projection[column * 4 + 1] -= ndcY * w;
        }
    }
}
