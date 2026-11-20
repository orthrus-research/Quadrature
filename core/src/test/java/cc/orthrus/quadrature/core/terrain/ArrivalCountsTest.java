package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ArrivalCountsTest {

    @Test
    void everythingHeldLeavesNothingMissing() {
        ArrivalCounts counts = new ArrivalCounts();
        counts.count(3, -2, 7, 12, 12, key -> true);
        for (int band = 0; band < ArrivalCounts.BANDS; band++) {
            assertEquals(0, counts.missing(band, 0));
            assertEquals(0, counts.missing(band, 1));
        }
    }

    @Test
    void bandsSplitByHeightAgainstThePlayer() {
        // Only the cubes at and above the player's level are held.
        ArrivalCounts counts = new ArrivalCounts();
        counts.count(0, 5, 0, 12, 12, key -> CubeKey.y(key) >= 4);
        assertEquals(0, counts.missing(ArrivalCounts.LEVEL, 0));
        assertEquals(0, counts.missing(ArrivalCounts.ABOVE, 1));
        assertEquals(counts.counted(ArrivalCounts.BELOW, 0), counts.missing(ArrivalCounts.BELOW, 0));
        assertEquals(counts.counted(ArrivalCounts.BELOW, 1), counts.missing(ArrivalCounts.BELOW, 1));
        // The level is three cubes high, above and below ten each, with a reach and height of 11.
        assertEquals(3 * 81, counts.counted(ArrivalCounts.LEVEL, 0));
        assertEquals(10 * 81, counts.counted(ArrivalCounts.ABOVE, 0));
        assertEquals(10 * 81, counts.counted(ArrivalCounts.BELOW, 0));
    }

    @Test
    void theReachIsRoundAndTheNearRingSquare() {
        ArrivalCounts counts = new ArrivalCounts();
        counts.count(0, 0, 0, 12, 12, key -> true);
        int disc = 0;
        int far = 0;
        for (int dz = -11; dz <= 11; dz++) {
            for (int dx = -11; dx <= 11; dx++) {
                if (dx * dx + dz * dz <= 121) {
                    disc++;
                    far += Math.max(Math.abs(dx), Math.abs(dz)) > 4 ? 1 : 0;
                }
            }
        }
        assertEquals(3 * (disc - far), counts.counted(ArrivalCounts.LEVEL, 0));
        assertEquals(3 * far, counts.counted(ArrivalCounts.LEVEL, 1));
    }
}
