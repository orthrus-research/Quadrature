package cc.orthrus.quadrature.core.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class LightSceneTest {

    private static final int STONE = 16;
    private static final int GLOWSTONE = 1424;
    private static final int LEAVES = 288;

    private static LightScene scene() {
        LightScene scene = new LightScene();
        scene.defineState(STONE, 0, 15, true);
        scene.defineState(GLOWSTONE, 15, 15, true);
        scene.defineState(LEAVES, 0, 1, false);
        return scene;
    }

    /** A cube of air with stone below y 4 and one glowstone cell. */
    private static short[] ground() {
        short[] cells = new short[LightScene.CELLS];
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    cells[LightScene.index(x, y, z)] = STONE;
                }
            }
        }
        cells[LightScene.index(5, 6, 7)] = (short) GLOWSTONE;
        return cells;
    }

    private static long[] blocking(LightScene scene, short[] cells) {
        long[] bits = new long[LightScene.CELLS / 64];
        for (int i = 0; i < LightScene.CELLS; i++) {
            if (scene.blocksLight(cells[i] & 0xFFFF)) {
                bits[i >> 6] |= 1L << i;
            }
        }
        return bits;
    }

    private static final class Seen implements LightScene.Changes {
        final List<String> events = new ArrayList<>();

        @Override
        public void everything() {
            events.add("everything");
        }

        @Override
        public void updated(int x, int y, int z, long generation) {
            events.add("updated " + x + " " + y + " " + z);
        }

        @Override
        public void removed(int x, int y, int z) {
            events.add("removed " + x + " " + y + " " + z);
        }
    }

    @Test
    void aStateSaysWhatItDoesToLight() {
        LightScene scene = scene();
        assertTrue(scene.stateDefined(LightScene.AIR));
        assertEquals(0, scene.emission(LightScene.AIR));
        assertEquals(0, scene.transmission(LightScene.AIR));
        assertFalse(scene.blocksLight(LightScene.AIR));
        assertEquals(15, scene.emission(GLOWSTONE));
        assertTrue(scene.blocksLight(STONE));
        assertEquals(1, scene.transmission(LEAVES));
        assertFalse(scene.blocksLight(LEAVES));
        assertFalse(scene.stateDefined(999));
        assertThrows(IllegalArgumentException.class, () -> scene.defineState(5, 16, 0, false));
    }

    @Test
    void aCellIsAirOrSolidOrNotAvailable() {
        LightScene scene = scene();
        short[] cells = ground();
        scene.put(2, -1, 3, cells, blocking(scene, cells));
        // Block positions inside cube (2, -1, 3).
        assertEquals(LightScene.Cell.RESIDENT_SOLID, scene.cell(32, -16, 48));
        assertEquals(STONE, scene.state(32 + 15, -16 + 3, 48 + 15));
        assertEquals(LightScene.Cell.KNOWN_AIR, scene.cell(32, -16 + 4, 48));
        assertEquals(GLOWSTONE, scene.state(32 + 5, -16 + 6, 48 + 7));
        // The cube beside it was never taken: nothing is known, which is not the same as empty.
        assertEquals(LightScene.Cell.NOT_AVAILABLE, scene.cell(31, -16, 48));
        assertEquals(-1, scene.state(31, -16, 48));
        assertFalse(scene.has(1, -1, 3));
        scene.remove(2, -1, 3);
        assertEquals(LightScene.Cell.NOT_AVAILABLE, scene.cell(32, -16, 48));
    }

    @Test
    void aCubeOfOneStateKeepsNoArray() {
        LightScene scene = scene();
        scene.putUniform(0, 0, 0, LightScene.AIR);
        short[] stone = new short[LightScene.CELLS];
        java.util.Arrays.fill(stone, (short) STONE);
        scene.put(0, -1, 0, stone, blocking(scene, stone));
        assertEquals(2, scene.cubes());
        assertEquals(0, scene.mixedCubes());
        assertEquals(0, scene.cellBytes());
        assertNull(scene.cells(0, -1, 0));
        assertEquals(STONE, scene.uniformState(0, -1, 0));
        assertEquals(LightScene.Cell.KNOWN_AIR, scene.cell(8, 8, 8));
        assertEquals(LightScene.Cell.RESIDENT_SOLID, scene.cell(8, -8, 8));

        short[] cells = ground();
        scene.put(0, 0, 0, cells, blocking(scene, cells));
        assertEquals(1, scene.mixedCubes());
        assertEquals(LightScene.CELLS * 2 + LightScene.CELLS / 8, scene.cellBytes());
        assertNotNull(scene.cells(0, 0, 0));
        assertEquals(-1, scene.uniformState(0, 0, 0));
        long[] bits = scene.blocking(0, 0, 0);
        assertTrue((bits[LightScene.index(3, 2, 9) >> 6] >>> LightScene.index(3, 2, 9) & 1) == 1);
        assertTrue((bits[LightScene.index(3, 9, 9) >> 6] >>> LightScene.index(3, 9, 9) & 1) == 0);
        // The scene keeps its own copy.
        cells[LightScene.index(0, 15, 0)] = STONE;
        assertEquals(LightScene.AIR, scene.state(0, 15, 0));
        scene.putUniform(0, 0, 0, LightScene.AIR);
        assertEquals(0, scene.mixedCubes());
    }

    @Test
    void cellsRunWithXFastestThenZThenY() {
        assertEquals(0, LightScene.index(0, 0, 0));
        assertEquals(1, LightScene.index(1, 0, 0));
        assertEquals(16, LightScene.index(0, 0, 1));
        assertEquals(256, LightScene.index(0, 1, 0));
        assertEquals(4095, LightScene.index(15, 15, 15));
        // A block position's low four bits place it in its cube, below zero as above.
        assertEquals(LightScene.index(15, 15, 15), LightScene.index(-1, -1, -1));
    }

    @Test
    void aConsumerIsToldEachChangedCubeOnce() {
        LightScene scene = scene();
        Seen seen = new Seen();
        long cursor = scene.changesSince(scene.version(), seen);
        assertTrue(seen.events.isEmpty());

        short[] cells = ground();
        scene.put(1, 0, 0, cells, blocking(scene, cells));
        scene.putUniform(2, 0, 0, LightScene.AIR);
        scene.put(1, 0, 0, cells, blocking(scene, cells));
        long first = scene.generation(1, 0, 0);
        cursor = scene.changesSince(cursor, seen);
        assertEquals(List.of("updated 2 0 0", "updated 1 0 0"), seen.events);
        assertEquals(scene.version(), cursor);
        assertEquals(3, first);

        seen.events.clear();
        cursor = scene.changesSince(cursor, seen);
        assertTrue(seen.events.isEmpty());

        scene.remove(2, 0, 0);
        scene.remove(7, 7, 7);
        cursor = scene.changesSince(cursor, seen);
        assertEquals(List.of("removed 2 0 0"), seen.events);
        assertEquals(-1, scene.generation(2, 0, 0));

        // Removed and taken again before the consumer asks: it is told only what the cube is now.
        seen.events.clear();
        scene.remove(1, 0, 0);
        scene.putUniform(1, 0, 0, STONE);
        scene.changesSince(cursor, seen);
        assertEquals(List.of("updated 1 0 0"), seen.events);
        assertTrue(scene.generation(1, 0, 0) > first);
    }

    @Test
    void aConsumerTooFarBehindTakesEverythingAgain() {
        LightScene scene = new LightScene(4);
        Seen seen = new Seen();
        long cursor = scene.version();
        for (int x = 0; x < 10; x++) {
            scene.putUniform(x, 0, 0, LightScene.AIR);
        }
        scene.remove(0, 0, 0);
        long now = scene.changesSince(cursor, seen);
        assertEquals("everything", seen.events.get(0));
        assertEquals(1 + 9, seen.events.size());
        assertFalse(seen.events.contains("updated 0 0 0"));

        // One that is close enough is told only the last changes.
        seen.events.clear();
        scene.putUniform(20, 0, 0, LightScene.AIR);
        scene.remove(5, 0, 0);
        scene.changesSince(now, seen);
        assertEquals(List.of("updated 20 0 0", "removed 5 0 0"), seen.events);

        // A consumer that never asked before starts with everything.
        seen.events.clear();
        scene.changesSince(-1, seen);
        assertEquals("everything", seen.events.get(0));
    }

    @Test
    void clearingForgetsEveryCubeAndTellsConsumersToStartAgain() {
        LightScene scene = scene();
        short[] cells = ground();
        scene.put(0, 0, 0, cells, blocking(scene, cells));
        long cursor = scene.version();
        scene.clear();
        scene.putUniform(4, 4, 4, LightScene.AIR);
        Seen seen = new Seen();
        scene.changesSince(cursor, seen);
        assertEquals(List.of("everything", "updated 4 4 4"), seen.events);
        assertEquals(1, scene.cubes());
        assertEquals(0, scene.mixedCubes());
        assertTrue(scene.stateDefined(STONE));
    }
}
