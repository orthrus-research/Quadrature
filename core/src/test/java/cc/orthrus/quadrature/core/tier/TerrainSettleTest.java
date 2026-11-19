package cc.orthrus.quadrature.core.tier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TerrainSettleTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void aPlaceStillArrivingIsNotCaughtUpWhileItsMeshesStandStill() {
        // As at radius 20: the meshes held stay the same between batches while thousands of cubes are still to come.
        TerrainSettle settle = new TerrainSettle();
        int unloaded = 9_600;
        for (int frame = 0; frame < 120 * 12; frame++) {
            if (frame % 240 == 0) {
                unloaded -= 500;
            }
            assertFalse(settle.frame(frame * SECOND / 120, 0, 1_400, true, unloaded));
        }
    }

    @Test
    void aPlaceWhoseSearchMeetsNothingUnloadedIsCaughtUp() {
        TerrainSettle settle = new TerrainSettle();
        settle.frame(0, 0, 4_090, true, 0);
        assertTrue(settle.frame(SECOND / 120, 0, 4_090, true, 0));
        assertFalse(settle.byPlateau());
        // Not while a mesh comes or goes, a cube waits, the place holds nothing or the search was not made.
        assertFalse(settle.frame(2 * SECOND / 120, 0, 4_091, true, 0));
        assertFalse(settle.frame(3 * SECOND / 120, 3, 4_091, true, 0));
        assertFalse(settle.frame(4 * SECOND / 120, 0, 0, true, 0));
        assertFalse(settle.frame(5 * SECOND / 120, 0, 0, false, 0));
    }

    @Test
    void cubesThatNeverComeCountAsCaughtUpOnceTheirCountStandsStill() {
        // A server that loads less than the client's distances: 300 cubes of the box never arrive.
        TerrainSettle settle = new TerrainSettle();
        long t = 0;
        for (; t < 14 * SECOND; t += SECOND / 120) {
            assertFalse(settle.frame(t, 0, 4_000, true, 300), "at " + t / SECOND + " s");
        }
        boolean caught = false;
        for (; t < 16 * SECOND && !caught; t += SECOND / 120) {
            caught = settle.frame(t, 0, 4_000, true, 300);
        }
        assertTrue(caught);
        assertTrue(settle.byPlateau());
        assertEquals(300, settle.unloaded());
    }

    @Test
    void theTimeSinceArrivalStartsAgainAtEachPlace() {
        TerrainSettle settle = new TerrainSettle();
        settle.arrived(10 * SECOND);
        settle.frame(12 * SECOND, 0, 1, true, 5);
        assertEquals(2.5, settle.secondsSinceArrival(12 * SECOND + SECOND / 2), 1e-9);
        settle.arrived(20 * SECOND);
        assertEquals(1.0, settle.secondsSinceArrival(21 * SECOND), 1e-9);
    }
}
