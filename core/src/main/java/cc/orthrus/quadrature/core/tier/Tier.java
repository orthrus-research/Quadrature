package cc.orthrus.quadrature.core.tier;

import java.util.Locale;

/** The quality tiers, lowest first (decision D22). Each is a preset of per-group settings. */
public enum Tier {
    LOW, MEDIUM, HIGH, MAX;

    /** The lower of this tier and another. */
    public Tier lower(Tier other) {
        return compareTo(other) <= 0 ? this : other;
    }

    /** One step down; the lowest tier stays where it is. */
    public Tier down() {
        return this == LOW ? LOW : values()[ordinal() - 1];
    }

    /** A tier by its name in any case, or null for anything else. */
    public static Tier parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
