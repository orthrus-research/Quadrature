package cc.orthrus.quadrature.core.bloom;

import java.util.Locale;

/**
 * The arithmetic of Quadrature's bloom (D27; research R4, 7c), apart from the GL stage that runs it so it can be
 * tested: a bloom kind's two weights, the chain's levels, a halving's thirteen taps, a doubling's tent, and the
 * composite, which gives GTCEu: Reloaded's documented rule from the two weights.
 *
 * <p>The rule is OUT = BG + BLOOM s (b + LT + (1 - Y) (HT - LT)), Y the background's brightness: its HSL lightness,
 * (max + min) / 2 of its channels, as GTCEu: Reloaded's blend computes it (its documentation leaves it undefined). Its factor is
 * g0 - Y (g0 - g1), with g0 = s (b + HT) the weight over black and g1 = s (b + LT) the weight over white, so it is
 * linear in the glow: one chain carries the g0-weighted color and, in a fourth channel, the luma of the
 * (g0 - g1)-weighted color, and the composite takes the ratio of the two back. Where one kind lights a pixel that is
 * the rule exactly; where kinds overlap, the darkening takes the overlap's mean.
 */
public final class BloomChain {

    /** The greatest weight a kind carries; the weight target holds weights as fractions of it. */
    public static final float GREATEST_WEIGHT = 4f;
    /** Six: the sixth and coarsest level carries the far halo GTCEu: Reloaded's wide blurs give, which five left short (P3-V1). */
    public static final int DEFAULT_LEVELS = 6;
    /**
     * What GTCEu: Reloaded's default style multiplies a source of one color by before its blend: its five levels'
     * weights summed (0.2 to 1.0, 3) times the strength it applies there a second time (its kinds' default, 1.5).
     */
    public static final double REFERENCE_RESPONSE = 4.5;
    public static final int LEAST_LEVELS = 2;
    public static final int MOST_LEVELS = 6;
    /** A doubling's tent spans this many texels of the level it reads, at least and at most: wider leaves holes. */
    public static final float LEAST_RADIUS = 0.5f;
    public static final float MOST_RADIUS = 1.5f;
    /** Below this luma the glow's darkening ratio is not formed: there is next to nothing to darken. */
    public static final float LEAST_LUMA = 1e-4f;

    /**
     * A halving's thirteen taps, as offsets in half-texels of the level written from its texel's center: the center,
     * four one step out diagonally, four two steps out along the axes and four two steps out diagonally.
     */
    public static final int[][] TAPS = {
        {0, 0},
        {-1, -1}, {1, -1}, {-1, 1}, {1, 1},
        {-2, 0}, {2, 0}, {0, -2}, {0, 2},
        {-2, -2}, {2, -2}, {-2, 2}, {2, 2}};

    /**
     * The five groups of four taps a halving weighs (Jimenez, SIGGRAPH 2014): the inner four, then one group a
     * quadrant of the center, the two axial taps and the diagonal tap on that side. Indices into {@link #TAPS}.
     */
    public static final int[][] BOXES = {
        {1, 2, 3, 4},
        {0, 5, 7, 9},
        {0, 6, 7, 10},
        {0, 5, 8, 11},
        {0, 6, 8, 12}};
    /** Each group's weight: the inner one half, the four of the quadrants an eighth each. */
    public static final double[] BOX_WEIGHTS = {0.5, 0.125, 0.125, 0.125, 0.125};

    /** A doubling's tent: the weights of its three-by-three reads, row by row. */
    public static final double[] TENT = {
        1 / 16.0, 2 / 16.0, 1 / 16.0,
        2 / 16.0, 4 / 16.0, 2 / 16.0,
        1 / 16.0, 2 / 16.0, 1 / 16.0};

    private BloomChain() {
    }

    /** A kind's weight over black: what GTCEu: Reloaded's rule multiplies the glow by where the picture is black. */
    public static float overBlack(float strength, float base, float highLimit) {
        return clamp(strength * (base + highLimit), 0f, GREATEST_WEIGHT);
    }

    /** A kind's weight over white: what the rule multiplies the glow by where the picture is white. */
    public static float overWhite(float strength, float base, float lowLimit) {
        return clamp(strength * (base + lowLimit), 0f, GREATEST_WEIGHT);
    }

    /** A kind's radius as the tent's span in texels of the level it reads. */
    public static float radius(float radius) {
        return clamp(radius, LEAST_RADIUS, MOST_RADIUS);
    }

    /** The levels asked for, held to the range the chain makes. */
    public static int levels(int requested) {
        return Math.max(LEAST_LEVELS, Math.min(MOST_LEVELS, requested));
    }

    /**
     * A level's weight in the sum, finest first: rising to 1 at the coarsest, as GTCEu: Reloaded's default style weighs
     * its five levels (0.2 to 1.0), so that in the 8-bit picture the glow's light lies in its wide halo rather than in a
     * core that clamps (P3-V1).
     */
    public static double levelWeight(int level, int levels) {
        return (level + 1) / (double) levels;
    }

    /** The levels' weights summed: what a source of one color comes out of the chain multiplied by. */
    public static double levelWeights(int levels) {
        return (levels + 1) / 2.0;
    }

    /**
     * The calibration gain for a chain of so many levels (D27): what brings its response to a source of one color, its
     * levels' weights summed, to GTCEu: Reloaded's ({@link #REFERENCE_RESPONSE}), so that the glow's light matches its
     * own whatever the levels: 1.5 at five, 9/7 at six. P3-V1 measured the gains that match at 1.64 for five levels and
     * 1.28 for six: five fall short where the far halo is missing.
     */
    public static double calibratedGain(int levels) {
        return REFERENCE_RESPONSE / levelWeights(levels);
    }

    /** A side of a level: the output's side halved {@code level + 1} times, each time rounded up. */
    public static int levelSide(int outputSide, int level) {
        int halvings = level + 1;
        return Math.max(1, (outputSide + (1 << halvings) - 1) >> halvings);
    }

    /** Rec. 709 luma: the measure the chain's fourth channel carries the darkening by. */
    public static double luma(double red, double green, double blue) {
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
    }

    /** HSL lightness, (max + min) / 2: the background's brightness in GTCEu: Reloaded's blend. */
    public static double lightness(double red, double green, double blue) {
        return (Math.max(red, Math.max(green, blue)) + Math.min(red, Math.min(green, blue))) / 2;
    }

    /** GTCEu: Reloaded's composite of one kind's glow over a background, for comparison. */
    public static double[] reference(double[] background, double[] glow, float strength, float base, float lowLimit,
        float highLimit) {
        double y = lightness(background[0], background[1], background[2]);
        double factor = strength * (base + lowLimit + (1 - y) * (highLimit - lowLimit));
        return new double[] {background[0] + glow[0] * factor, background[1] + glow[1] * factor,
            background[2] + glow[2] * factor};
    }

    /**
     * The chain's composite: the background plus the glow's weighted color, darkened by the background's lightness times
     * the ratio the fourth channel carries against the weighted color's own luma. Not clamped; the stage's store is.
     */
    public static double[] composite(double[] background, double[] weighted, double darkening) {
        double ratio = Math.min(1, Math.max(0, darkening / Math.max(luma(weighted[0], weighted[1], weighted[2]), LEAST_LUMA)));
        double y = lightness(background[0], background[1], background[2]);
        double factor = 1 - y * ratio;
        return new double[] {background[0] + weighted[0] * factor, background[1] + weighted[1] * factor,
            background[2] + weighted[2] * factor};
    }

    /**
     * Each tap's weight once the groups are summed: an eighth for the center and the inner four, a sixteenth for an
     * axial tap two steps out, a thirty-second for a diagonal one.
     */
    public static double[] tapWeights() {
        double[] weights = new double[TAPS.length];
        for (int box = 0; box < BOXES.length; box++) {
            for (int tap : BOXES[box]) {
                weights[tap] += BOX_WEIGHTS[box] / 4;
            }
        }
        return weights;
    }

    /**
     * GLSL constants and functions for the stage's programs: the taps with their summed weights, the groups and their
     * weights, the tent, the greatest weight, the luma floor, luma and the composite rule, as above.
     */
    public static String glsl() {
        StringBuilder taps = new StringBuilder("const ivec2 TAPS[13] = ivec2[13](");
        for (int i = 0; i < TAPS.length; i++) {
            taps.append(i == 0 ? "" : ", ").append("ivec2(").append(TAPS[i][0]).append(", ").append(TAPS[i][1]).append(')');
        }
        taps.append(");");
        double[] summed = tapWeights();
        StringBuilder tapWeights = new StringBuilder("const float TAP_WEIGHTS[13] = float[13](");
        for (int i = 0; i < summed.length; i++) {
            tapWeights.append(i == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.6f", summed[i]));
        }
        tapWeights.append(");");
        StringBuilder tent = new StringBuilder("const float TENT[9] = float[9](");
        for (int i = 0; i < TENT.length; i++) {
            tent.append(i == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.6f", TENT[i]));
        }
        tent.append(");");
        StringBuilder boxes = new StringBuilder("const ivec4 BOXES[5] = ivec4[5](");
        for (int i = 0; i < BOXES.length; i++) {
            boxes.append(i == 0 ? "" : ", ").append("ivec4(").append(BOXES[i][0]).append(", ").append(BOXES[i][1])
                .append(", ").append(BOXES[i][2]).append(", ").append(BOXES[i][3]).append(')');
        }
        boxes.append(");");
        StringBuilder weights = new StringBuilder("const float BOX_WEIGHTS[5] = float[5](");
        for (int i = 0; i < BOX_WEIGHTS.length; i++) {
            weights.append(i == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.6f", BOX_WEIGHTS[i]));
        }
        weights.append(");");
        return String.join("\n", taps, tapWeights, boxes, weights, tent,
            String.format(Locale.ROOT, "const float GREATEST_WEIGHT = %.6f;", GREATEST_WEIGHT),
            String.format(Locale.ROOT, "const float LEAST_LUMA = %.6e;", LEAST_LUMA),
            "float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }",
            "float lightness(vec3 c) { return (max(c.r, max(c.g, c.b)) + min(c.r, min(c.g, c.b))) * 0.5; }",
            "// The composite: the background plus the weighted glow, darkened by the background's lightness times the",
            "// ratio the fourth channel carries against the weighted glow's own luma (BloomChain.composite).",
            "vec3 composite(vec3 background, vec4 glow) {",
            "    float ratio = clamp(glow.a / max(luma(glow.rgb), LEAST_LUMA), 0.0, 1.0);",
            "    return background + glow.rgb * (1.0 - lightness(background) * ratio);",
            "}");
    }

    private static float clamp(float value, float least, float most) {
        return Math.max(least, Math.min(most, value));
    }
}
