package cc.orthrus.quadrature.core.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NearEdgeTest {

    @Test
    void theEdgeIsTheNearestSideOfTheCubesThatCanBeMeshed() {
        // Twelve cubes held to each side of cube 0: eleven can be meshed, so the box runs from -176 to 192.
        assertEquals(184f, NearEdge.blocks(0, 0, 12, 8.0, 8.0), 0f);
        // At a cube's low corner the high side is a cube farther and the low side is the nearest.
        assertEquals(176f, NearEdge.blocks(0, 0, 12, 0.0, 0.0), 0f);
        // Nearer one side along z than any along x.
        assertEquals(176.5f, NearEdge.blocks(0, 0, 12, 8.0, 15.5), 0f);
        // Far from the world's origin, and in cubes below zero.
        assertEquals(184f, NearEdge.blocks(-1_000_000, 62_500, 12, -16_000_000.0 + 8, 1_000_000.0 + 8), 0f);
    }

    @Test
    void withNothingToMeshBeyondTheViewsOwnCubeTheEdgeIsThatCubesSide() {
        assertEquals(8f, NearEdge.blocks(3, -2, 1, 56.0, -24.0), 0f);
        assertEquals(8f, NearEdge.blocks(3, -2, 0, 56.0, -24.0), 0f);
        // An eye outside the box, as a camera behind the player may be for a moment: never below nothing.
        assertEquals(0f, NearEdge.blocks(3, -2, 1, 70.0, -24.0), 0f);
    }
}
