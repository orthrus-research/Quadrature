package cc.orthrus.quadrature.core.fade;

/**
 * The one pattern by which two things drawn over the same pixels share them: near terrain and
 * distant terrain across the near join, two levels of distant terrain across a band, a tile
 * arriving over the ground it replaces. One side is the primary and shows {@code fade} of its
 * pixels, 0 to 1; the other is its complement and shows the rest. Asked with the same pixel, fade
 * and frame, exactly one of the two keeps the pixel, whatever the fade.
 *
 * <p>The pattern is ordered, 4 by 4, from the pixel's place in the window, so it does not crawl over
 * a still frame. A stage that accumulates over frames passes the frame's number: the pattern then
 * moves through its 16 places, one a frame, and each pixel shows the primary in as many of 16 frames
 * as it has pixels of 16. Without such a stage the frame is 0.
 *
 * <p>The same rule is given twice, as {@link #keeps} for Java and as {@link #glsl} for a fragment
 * stage, from the same sixteen numbers. A threshold is {@code (n + 0.5) / 16} for n in 0 to 15: a
 * sum of powers of two, so Java and a shader compare the same floats.
 */
public final class FadePattern {

    /** The side of the pattern, in pixels. */
    public static final int SIDE = 4;

    /** The name of the function {@link #glsl} declares. */
    public static final String GLSL_FUNCTION = "quadratureFadeKeeps";

    /**
     * Ordered so that any fade's pixels are spread as evenly as 16 allow (Bayer's 4 by 4 index
     * matrix), by row.
     */
    private static final int[] ORDER = {
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5,
    };

    private FadePattern() {
    }

    /** The threshold of a place in the pattern: {@code (n + 0.5) / 16}, strictly between 0 and 1. */
    public static float threshold(int x, int y) {
        return (ORDER[(y & 3) * SIDE + (x & 3)] + 0.5f) / 16f;
    }

    /**
     * Whether a side keeps a pixel.
     *
     * @param x          the pixel's column in the window, from 0
     * @param y          the pixel's row in the window, from 0
     * @param fade       how much of the primary side shows, 0 for none to 1 for all
     * @param complement false for the primary side, true for the other
     * @param frame      the frame's number for a stage that accumulates over frames, otherwise 0
     */
    public static boolean keeps(int x, int y, float fade, boolean complement, int frame) {
        boolean primary = threshold(x + (frame & 3), y + (frame >> 2 & 3)) < fade;
        return complement != primary;
    }

    /**
     * The same rule for a fragment stage: a constant array and one function, to be placed before
     * {@code main}.
     *
     * <pre>
     * bool quadratureFadeKeeps(vec2 window, float fade, bool complement, int frame)
     * </pre>
     *
     * {@code window} is {@code gl_FragCoord.xy}; the other arguments are {@link #keeps}'s. A stage
     * discards the fragment when it returns false. It needs GLSL 1.30 or later.
     */
    public static String glsl() {
        StringBuilder text = new StringBuilder("const float QUADRATURE_FADE_THRESHOLD[16] = float[16](");
        for (int i = 0; i < ORDER.length; i++) {
            text.append(i == 0 ? "" : ", ").append((ORDER[i] + 0.5f) / 16f);
        }
        text.append(");\n");
        text.append("bool ").append(GLSL_FUNCTION).append("(vec2 window, float fade, bool complement, int frame) {\n");
        text.append("    ivec2 place = (ivec2(window) + ivec2(frame & 3, (frame >> 2) & 3)) & 3;\n");
        text.append("    bool primary = QUADRATURE_FADE_THRESHOLD[place.y * 4 + place.x] < fade;\n");
        text.append("    return complement != primary;\n");
        text.append("}\n");
        return text.toString();
    }
}
