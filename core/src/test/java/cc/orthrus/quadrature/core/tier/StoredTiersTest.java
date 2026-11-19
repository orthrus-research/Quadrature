package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class StoredTiersTest {

    private static StoredTiers stored() {
        Map<String, String> figures = new LinkedHashMap<>();
        figures.put("bandwidth small GB/s", "301.2");
        figures.put("score", "1.000");
        return new StoredTiers(TierBenchmark.VERSION, "0a1b2c3d", "11112222", "33334444", 2560, 1440, Tier.HIGH,
            Tier.MAX, figures, Map.of(TierGroup.NEAR_TERRAIN, Tier.MEDIUM));
    }

    @Test
    void aStoredResultReadsBackAsWritten() {
        StoredTiers read = StoredTiers.parse(stored().text());
        assertEquals(stored(), read);
        assertEquals("301.2", read.figures().get("bandwidth small GB/s"));
        assertEquals(Tier.MEDIUM, read.overrides().get(TierGroup.NEAR_TERRAIN));
    }

    @Test
    void aFileOfAnotherFormatOrMissingATierIsNotAResult() {
        assertNull(StoredTiers.parse("Quadrature tier benchmark, format 2\nkey = x\n".getBytes(StandardCharsets.UTF_8)));
        String text = new String(stored().text(), StandardCharsets.UTF_8).replace("speed tier = HIGH", "speed tier = ultra");
        assertNull(StoredTiers.parse(text.getBytes(StandardCharsets.UTF_8)));
        assertNull(StoredTiers.parse(new byte[0]));
    }

    @Test
    void unknownLinesAndOverridesAreDropped() {
        String text = new String(stored().text(), StandardCharsets.UTF_8)
            + "override noSuchGroup = LOW\noverride shadowFilter = ultra\nsomething else\n";
        StoredTiers read = StoredTiers.parse(text.getBytes(StandardCharsets.UTF_8));
        assertEquals(Map.of(TierGroup.NEAR_TERRAIN, Tier.MEDIUM), read.overrides());
    }

    @Test
    void theBenchmarkRunsAgainUnderAnotherKeyAndTheBandHoldsOnTheSameGpu() {
        StoredTiers stored = stored();
        assertTrue(StoredTiers.needsRun(null, "0a1b2c3d"));
        assertFalse(StoredTiers.needsRun(stored, "0a1b2c3d"));
        assertTrue(StoredTiers.needsRun(stored, "ffffffff"));
        // A new driver changes the key, not the GPU: the last tiers still set the band.
        assertEquals(Tier.HIGH, StoredTiers.previousSpeed(stored, "11112222"));
        assertEquals(Tier.MAX, StoredTiers.previousMemory(stored, "11112222"));
        assertNull(StoredTiers.previousSpeed(stored, "99999999"));
        assertNull(StoredTiers.previousMemory(null, "11112222"));
    }

    @Test
    void theKeyChangesWithTheGpuOrTheDriverAndHoldsNoIdentifier() {
        byte[] uuid = new byte[16];
        uuid[0] = 0x42;
        String gpu = TierBenchmark.gpuDigest("NVIDIA Corporation", "NVIDIA GeForce RTX 3060/PCIe/SSE2", uuid);
        String driver = TierBenchmark.driverDigest("4.6.0 NVIDIA 610.88", uuid);
        String newer = TierBenchmark.driverDigest("4.6.0 NVIDIA 612.01", uuid);
        assertEquals(8, gpu.length());
        assertTrue(gpu.matches("[0-9a-f]{8}"));
        assertNotEquals(TierBenchmark.key(gpu, driver), TierBenchmark.key(gpu, newer));
        assertEquals(TierBenchmark.key(gpu, driver), TierBenchmark.key(gpu, driver));
        assertNotEquals(gpu, TierBenchmark.gpuDigest("NVIDIA Corporation", "NVIDIA GeForce RTX 3060/PCIe/SSE2", null));
        assertFalse(new String(stored().text(), StandardCharsets.UTF_8).contains("3060"));
    }
}
