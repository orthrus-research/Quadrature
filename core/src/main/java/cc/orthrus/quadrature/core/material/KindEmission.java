package cc.orthrus.quadrature.core.material;

/**
 * How strongly a surface gives light of its own, from its kind and its own color: the value a shading
 * stage multiplies the surface's color by. It is worked out for each texel, so that the bright parts
 * of an emitter's texture glow and its dark frame does not.
 *
 * <p>Lava gives a tenth, whatever its color. A dim emitter gives up to 0.65 and a bright one up to
 * 0.90, each rising smoothly with the greatest channel of the surface's linear color between two
 * limits of its own. Every other kind gives nothing, and no kind has a least value.
 *
 * <p>The terrain program's fragment stage works the same rule out in {@link #GLSL}, where the targets
 * for shading are written; a test holds the two to the same numbers.
 */
public final class KindEmission {

    /** The kind of lava. */
    public static final int LAVA = 101;
    /** The kind of a dim emitter. */
    public static final int DIM = 120;
    /** The kind of a bright emitter: lamps and flame. */
    public static final int BRIGHT = 121;

    /** What lava gives, whatever its color. */
    public static final float LAVA_VALUE = 0.10f;
    /** The most a dim emitter gives, and the limits of its greatest channel it rises between. */
    public static final float DIM_MOST = 0.65f;
    public static final float DIM_LOWER = 0.20f;
    public static final float DIM_UPPER = 0.70f;
    /** The most a bright emitter gives, and the limits of its greatest channel it rises between. */
    public static final float BRIGHT_MOST = 0.90f;
    public static final float BRIGHT_LOWER = 0.12f;
    public static final float BRIGHT_UPPER = 0.65f;

    /** The light, of 15, from which a block that gives light and is named by no list counts as a bright emitter. */
    public static final int BRIGHT_FROM_LIGHT = 12;

    /**
     * The rule in GLSL 4.60, as the terrain program's fragment stage includes it: {@code
     * rivenKindEmission(kind, linearAlbedo)}, with the kind as the material table's byte 5 holds it
     * and the linear albedo the texel times the tint, decoded from sRGB. GLSL's {@code smoothstep} is
     * the same curve as {@link #smooth}.
     */
    public static final String GLSL = String.join("\n",
        "float rivenKindEmission(uint kind, vec3 linearAlbedo) {",
        "    float m = max(linearAlbedo.r, max(linearAlbedo.g, linearAlbedo.b));",
        "    if (kind == 101u) { return 0.10; }",
        "    if (kind == 120u) { return 0.65 * smoothstep(0.20, 0.70, m); }",
        "    if (kind == 121u) { return 0.90 * smoothstep(0.12, 0.65, m); }",
        "    return 0.0;",
        "}");

    private KindEmission() {
    }

    /**
     * @param kind the surface's kind
     * @param red  the surface's linear color: its texel times its tint
     * @return the emission, 0 to 0.9
     */
    public static float of(int kind, float red, float green, float blue) {
        float most = Math.max(red, Math.max(green, blue));
        switch (kind) {
            case LAVA:
                return LAVA_VALUE;
            case DIM:
                return DIM_MOST * smooth(DIM_LOWER, DIM_UPPER, most);
            case BRIGHT:
                return BRIGHT_MOST * smooth(BRIGHT_LOWER, BRIGHT_UPPER, most);
            default:
                return 0f;
        }
    }

    /** The emission as a target stores it: of 255, to the nearest. */
    public static int stored(int kind, float red, float green, float blue) {
        return Math.round(of(kind, red, green, blue) * 255f);
    }

    /** The kind of a block that gives light and is named by no list: bright from a light of 12, dim below, none with no light. */
    public static int fallbackKind(int light) {
        return light <= 0 ? MaterialKinds.NONE : light >= BRIGHT_FROM_LIGHT ? BRIGHT : DIM;
    }

    /** 0 below the lower limit, 1 above the upper, and between them t * t * (3 - 2 t) of the share of the way. */
    static float smooth(float lower, float upper, float value) {
        float t = Math.max(0f, Math.min(1f, (value - lower) / (upper - lower)));
        return t * t * (3f - 2f * t);
    }
}
