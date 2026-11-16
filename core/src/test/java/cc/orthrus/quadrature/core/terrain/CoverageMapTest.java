package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class CoverageMapTest {

    private static final int MAX = 100;

    /** A box of five columns a side and five cubes high about cube (10, 4, 20). */
    private static CoverageMap begun() {
        CoverageMap map = new CoverageMap();
        map.begin(10, 4, 20, 2, 2);
        return map;
    }

    private static void column(CoverageMap map, int x, int z, int topY, byte... fromTheTopDown) {
        for (int i = 0; i < fromTheTopDown.length; i++) {
            if (fromTheTopDown[i] != 0) {
                map.cube(x, topY - i, z, fromTheTopDown[i]);
            }
        }
    }

    @Test
    void aColumnWithNothingHeldIsNotLoaded() {
        CoverageMap map = begun();
        map.finish(MAX);
        assertEquals(CoverageMap.NOT_LOADED, map.state(10, 20));
        assertEquals(25, map.count(CoverageMap.NOT_LOADED));
        assertEquals(CoverageMap.NONE, map.surfaceCubeY(10, 20));
        assertEquals(5, map.side());
    }

    @Test
    void theSurfaceIsTheFirstCubeUnderKnownAir() {
        CoverageMap map = begun();
        // Air, air, ground meshed, cube beneath meshed and drawing nothing.
        column(map, 10, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_MESHED,
            CoverageMap.CUBE_EMPTY);
        // The same with the ground cube still waiting to be meshed.
        column(map, 11, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_HELD, CoverageMap.CUBE_MESHED);
        // The ground meshed and the cube beneath it waiting.
        column(map, 12, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_MESHED, CoverageMap.CUBE_HELD);
        map.finish(MAX);
        assertEquals(CoverageMap.SURFACE_MESHED, map.state(10, 20));
        assertEquals(4, map.surfaceCubeY(10, 20));
        assertEquals(CoverageMap.SURFACE_WAITING, map.state(11, 20));
        assertEquals(5, map.surfaceCubeY(11, 20));
        assertEquals(CoverageMap.SURFACE_WAITING, map.state(12, 20));
        assertEquals(1, map.count(CoverageMap.SURFACE_MESHED));
        assertEquals(2, map.count(CoverageMap.SURFACE_WAITING));
        assertEquals(22, map.count(CoverageMap.NOT_LOADED));
    }

    @Test
    void noSurfaceIsKnownWhereAllIsAirOrNothingIsKnownAbove() {
        CoverageMap map = begun();
        // Air all the way down: the ground is below the box.
        column(map, 8, 18, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_EMPTY,
            CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_EMPTY);
        // The highest cube held draws something: the ground may be above it.
        column(map, 9, 18, 6, CoverageMap.CUBE_MESHED, CoverageMap.CUBE_MESHED);
        // Air, then a cube that is not held.
        column(map, 10, 18, 6, CoverageMap.CUBE_EMPTY, (byte) 0, CoverageMap.CUBE_MESHED);
        map.finish(MAX);
        assertEquals(CoverageMap.NO_SURFACE, map.state(8, 18));
        assertEquals(CoverageMap.NO_SURFACE, map.state(9, 18));
        assertEquals(CoverageMap.NO_SURFACE, map.state(10, 18));
        assertEquals(CoverageMap.NONE, map.surfaceCubeY(9, 18));
        // At the world's top there is nothing above to know.
        CoverageMap top = new CoverageMap();
        top.begin(10, 4, 20, 2, 2);
        top.cube(9, 6, 18, CoverageMap.CUBE_MESHED);
        top.finish(6);
        assertEquals(CoverageMap.SURFACE_MESHED, top.state(9, 18));
    }

    @Test
    void theGenerationMovesOnlyWhenAColumnChangesOrTheBoxMoves() {
        CoverageMap map = begun();
        column(map, 10, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_HELD);
        map.finish(MAX);
        long first = map.generation();
        map.begin(10, 4, 20, 2, 2);
        column(map, 10, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_HELD);
        map.finish(MAX);
        assertEquals(first, map.generation());
        map.begin(10, 4, 20, 2, 2);
        column(map, 10, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_MESHED);
        map.finish(MAX);
        assertNotEquals(first, map.generation());
        long second = map.generation();
        map.begin(11, 4, 20, 2, 2);
        column(map, 10, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_MESHED);
        map.finish(MAX);
        assertNotEquals(second, map.generation());
        assertEquals(CoverageMap.SURFACE_MESHED, map.state(10, 20));
        // A cube outside the box is passed over, and a column outside it reads as not loaded.
        map.cube(100, 4, 20, CoverageMap.CUBE_MESHED);
        assertEquals(CoverageMap.NOT_LOADED, map.state(100, 20));
    }

    @Test
    void aColumnWhoseBandIsKnownIsJudgedByTheBandsCubes() {
        CoverageMap map = begun();
        // A tree's crown over the ground: the scan would take the crown for the surface.
        column(map, 10, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_MESHED, CoverageMap.CUBE_EMPTY,
            CoverageMap.CUBE_MESHED, CoverageMap.CUBE_HELD);
        map.finish(MAX);
        assertEquals(5, map.surfaceCubeY(10, 20));

        // Told that the surface spans cubes 2 to 3, the column waits on cube 2, which is not meshed.
        map.begin(10, 4, 20, 2, 2);
        column(map, 10, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_MESHED, CoverageMap.CUBE_EMPTY,
            CoverageMap.CUBE_MESHED, CoverageMap.CUBE_HELD);
        map.band(10, 20, 2, 3);
        map.finish(MAX);
        assertEquals(CoverageMap.SURFACE_WAITING, map.state(10, 20));
        assertEquals(3, map.surfaceCubeY(10, 20));

        // Once both are meshed the column is.
        map.begin(10, 4, 20, 2, 2);
        column(map, 10, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_MESHED, CoverageMap.CUBE_EMPTY,
            CoverageMap.CUBE_MESHED, CoverageMap.CUBE_MESHED);
        map.band(10, 20, 2, 3);
        map.finish(MAX);
        assertEquals(CoverageMap.SURFACE_MESHED, map.state(10, 20));

        // A band that lies outside what is held: cubes are held, the surface is not among them.
        map.begin(10, 4, 20, 2, 2);
        column(map, 10, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_EMPTY);
        map.band(10, 20, -9, -8);
        map.finish(MAX);
        assertEquals(CoverageMap.NO_SURFACE, map.state(10, 20));
        // The band is forgotten at the next filling.
        map.begin(10, 4, 20, 2, 2);
        column(map, 10, 20, 6, CoverageMap.CUBE_EMPTY, CoverageMap.CUBE_MESHED);
        map.finish(MAX);
        assertEquals(CoverageMap.SURFACE_MESHED, map.state(10, 20));
        assertEquals(5, map.surfaceCubeY(10, 20));
    }
}