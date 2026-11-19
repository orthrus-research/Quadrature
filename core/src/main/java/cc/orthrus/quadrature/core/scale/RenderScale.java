package cc.orthrus.quadrature.core.scale;

/**
 * How large the world is drawn against the picture it is shown in. The world pass draws into a
 * framebuffer of {@link #side} pixels each way, and the spatial scaler fills the whole picture from it.
 *
 * <p>Both sides are scaled by the same factor and rounded to whole pixels, so the rectangle's shape
 * differs from the picture's by under a pixel. The game builds its projection from the picture's shape,
 * which the scaler restores exactly: a point at a fraction of the rectangle lands at the same fraction
 * of the picture.
 */
public final class RenderScale {

    /** The smallest factor accepted; below it the scaler would invent more than a third of each side. */
    public static final double LEAST = 0.5;

    private RenderScale() {
    }

    /**
     * A factor read from a setting: the value if it lies in {@link #LEAST} to 1, otherwise 1.
     *
     * @param text the setting as written, or null where it is not set
     */
    public static double parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 1;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return value >= LEAST && value <= 1 ? value : 1;
        } catch (NumberFormatException notANumber) {
            return 1;
        }
    }

    /** Whether a setting names a factor {@link #parse} would refuse. */
    public static boolean refused(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return !(value >= LEAST && value <= 1);
        } catch (NumberFormatException notANumber) {
            return true;
        }
    }

    /** One side of the drawn rectangle: the output's side times the factor, to the nearest pixel, at least 1. */
    public static int side(int output, double scale) {
        return Math.max(1, Math.min(output, (int) Math.round(output * scale)));
    }

    /**
     * Where an output pixel's center falls in the drawn rectangle, in its texel indices: texel i is
     * centered at i, so the first output pixel's center maps a little inside the first texel.
     *
     * @param pixel  the output pixel's index along one axis
     * @param output the output's side
     * @param drawn  the rectangle's side
     */
    public static double source(int pixel, int output, int drawn) {
        return (pixel + 0.5) * drawn / output - 0.5;
    }
}
