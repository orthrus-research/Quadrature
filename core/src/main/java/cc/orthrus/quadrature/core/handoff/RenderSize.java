package cc.orthrus.quadrature.core.handoff;

/**
 * The render resolution an upscaler reads from, as a fraction of the output resolution it writes. DLSS
 * takes its render size from a query per mode; a measurement fixes the fraction instead. Each side is
 * rounded to the nearest pixel and kept between 32 pixels, DLSS's smallest output, and the output's own.
 */
public record RenderSize(int width, int height) {

    public static final int MINIMUM = 32;

    public static RenderSize of(int outputWidth, int outputHeight, double scale) {
        if (!(scale > 0 && scale <= 1)) {
            throw new IllegalArgumentException("A render scale must lie in (0, 1], not " + scale + ".");
        }
        if (outputWidth < 1 || outputHeight < 1) {
            throw new IllegalArgumentException("No output of " + outputWidth + "x" + outputHeight + ".");
        }
        return new RenderSize(side(outputWidth, scale), side(outputHeight, scale));
    }

    private static int side(int output, double scale) {
        int scaled = (int) Math.round(output * scale);
        return Math.min(output, Math.max(Math.min(MINIMUM, output), scaled));
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}
