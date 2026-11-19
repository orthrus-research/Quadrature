package cc.orthrus.quadrature.core.handoff;

import java.util.Locale;

/**
 * The least memory the handoff's four images take for an output size, from their texels, and the bounds
 * past which a size is taken for an error and refused before anything is allocated: a side longer than
 * {@link #LARGEST_SIDE}, more than {@link #MOST_BYTES} in all, or an allocation far beyond its texels. All
 * arithmetic is in {@code long}: a 16,384-pixel square of eight-byte texels is 2 GiB, past {@code int}.
 *
 * @param render the inputs' size
 * @param color  bytes of the color input's texels ({@code RGBA16F})
 * @param depth  bytes of the depth input's texels, at the game's depth format's size
 * @param motion bytes of the motion input's texels ({@code RG16F})
 * @param output bytes of the output's texels ({@code RGBA16F})
 */
public record SharedImageSizes(RenderSize render, int outputWidth, int outputHeight, long color, long depth, long motion,
                               long output) {

    public static final int COLOR_TEXEL = 8;
    public static final int MOTION_TEXEL = 4;
    public static final int OUTPUT_TEXEL = 8;
    /** The longest side handed over; past it a size is wrong, not large. Every supported card allows twice it. */
    public static final int LARGEST_SIDE = 16384;
    /** The most the four images may take together. A 3840x2160 output at full scale needs 190 MiB of texels. */
    public static final long MOST_BYTES = 1L << 30;
    /** How many times its texels an image's allocation may take, for padding and compression metadata. */
    public static final int SLACK = 4;
    /** Allowed beyond the slack, for the smallest images' alignment. */
    public static final long ALIGNMENT_ALLOWANCE = 1L << 20;

    /**
     * The sizes for an output, or an exception naming the size when a side lies outside 1 to
     * {@link #LARGEST_SIDE} pixels.
     *
     * @param depthTexel bytes a texel of the game's depth format takes, 1 to 8
     */
    public static SharedImageSizes of(int outputWidth, int outputHeight, double scale, int depthTexel) {
        checkOutput(outputWidth, outputHeight);
        return of(outputWidth, outputHeight, RenderSize.of(outputWidth, outputHeight, scale), depthTexel);
    }

    private static void checkOutput(int outputWidth, int outputHeight) {
        if (outputWidth < 1 || outputHeight < 1 || outputWidth > LARGEST_SIDE || outputHeight > LARGEST_SIDE) {
            throw new IllegalArgumentException("The handoff refuses an output of " + outputWidth + "x" + outputHeight
                + ": each side must lie in 1 to " + LARGEST_SIDE + " pixels.");
        }
    }

    /**
     * The sizes for an output and a render size someone else chose, the scaler's drawn picture: an exception
     * where the output lies outside 1 to {@link #LARGEST_SIDE} pixels a side or the render size outside 1 to
     * the output's.
     */
    public static SharedImageSizes of(int outputWidth, int outputHeight, RenderSize render, int depthTexel) {
        checkOutput(outputWidth, outputHeight);
        if (render.width() < 1 || render.height() < 1 || render.width() > outputWidth || render.height() > outputHeight) {
            throw new IllegalArgumentException("The handoff refuses a render size of " + render + " for an output of "
                + outputWidth + "x" + outputHeight + ".");
        }
        if (depthTexel < 1 || depthTexel > 8) {
            throw new IllegalArgumentException("A depth texel of " + depthTexel + " bytes is not a depth format's.");
        }
        long inputTexels = (long) render.width() * render.height();
        return new SharedImageSizes(render, outputWidth, outputHeight, inputTexels * COLOR_TEXEL, inputTexels * depthTexel,
            inputTexels * MOTION_TEXEL, (long) outputWidth * outputHeight * OUTPUT_TEXEL);
    }

    public long total() {
        return color + depth + motion + output;
    }

    /** Why the four images may not be made, or null when they may. */
    public String refusal() {
        if (total() > MOST_BYTES) {
            return String.format(Locale.ROOT, "The handoff refuses images of %.1f MiB in all for an output of %dx%d "
                + "(render %s): more than %d MiB.", total() / 1048576.0, outputWidth, outputHeight, render,
                MOST_BYTES >> 20);
        }
        return null;
    }

    /** Why an allocation the driver asks for an image may not be made, or null when it may. */
    public static String allocationRefusal(String image, long allocation, long texelBytes) {
        if (allocation <= 0 || allocation > MOST_BYTES || allocation > SLACK * texelBytes + ALIGNMENT_ALLOWANCE) {
            return String.format(Locale.ROOT, "The handoff refuses the %s image's allocation of %d bytes for %d bytes of "
                + "texels: more than %d times them, or than %d MiB.", image, allocation, texelBytes, SLACK,
                MOST_BYTES >> 20);
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "render %s, output %dx%d: color %.2f, depth %.2f, motion %.2f, output %.2f, "
                + "%.2f MiB of texels in all", render, outputWidth, outputHeight, color / 1048576.0, depth / 1048576.0,
            motion / 1048576.0, output / 1048576.0, total() / 1048576.0);
    }
}
