package cc.orthrus.quadrature.core.tier;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What each group's tier sets (ARCHITECTURE 12.1), as the system properties the parts built so far read when they
 * start. Only the groups that are built are given: the render scale and the temporal pass, distant terrain's reach and
 * the light's near layer. Near terrain's radius is the game's own render distance and Cubature's vertical distance,
 * which stay the player's; the other groups are not built.
 */
public final class TierSettings {

    /** Distant terrain's reach, in blocks a ring, by tier (ARCHITECTURE 12.1). */
    static final int[] DISTANT_REACH = {256, 384, 512, 768};

    /** The stored figure the chosen render scales are kept under, lowest tier first. */
    public static final String SCALES_FIGURE = "render scales";

    private TierSettings() {
    }

    /**
     * The properties a choice sets.
     *
     * @param scales each tier's render scale as the selection chose it ({@link TierSelection.Outcome#scales}), or null
     *     for each tier's own
     */
    public static Map<String, String> properties(Map<TierGroup, Tier> choice, double[] scales) {
        Map<String, String> properties = new LinkedHashMap<>();
        Tier render = choice.get(TierGroup.RENDER_SCALE);
        if (render != null) {
            double scale = scales != null && scales.length == Tier.values().length ? scales[render.ordinal()]
                : TierBenchmark.renderScale(render);
            // Every tier draws through the temporal pass; below the output, through the scaler too.
            properties.put("quadrature.taa", "true");
            if (scale < 1) {
                properties.put("quadrature.scale", String.format(Locale.ROOT, "%.2f", scale));
            }
        }
        Tier reach = choice.get(TierGroup.DISTANT_REACH);
        if (reach != null) {
            properties.put("quadrature.distant.reach", Integer.toString(DISTANT_REACH[reach.ordinal()]));
        }
        Tier light = choice.get(TierGroup.LIGHT_DEPTH);
        if (light != null) {
            // HIGH and MAX draw the near layer beside the wide one; LOW and MEDIUM the wide one alone.
            properties.put("quadrature.shadow.near", Boolean.toString(light.ordinal() >= Tier.HIGH.ordinal()));
        }
        return properties;
    }

    /** The stored scales figure for an outcome's scales. */
    public static String scalesFigure(double[] scales) {
        StringBuilder text = new StringBuilder();
        for (double scale : scales) {
            text.append(text.length() == 0 ? "" : " ").append(String.format(Locale.ROOT, "%.2f", scale));
        }
        return text.toString();
    }

    /** The scales a stored figure names, or null where it names none or is malformed. */
    public static double[] scales(String figure) {
        if (figure == null) {
            return null;
        }
        String[] parts = figure.trim().split("\\s+");
        if (parts.length != Tier.values().length) {
            return null;
        }
        double[] scales = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                scales[i] = Double.parseDouble(parts[i]);
                if (!(scales[i] > 0 && scales[i] <= 1)) {
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return scales;
    }
}
