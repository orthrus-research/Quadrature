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

    /**
     * {@code quadrature.distant.crossfade}: {@code on} (the default) draws two levels over a band
     * where they meet and fades from one to the other, and fades a tile in when it arrives;
     * {@code off} lets each level end on a line, as the first five runs drew.
     */
    static final boolean CROSSFADE = !"off".equals(System.getProperty("quadrature.distant.crossfade", "on"));

    /**
     * {@code quadrature.distant.fill}: {@code on} (the default) has every level also take the tiles
     * inside its own hole and draw them until finer tiles are stored, so that no hole is open
     * while distant terrain arrives; {@code off} leaves each level its ring, as the first seven runs
     * drew.
     */
    static final boolean FILL = !"off".equals(System.getProperty("quadrature.distant.fill", "on"));

    /**
     * {@code quadrature.distant.joinFade}: {@code on} lets distant terrain fade in across near
     * terrain's last blocks, where near terrain fades out, in place of ending where near terrain's
     * columns begin. Off by default, and with no effect unless near terrain says that it fades and
     * over what band ({@code DistantHook.nearFades}): one side alone would draw the band twice or
     * not at all.
     */
    static final boolean JOIN_FADE = "on".equals(System.getProperty("quadrature.distant.joinFade", "off"));

    /**
     * {@code quadrature.distant.joinFadeBand}: {@code start,end} in blocks, for looking at distant
     * terrain's half of the join's fade alone. It takes the band as given whatever near terrain
     * does, so with near terrain not fading it draws distant terrain's pattern over near terrain's
     * whole ground. A debug view, not a look.
     */
    static final float[] JOIN_FADE_DEBUG = band(System.getProperty("quadrature.distant.joinFadeBand"));

    private static float[] band(String setting) {
        if (setting == null) {
            return null;
        }
        try {
            String[] parts = setting.split(",");
            float start = Float.parseFloat(parts[0].trim());
            float end = Float.parseFloat(parts[1].trim());
            return parts.length == 2 && start >= 0 && end > start ? new float[] {start, end} : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * {@code quadrature.distant.order}: {@code near} (the default, and only with fill tiles) builds
     * the coarsest level's fill tiles, then every level from the finest outward; {@code coarse}
     * builds the coarsest level first, as the first seven runs did.
     */
    static final boolean NEAR_FIRST = FILL && !"coarse".equals(System.getProperty("quadrature.distant.order", "near"));

    /** Frames over which a newly stored tile fades in. */
    static final int ARRIVAL_FRAMES = 24;

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
