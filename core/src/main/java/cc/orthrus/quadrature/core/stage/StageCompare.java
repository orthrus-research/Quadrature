package cc.orthrus.quadrature.core.stage;

/**
 * Sets what a stage wrote beside what it was expected to write, pixel by pixel, and counts what
 * differs. A mask leaves pixels out. Images are rows from the bottom, left to right, four channels a
 * pixel.
 *
 * <p>Radiance: every color channel within an absolute part plus a share of the expected value, and
 * alpha exactly the expected. Display: every channel within a number of levels of 255.
 */
public final class StageCompare {

    /** What radiance may differ by apart from its share of the expected value. */
    public static final float RADIANCE_ABSOLUTE = 0.004f;
    /** The share of the expected value radiance may differ by besides. */
    public static final float RADIANCE_RELATIVE = 0.01f;
    /** The levels of 255 a display channel may differ by. */
    public static final int DISPLAY_LEVELS = 2;

    /** What a comparison found. */
    public static final class Result {
        public final int width;
        public final int height;
        public int compared;
        public int masked;
        public int differing;
        /** The pixels that differ only in alpha. */
        public int alphaOnly;
        /** The greatest difference of any channel compared, and where: in the units of the image. */
        public double worst;
        public int worstX = -1;
        public int worstY = -1;
        /** The box that holds every differing pixel, or -1 where none differs. */
        public int minX = -1;
        public int minY = -1;
        public int maxX = -1;
        public int maxY = -1;
        /** For each pixel, 0 where it agrees or is left out, else the difference scaled to 1 to 255. */
        public final byte[] difference;

        Result(int width, int height) {
            this.width = width;
            this.height = height;
            difference = new byte[width * height];
        }

        public boolean passes() {
            return differing == 0;
        }

        void differs(int x, int y, double by, double scale) {
            differing++;
            minX = minX < 0 ? x : Math.min(minX, x);
            minY = minY < 0 ? y : Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            difference[y * width + x] = (byte) Math.max(1, Math.min(255, (int) Math.round(by / scale * 255)));
        }

        @Override
        public String toString() {
            return String.format("%s: %d of %d pixels compared differ (%d only in alpha), %d left out by the mask; worst %.5g at "
                    + "(%d, %d); differing pixels lie in x %d to %d, y %d to %d", passes() ? "PASS" : "FAIL", differing, compared,
                alphaOnly, masked, worst, worstX, worstY, minX, maxX, minY, maxY);
        }
    }

    private StageCompare() {
    }

    /**
     * @param got      what was written, four floats a pixel
     * @param expected what should have been
     * @param mask     a byte a pixel, anything but 0 leaving the pixel out; null to compare all
     */
    public static Result radiance(float[] got, float[] expected, byte[] mask, int width, int height) {
        return radiance(got, expected, mask, 0, width, height, RADIANCE_ABSOLUTE, RADIANCE_RELATIVE, true);
    }

    /**
     * As {@link #radiance(float[], float[], byte[], int, int)}, with the tolerances given.
     *
     * @param compared   the mask's value for a pixel that is compared; any other leaves it out
     * @param absolute   what a color channel may differ by apart from its share of the expected value
     * @param relative   the share of the expected value it may differ by besides
     * @param alphaExact whether alpha must be exactly the expected; otherwise alpha is not compared
     */
    public static Result radiance(float[] got, float[] expected, byte[] mask, int compared, int width, int height,
        double absolute, double relative, boolean alphaExact) {
        Result result = new Result(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = y * width + x;
                if (mask != null && (mask[pixel] & 0xFF) != compared) {
                    result.masked++;
                    continue;
                }
                result.compared++;
                double most = 0;
                boolean color = false;
                for (int channel = 0; channel < 3; channel++) {
                    float want = expected[pixel * 4 + channel];
                    double by = Math.abs(got[pixel * 4 + channel] - want);
                    // Not a number never agrees.
                    if (!(by <= absolute + relative * Math.abs(want))) {
                        color = true;
                    }
                    most = Double.isNaN(by) ? Double.POSITIVE_INFINITY : Math.max(most, by);
                }
                boolean alpha = alphaExact && got[pixel * 4 + 3] != expected[pixel * 4 + 3];
                if (most > result.worst) {
                    result.worst = most;
                    result.worstX = x;
                    result.worstY = y;
                }
                if (color || alpha) {
                    if (!color) {
                        result.alphaOnly++;
                    }
                    result.differs(x, y, color ? most : 1, 1);
                }
            }
        }
        return result;
    }

    /**
     * @param got      what was written, four bytes a pixel
     * @param expected what should have been
     * @param mask     as {@link #radiance}
     */
    public static Result display(byte[] got, byte[] expected, byte[] mask, int width, int height) {
        return display(got, expected, mask, 0, width, height, DISPLAY_LEVELS, true);
    }

    /**
     * As {@link #display(byte[], byte[], byte[], int, int)}, with the tolerances given.
     *
     * @param compared      the mask's value for a pixel that is compared; any other leaves it out
     * @param levels        the levels of 255 a channel may differ by
     * @param alphaCompared whether alpha is compared at all
     */
    public static Result display(byte[] got, byte[] expected, byte[] mask, int compared, int width, int height, int levels,
        boolean alphaCompared) {
        Result result = new Result(width, height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = y * width + x;
                if (mask != null && (mask[pixel] & 0xFF) != compared) {
                    result.masked++;
                    continue;
                }
                result.compared++;
                int most = 0;
                int alpha = alphaCompared ? Math.abs((got[pixel * 4 + 3] & 0xFF) - (expected[pixel * 4 + 3] & 0xFF)) : 0;
                for (int channel = 0; channel < (alphaCompared ? 4 : 3); channel++) {
                    most = Math.max(most, Math.abs((got[pixel * 4 + channel] & 0xFF) - (expected[pixel * 4 + channel] & 0xFF)));
                }
                if (most > result.worst) {
                    result.worst = most;
                    result.worstX = x;
                    result.worstY = y;
                }
                if (most > levels) {
                    int color = 0;
                    for (int channel = 0; channel < 3; channel++) {
                        color = Math.max(color, Math.abs((got[pixel * 4 + channel] & 0xFF) - (expected[pixel * 4 + channel] & 0xFF)));
                    }
                    if (color <= levels && alpha > levels) {
                        result.alphaOnly++;
                    }
                    result.differs(x, y, most, 255);
                }
            }
        }
        return result;
    }

    /** Reads little-endian 32-bit floats from raw bytes. */
    public static float[] floats(byte[] raw) {
        java.nio.FloatBuffer view = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] out = new float[view.remaining()];
        view.get(out);
        return out;
    }
}
