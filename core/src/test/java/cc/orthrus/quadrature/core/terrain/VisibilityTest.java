package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.orthrus.quadrature.core.view.View;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class VisibilityTest {

    private static final int WEST_EAST = pairOnly(CubeConnectivity.WEST, CubeConnectivity.EAST);

    private final Visibility visibility = new Visibility();

    /** The links that join one pair of faces and no other. */
    private static int pairOnly(int a, int b) {
        for (int links = 1; links < 1 << 15; links <<= 1) {
            if (CubeConnectivity.connected(links, a, b)) {
                return links;
            }
        }
        throw new AssertionError();
    }

    /** A view from the middle of cube 0 0 0 whose volume excludes nothing, reaching two cubes. */
    private static View everywhere(boolean occlusion) {
        float[] identity = new float[16];
        identity[0] = identity[5] = identity[10] = identity[15] = 1;
        return new View().set(View.Purpose.MAIN, View.Depth.CONVENTIONAL, 8, 8, 8, 8, 8, 8, identity,
            new float[16], 2, 2, occlusion);
    }

    /** A world in which every cube has {@code fallback} links except those given. */
    private static Visibility.Cubes world(int fallback, Map<Long, Integer> special) {
        return (x, y, z) -> special.getOrDefault(CubeKey.of(x, y, z), fallback);
    }

    private Set<Long> seen() {
        Set<Long> keys = new HashSet<>();
        for (int i = 0; i < visibility.count(); i++) {
            keys.add(visibility.key(i));
        }
        assertEquals(visibility.count(), keys.size(), "a cube was listed twice");
        return keys;
    }

    @Test
    void openCubesAreAllSeenOutToTheReach() {
        assertEquals(125, visibility.search(world(CubeConnectivity.ALL, Map.of()), everywhere(true)));
        assertEquals(CubeKey.of(0, 0, 0), visibility.key(0));
    }

    @Test
    void aSealedRoomShowsItsWallsAndNothingBeyond() {
        Map<Long, Integer> special = new HashMap<>();
        special.put(CubeKey.of(0, 0, 0), (int) CubeConnectivity.ALL);
        assertEquals(7, visibility.search(world(CubeConnectivity.NONE, special), everywhere(true)));
        Set<Long> seen = seen();
        assertTrue(seen.contains(CubeKey.of(1, 0, 0)));
        assertFalse(seen.contains(CubeKey.of(2, 0, 0)));
        assertFalse(seen.contains(CubeKey.of(1, 1, 0)));
    }

    @Test
    void aTunnelIsFollowedAndItsWallsAreNot() {
        Map<Long, Integer> special = new HashMap<>();
        special.put(CubeKey.of(0, 0, 0), (int) CubeConnectivity.ALL);
        special.put(CubeKey.of(1, 0, 0), WEST_EAST);
        assertEquals(8, visibility.search(world(CubeConnectivity.NONE, special), everywhere(true)));
        Set<Long> seen = seen();
        assertTrue(seen.contains(CubeKey.of(2, 0, 0)));
        assertFalse(seen.contains(CubeKey.of(-2, 0, 0)));
        assertFalse(seen.contains(CubeKey.of(1, 1, 1)));
    }

    @Test
    void theSearchPassesThroughCubesThatHoldNothing() {
        // Only the far cube is solid; everything between is open and is passed through.
        Map<Long, Integer> special = new HashMap<>();
        special.put(CubeKey.of(2, 0, 0), (int) CubeConnectivity.NONE);
        visibility.search(world(CubeConnectivity.ALL, special), everywhere(true));
        assertTrue(seen().contains(CubeKey.of(2, 0, 0)));
    }

    @Test
    void anUnloadedCubeIsPassedThroughAndNotListed() {
        // Everything is solid but the eye's cube, so the far cube is reached only through the gap.
        Map<Long, Integer> special = new HashMap<>();
        special.put(CubeKey.of(0, 0, 0), (int) CubeConnectivity.ALL);
        special.put(CubeKey.of(1, 0, 0), -1);
        visibility.search(world(CubeConnectivity.NONE, special), everywhere(true));
        Set<Long> seen = seen();
        assertFalse(seen.contains(CubeKey.of(1, 0, 0)));
        assertTrue(seen.contains(CubeKey.of(2, 0, 0)));
        assertTrue(seen.contains(CubeKey.of(1, 1, 0)));
        assertFalse(seen.contains(CubeKey.of(2, 2, 0)));
    }

    @Test
    void aSearchThroughUnloadedCubesIsBoundedByTheReach() {
        // Nothing is loaded but two cubes: one at the edge of the reach, one just beyond it.
        Map<Long, Integer> special = new HashMap<>();
        special.put(CubeKey.of(2, 2, -2), (int) CubeConnectivity.NONE);
        special.put(CubeKey.of(3, 0, 0), (int) CubeConnectivity.NONE);
        assertEquals(1, visibility.search(world(-1, special), everywhere(true)));
        assertEquals(CubeKey.of(2, 2, -2), visibility.key(0));
    }

    @Test
    void aSearchStartsFromAnEyeCubeThatIsNotLoaded() {
        Map<Long, Integer> special = new HashMap<>();
        special.put(CubeKey.of(0, 0, 0), -1);
        assertEquals(124, visibility.search(world(CubeConnectivity.ALL, special), everywhere(true)));
        assertFalse(seen().contains(CubeKey.of(0, 0, 0)));
    }

    @Test
    void anEyeOutsideTheReachIsReportedAndNothingIsListed() {
        float[] identity = new float[16];
        identity[0] = identity[5] = identity[10] = identity[15] = 1;
        View view = new View().set(View.Purpose.MAIN, View.Depth.CONVENTIONAL, 8, 8, 8, 8, 8 + 16 * 5, 8, identity,
            new float[16], 2, 2, true);
        assertEquals(-1, visibility.search(world(CubeConnectivity.ALL, Map.of()), view));
        assertEquals(0, visibility.count());
    }

    @Test
    void aCubeEnteredTwoWaysIsLeftByWhatEitherAllows() {
        // Cube 1 1 0 joins west and east only. It is entered from below, which leads nowhere, and from
        // the west, which leads east.
        Map<Long, Integer> special = new HashMap<>();
        special.put(CubeKey.of(0, 0, 0), (int) CubeConnectivity.ALL);
        special.put(CubeKey.of(1, 0, 0), (int) CubeConnectivity.ALL);
        special.put(CubeKey.of(0, 1, 0), (int) CubeConnectivity.ALL);
        special.put(CubeKey.of(1, 1, 0), WEST_EAST);
        visibility.search(world(CubeConnectivity.NONE, special), everywhere(true));
        Set<Long> seen = seen();
        assertTrue(seen.contains(CubeKey.of(2, 1, 0)));
        assertFalse(seen.contains(CubeKey.of(1, 2, 0)) && !seen.contains(CubeKey.of(0, 2, 0)));
    }

    @Test
    void withoutOcclusionSolidCubesHideNothing() {
        assertEquals(125, visibility.search(world(CubeConnectivity.NONE, Map.of()), everywhere(false)));
    }

    @Test
    void theSearchNeverDoublesBack() {
        // Only a passage that leaves east, climbs, and returns west above the eye could reach 0 2 0.
        Map<Long, Integer> special = new HashMap<>();
        for (int[] cube : new int[][] {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {1, 2, 0}}) {
            special.put(CubeKey.of(cube[0], cube[1], cube[2]), (int) CubeConnectivity.ALL);
        }
        visibility.search(world(CubeConnectivity.NONE, special), everywhere(true));
        Set<Long> seen = seen();
        assertTrue(seen.contains(CubeKey.of(1, 2, 0)));
        assertTrue(seen.contains(CubeKey.of(2, 2, 0)));
        assertFalse(seen.contains(CubeKey.of(0, 2, 0)));
    }

    @Test
    void aSweepKeepsWhatIsWithinReach() {
        Visibility.CubeSet cubes = visitor -> {
            visitor.cube(0, 0, 0);
            visitor.cube(2, -2, 2);
            visitor.cube(3, 0, 0);
            visitor.cube(0, -3, 0);
        };
        assertEquals(2, visibility.sweep(cubes, everywhere(true)));
        assertEquals(CubeKey.of(2, -2, 2), visibility.key(1));
    }

    @Test
    void aSearchCanFollowASweepOnTheSameInstance() {
        visibility.sweep(visitor -> visitor.cube(0, 0, 0), everywhere(true));
        assertEquals(125, visibility.search(world(CubeConnectivity.ALL, Map.of()), everywhere(true)));
    }
}
