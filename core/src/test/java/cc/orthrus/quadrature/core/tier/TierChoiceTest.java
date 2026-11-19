package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import java.util.Map;

final class TierChoiceTest {

    @Test
    void groupsThatHoldMemoryTakeTheLowerTierAndTheRestTheSpeedTier() {
        Map<TierGroup, Tier> chosen = TierChoice.choose(Tier.MAX, Tier.MEDIUM, Map.of());
        assertEquals(Tier.MEDIUM, chosen.get(TierGroup.NEAR_TERRAIN));
        assertEquals(Tier.MEDIUM, chosen.get(TierGroup.DISTANT_REACH));
        assertEquals(Tier.MEDIUM, chosen.get(TierGroup.LIGHT_DEPTH));
        assertEquals(Tier.MAX, chosen.get(TierGroup.RENDER_SCALE));
        assertEquals(Tier.MAX, chosen.get(TierGroup.INDIRECT_LIGHT));
        // Memory never raises a group above the speed tier.
        assertEquals(Tier.LOW, TierChoice.choose(Tier.LOW, Tier.MAX, Map.of()).get(TierGroup.NEAR_TERRAIN));
        assertEquals(TierGroup.values().length, chosen.size());
    }

    @Test
    void thePlayersChoiceStandsOverBoth() {
        Map<TierGroup, Tier> chosen = TierChoice.choose(Tier.LOW, Tier.LOW,
            Map.of(TierGroup.NEAR_TERRAIN, Tier.MAX, TierGroup.SHADOW_FILTER, Tier.HIGH));
        assertEquals(Tier.MAX, chosen.get(TierGroup.NEAR_TERRAIN));
        assertEquals(Tier.HIGH, chosen.get(TierGroup.SHADOW_FILTER));
        assertEquals(Tier.LOW, chosen.get(TierGroup.FOG_AND_CLOUDS));
    }

    @Test
    void tiersStepAndParse() {
        assertEquals(Tier.LOW, Tier.LOW.down());
        assertEquals(Tier.HIGH, Tier.MAX.down());
        assertEquals(Tier.MEDIUM, Tier.HIGH.lower(Tier.MEDIUM));
        assertEquals(Tier.MEDIUM, Tier.MEDIUM.lower(Tier.MAX));
        assertEquals(Tier.HIGH, Tier.parse(" high "));
        assertNull(Tier.parse("ultra"));
        assertNull(Tier.parse(null));
        assertEquals(TierGroup.LIGHT_DEPTH, TierGroup.byKey("lightDepth"));
        assertNull(TierGroup.byKey("lightdepth"));
    }
}
