package cc.orthrus.quadrature.core.tier;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The tier each group runs at (D22): a group that holds memory takes the lower of the speed and memory
 * tiers, every other group the speed tier, and the player's choice for a group stands over both.
 */
public final class TierChoice {

    private TierChoice() {
    }

    /**
     * @param overrides the player's tier for any group; groups not named are chosen
     * @return a tier for every group
     */
    public static Map<TierGroup, Tier> choose(Tier speed, Tier memory, Map<TierGroup, Tier> overrides) {
        Map<TierGroup, Tier> chosen = new EnumMap<>(TierGroup.class);
        for (TierGroup group : TierGroup.values()) {
            Tier override = overrides.get(group);
            chosen.put(group, override != null ? override : group.holdsMemory() ? speed.lower(memory) : speed);
        }
        return Collections.unmodifiableMap(chosen);
    }
}
