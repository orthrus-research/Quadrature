package cc.orthrus.quadrature.asm;

import cc.orthrus.quadrature.core.tier.StoredTiers;
import cc.orthrus.quadrature.core.tier.Tier;
import cc.orthrus.quadrature.core.tier.TierChoice;
import cc.orthrus.quadrature.core.tier.TierGroup;
import cc.orthrus.quadrature.core.tier.TierSettings;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Applies the stored tiers before the game's classes load (PLAN item 5d), on the first-launch path
 * ({@code -Dquadrature.tierBench.firstLaunch=true}): the parts they set read their settings once, when they start,
 * and the scaler starts with the game's first framebuffer, before any mod event. The tiers are those chosen last; the
 * benchmark checks the GPU and driver when the first screen opens and, where they changed, runs again, and its tiers
 * apply from the next launch. A setting given on the command line stands over the tier's, as does the player's tier for a
 * group ({@code quadrature.tier.<group>}, or the stored file's override lines).
 */
final class TierStartup {

    private static final Logger LOGGER = LogManager.getLogger("Quadrature");
    private static final String FILE = "quadrature-tiers.txt";

    private TierStartup() {
    }

    static void apply(File gameDir) {
        if (!Boolean.getBoolean("quadrature.tierBench.firstLaunch") || gameDir == null) {
            return;
        }
        Path source = gameDir.toPath().resolve(FILE);
        StoredTiers stored;
        try {
            if (!Files.exists(source)) {
                LOGGER.info("Tiers at startup: nothing stored yet; the benchmark chooses them when the first screen opens.");
                return;
            }
            stored = StoredTiers.parse(Files.readAllBytes(source));
        } catch (Exception e) {
            LOGGER.warn("Tiers at startup: {} could not be read; the parts start with their own settings.", FILE, e);
            return;
        }
        Map<TierGroup, Tier> overrides = new EnumMap<>(TierGroup.class);
        overrides.putAll(stored.overrides());
        for (TierGroup group : TierGroup.values()) {
            Tier tier = Tier.parse(System.getProperty("quadrature.tier." + group.key()));
            if (tier != null) {
                overrides.put(group, tier);
            }
        }
        Map<TierGroup, Tier> choice = TierChoice.choose(stored.speed(), stored.memory(), overrides);
        Map<String, String> settings = TierSettings.properties(choice,
            TierSettings.scales(stored.figures().get(TierSettings.SCALES_FIGURE)));
        StringBuilder applied = new StringBuilder();
        StringBuilder kept = new StringBuilder();
        for (Map.Entry<String, String> setting : settings.entrySet()) {
            String given = System.getProperty(setting.getKey());
            if (given == null) {
                System.setProperty(setting.getKey(), setting.getValue());
                applied.append(' ').append(setting.getKey()).append('=').append(setting.getValue());
            } else {
                kept.append(' ').append(setting.getKey()).append('=').append(given);
            }
        }
        LOGGER.info("Tiers at startup, as chosen last (speed {}, memory {}; by group {}): set{}; given on the command "
            + "line and kept:{}", stored.speed(), stored.memory(), choice, applied.length() == 0 ? " nothing" : applied,
            kept.length() == 0 ? " nothing" : kept);
    }
}
