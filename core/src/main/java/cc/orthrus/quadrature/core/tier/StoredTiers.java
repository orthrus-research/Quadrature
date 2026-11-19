package cc.orthrus.quadrature.core.tier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The benchmark's stored result: the key it was measured under, the tiers chosen, the figures they were
 * chosen from, and the player's overrides, as {@code name = value} lines. The GPU and the driver are
 * kept as digests only ({@link TierBenchmark#gpuDigest}).
 *
 * @param figures the result's figures by name, for the record; tiers are chosen again from a new run
 * @param overrides the player's tier for any group
 */
public record StoredTiers(int benchmark, String key, String gpu, String driver, int outputWidth, int outputHeight,
                          Tier speed, Tier memory, Map<String, String> figures, Map<TierGroup, Tier> overrides) {

    /** The first line of the file, naming its format. */
    public static final String HEADER = "Quadrature tier benchmark, format 1";

    public StoredTiers {
        figures = Collections.unmodifiableMap(new LinkedHashMap<>(figures));
        overrides = overrides.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(overrides));
    }

    /** Whether the benchmark is to run: nothing is stored, or it was stored under another key. */
    public static boolean needsRun(StoredTiers stored, String key) {
        return stored == null || !stored.key.equals(key);
    }

    /** The speed tier chosen last on this GPU, for the band; null on another GPU or with nothing stored. */
    public static Tier previousSpeed(StoredTiers stored, String gpu) {
        return stored != null && stored.gpu.equals(gpu) ? stored.speed : null;
    }

    /** The memory tier chosen last on this GPU, for the band; null on another GPU or with nothing stored. */
    public static Tier previousMemory(StoredTiers stored, String gpu) {
        return stored != null && stored.gpu.equals(gpu) ? stored.memory : null;
    }

    /** The same record with other overrides, kept when the benchmark runs again. */
    public StoredTiers withOverrides(Map<TierGroup, Tier> replaced) {
        return new StoredTiers(benchmark, key, gpu, driver, outputWidth, outputHeight, speed, memory, figures, replaced);
    }

    public byte[] text() {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        lines.add("benchmark = " + benchmark);
        lines.add("key = " + key);
        lines.add("gpu = " + gpu);
        lines.add("driver = " + driver);
        lines.add("output = " + outputWidth + "x" + outputHeight);
        lines.add("speed tier = " + speed);
        lines.add("memory tier = " + memory);
        figures.forEach((name, value) -> lines.add("figure " + name + " = " + value));
        overrides.forEach((group, tier) -> lines.add("override " + group.key() + " = " + tier));
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads a stored result. Lines it does not know are skipped, and an override of a group or tier it does
     * not know is dropped; a file of another format, or one missing the key, the digests or a tier, gives
     * null, which means the benchmark runs again.
     */
    public static StoredTiers parse(byte[] bytes) {
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\r?\n");
        if (lines.length == 0 || !lines[0].equals(HEADER)) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> figures = new LinkedHashMap<>();
        Map<TierGroup, Tier> overrides = new EnumMap<>(TierGroup.class);
        for (int i = 1; i < lines.length; i++) {
            int equals = lines[i].indexOf(" = ");
            if (equals < 0) {
                continue;
            }
            String name = lines[i].substring(0, equals).trim();
            String value = lines[i].substring(equals + 3).trim();
            if (name.startsWith("figure ")) {
                figures.put(name.substring("figure ".length()), value);
            } else if (name.startsWith("override ")) {
                TierGroup group = TierGroup.byKey(name.substring("override ".length()));
                Tier tier = Tier.parse(value);
                if (group != null && tier != null) {
                    overrides.put(group, tier);
                }
            } else {
                values.put(name, value);
            }
        }
        try {
            int benchmark = Integer.parseInt(values.get("benchmark"));
            String[] output = values.get("output").split("x");
            Tier speed = Tier.parse(values.get("speed tier"));
            Tier memory = Tier.parse(values.get("memory tier"));
            String key = values.get("key");
            String gpu = values.get("gpu");
            String driver = values.get("driver");
            if (speed == null || memory == null || key == null || gpu == null || driver == null) {
                return null;
            }
            return new StoredTiers(benchmark, key, gpu, driver, Integer.parseInt(output[0]), Integer.parseInt(output[1]),
                speed, memory, figures, overrides);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
