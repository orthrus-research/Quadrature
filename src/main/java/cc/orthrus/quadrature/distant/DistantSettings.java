package cc.orthrus.quadrature.distant;

/**
 * Distant terrain's settings, read once from system properties. It names nothing of the game's or
 * Cubature's, so the hook and the Mixins can read it before distant terrain's own classes load.
 */
final class DistantSettings {

    /** {@code quadrature.distant.reach}: how far ring 0 reaches, in blocks; a multiple of 128. */
    static final int REACH = Integer.getInteger("quadrature.distant.reach", 384);

    /** {@code quadrature.distant.levels}: how many levels; 7 reach 24.5 km with a reach of 384. */
    static final int LEVELS = Integer.getInteger("quadrature.distant.levels", 7);

    /**
     * {@code quadrature.distant.fog}: {@code own} (the default) gives near and distant terrain one
     * distance fog; {@code game} leaves the game's fog on near terrain and none on distant terrain,
     * as the first two runs drew.
     */
    static final boolean FOG = !"game".equals(System.getProperty("quadrature.distant.fog", "own"));

    /**
     * {@code quadrature.distant.fogStart}: where the shared fog begins, in blocks from the eye. By
     * default ring 0's reach: everything at full detail, near terrain included at any usual view
     * distance, is clear.
     */
    static final float FOG_START = number("quadrature.distant.fogStart", REACH);

    /**
     * {@code quadrature.distant.fogEnd}: where the shared fog is complete. By default the reach of
     * the outermost ring, so the last tiles fade into the horizon instead of ending on an edge.
     */
    static final float FOG_END = Math.max(FOG_START + 1f,
        number("quadrature.distant.fogEnd", (float) ((long) REACH << Math.max(0, LEVELS - 1))));

    /**
     * {@code quadrature.distant.detail}: {@code images} (the default) draws level 0's faces with
     * their materials' images near the join; {@code flat} draws flat colors everywhere, as the
     * first four runs did.
     */
    static final boolean DETAIL = !"flat".equals(System.getProperty("quadrature.distant.detail", "images"));

    /**
     * {@code quadrature.distant.detailStart}: up to this distance from the eye, in blocks, a level 0
     * face shows its image whole. By default 224: past the near box at the usual view distances, so
     * the change from near terrain's images is not a change to flat color.
     */
    static final float DETAIL_START = number("quadrature.distant.detailStart", 224f);

    /**
     * {@code quadrature.distant.detailEnd}: from this distance on, a face shows its flat color only.
     * By default 352, inside ring 0's reach, so level 1 never needs images and no image ends on a
     * level's edge.
     */
    static final float DETAIL_END = Math.max(DETAIL_START + 1f, number("quadrature.distant.detailEnd", 352f));

    static final int DEBUG_NONE = 0;
    /** Magenta where the near box holds distant ground and near terrain drew nothing in front of it. */
    static final int DEBUG_BOX = 1;
    /** Every face takes a color for its tile's level, and no fog. */
    static final int DEBUG_LEVEL = 2;

    /** {@code quadrature.distant.debug}: {@code box}, {@code level} or unset. */
    static final int DEBUG = debug(System.getProperty("quadrature.distant.debug", ""));

    private DistantSettings() {
    }

    private static float number(String property, float otherwise) {
        String value = System.getProperty(property);
        if (value == null) {
            return otherwise;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException malformed) {
            return otherwise;
        }
    }

    private static int debug(String value) {
        return "box".equals(value) ? DEBUG_BOX : "level".equals(value) ? DEBUG_LEVEL : DEBUG_NONE;
    }
}
