package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

class TierSettingsTest {

    @Test
    void eachTierSetsTheValuesOfItsColumn() {
        Map<String, String> low = TierSettings.properties(TierChoice.choose(Tier.LOW, Tier.LOW, Map.of()), null);
        assertEquals("true", low.get("quadrature.taa"));
        assertEquals("0.67", low.get("quadrature.scale"));
        assertEquals("256", low.get("quadrature.distant.reach"));
        assertEquals("false", low.get("quadrature.shadow.near"));
        Map<String, String> high = TierSettings.properties(TierChoice.choose(Tier.HIGH, Tier.MAX, Map.of()), null);
        assertEquals("true", high.get("quadrature.taa"));
        assertFalse(high.containsKey("quadrature.scale"), "HIGH draws at the output's resolution");
        assertEquals("512", high.get("quadrature.distant.reach"));
        assertEquals("true", high.get("quadrature.shadow.near"));
    }

    @Test
    void theMemoryTierHoldsDownTheGroupsThatHoldMemory() {
        // Speed MAX, memory MEDIUM: the reach and the light's layers hold memory and take MEDIUM's values.
        Map<String, String> chosen = TierSettings.properties(TierChoice.choose(Tier.MAX, Tier.MEDIUM, Map.of()), null);
        assertEquals("384", chosen.get("quadrature.distant.reach"));
        assertEquals("false", chosen.get("quadrature.shadow.near"));
        assertFalse(chosen.containsKey("quadrature.scale"));
    }

    @Test
    void theSelectionsScalesStandOverEachTiersOwn() {
        // LOW drawn natively, as the selection may choose where the scaler costs more than it saves.
        double[] scales = {1.0, 0.77, 1.0, 1.0};
        Map<String, String> low = TierSettings.properties(TierChoice.choose(Tier.LOW, Tier.LOW, Map.of()), scales);
        assertFalse(low.containsKey("quadrature.scale"));
        assertEquals("true", low.get("quadrature.taa"));
    }

    @Test
    void theScalesFigureReadsBackAndRefusesWhatItCannotRead() {
        double[] scales = {0.67, 0.77, 1.0, 1.0};
        assertArrayEquals(scales, TierSettings.scales(TierSettings.scalesFigure(scales)), 1e-12);
        assertNull(TierSettings.scales(null));
        assertNull(TierSettings.scales("0.67 0.77 1.00"));
        assertNull(TierSettings.scales("0.67 0.77 1.00 x"));
        assertNull(TierSettings.scales("0.67 0.77 1.00 1.50"));
    }
}
