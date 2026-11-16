package cc.orthrus.quadrature.core.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SceneBricksTest {

    private static final int STONE = 16;
    private static final int LEAVES = 288;

    private static LightScene scene() {
        LightScene scene = new LightScene();
        scene.defineState(STONE, 0, 15, true);
        scene.defineState(LEAVES, 0, 1, false);
        return scene;
    }

    /** Puts a cube of air with stone in the cells given. */
    private static void put(LightScene scene, int x, int y, int z, int... stoneCells) {
        short[] cells = new short[LightScene.CELLS];
        long[] blocking = new long[LightScene.CELLS / 64];
        cells[LightScene.index(0, 15, 0)] = LEAVES;
        for (int cell : stoneCells) {
            cells[cell] = STONE;
            blocking[cell >> 6] |= 1L << cell;
        }
        scene.put(x, y, z, cells, blocking);
    }

    private static boolean bit(long[] words, int at, int bit) {
        return (words[at + (bit >> 6)] >>> bit & 1) != 0;
    }

    @Test
    void anEntrySaysNothingIsKnownOrOneStateOrABrick() {
        LightScene scene = scene();
        SceneBricks bricks = new SceneBricks(scene, 2, 1);
        assertEquals(5, bricks.sizeX());
        assertEquals(3, bricks.sizeY());
        scene.putUniform(0, 0, 0, LightScene.AIR);
        scene.putUniform(1, 0, 0, STONE);
        put(scene, 2, 0, 0, LightScene.index(3, 4, 5));
        bricks.update(0, 0, 0);
        assertEquals(SceneBricks.UNIFORM | LightScene.AIR, bricks.entry(0, 0, 0));
        assertEquals(SceneBricks.UNIFORM | STONE, bricks.entry(1, 0, 0));
        assertEquals(SceneBricks.BRICK, bricks.entry(2, 0, 0));
        assertEquals(SceneBricks.NOT_AVAILABLE, bricks.entry(-1, 0, 0));
        // A cube beyond the box is not available whatever the scene holds.
        scene.putUniform(3, 0, 0, STONE);
        bricks.update(0, 0, 0);
        assertEquals(SceneBricks.NOT_AVAILABLE, bricks.entry(3, 0, 0));
        assertEquals(1, bricks.bricksHeld());
    }

    @Test
    void aBrickHoldsTheBlockingCellsAndACoarserLevel() {
        LightScene scene = scene();
        SceneBricks bricks = new SceneBricks(scene, 1, 1);
        put(scene, 0, 0, 0, LightScene.index(3, 4, 5), LightScene.index(15, 15, 15));
        bricks.update(0, 0, 0);
        long[] words = bricks.bricks();
        assertTrue(bit(words, 0, LightScene.index(3, 4, 5)));
        assertTrue(bit(words, 0, LightScene.index(15, 15, 15)));
        // Leaves hold light back and do not block it.
        assertTrue(!bit(words, 0, LightScene.index(0, 15, 0)));
        int set = 0;
        for (int i = 0; i < SceneBricks.FINE_WORDS; i++) {
            set += Long.bitCount(words[i]);
        }
        assertEquals(2, set);
        // The coarser level: blocks of two cells a side, three bits an axis in the same order.
        assertTrue(bit(words, SceneBricks.FINE_WORDS, 2 << 6 | 2 << 3 | 1));
        assertTrue(bit(words, SceneBricks.FINE_WORDS, 7 << 6 | 7 << 3 | 7));
        int coarse = 0;
        for (int i = SceneBricks.FINE_WORDS; i < SceneBricks.BRICK_WORDS; i++) {
            coarse += Long.bitCount(words[i]);
        }
        assertEquals(2, coarse);
        assertEquals(576, SceneBricks.BRICK_BYTES);
    }

    @Test
    void movingTheOriginChangesOnlyWhatLeftAndEntered() {
        LightScene scene = scene();
        SceneBricks bricks = new SceneBricks(scene, 1, 0);
        for (int x = -3; x <= 3; x++) {
            scene.putUniform(x, 0, 0, STONE);
        }
        bricks.update(0, 0, 0);
        int[] indices = new int[64];
        // Three cubes are known; the other six entries of the box were nothing and stay nothing.
        assertEquals(3, bricks.takeEntries(indices, 64));
        long generation = bricks.generation();

        bricks.update(1, 0, 0);
        assertNotEquals(generation, bricks.generation());
        // Cube -1 left and cube 2 entered; they share an entry, which now names cube 2.
        assertEquals(bricks.entryIndex(-1, 0, 0), bricks.entryIndex(2, 0, 0));
        assertEquals(SceneBricks.NOT_AVAILABLE, bricks.entry(-1, 0, 0));
        assertEquals(SceneBricks.UNIFORM | STONE, bricks.entry(2, 0, 0));
        // Only that one entry was touched, of the nine.
        assertEquals(1, bricks.takeEntries(indices, 64));
        assertEquals(bricks.entryIndex(2, 0, 0), indices[0]);

        generation = bricks.generation();
        bricks.update(1, 0, 0);
        assertEquals(generation, bricks.generation());
    }

    @Test
    void changesInTheSceneReachTheLookupAndBricksAreReused() {
        LightScene scene = scene();
        SceneBricks bricks = new SceneBricks(scene, 1, 1);
        put(scene, 0, 0, 0, 1);
        put(scene, 1, 0, 0, 2);
        bricks.update(0, 0, 0);
        assertEquals(2, bricks.bricksHeld());
        int[] slots = new int[8];
        int[] indices = new int[64];
        // An entry that names a brick waits until the brick has been taken.
        assertEquals(0, bricks.takeEntries(indices, 64));
        assertEquals(1, bricks.takeBricks(slots, 1));
        assertEquals(1, bricks.takeEntries(indices, 64));
        assertEquals(1, bricks.takeBricks(slots, 8));
        assertEquals(1, bricks.takeEntries(indices, 64));
        assertEquals(0, bricks.bricksWaiting());
        assertEquals(0, bricks.entriesWaiting());

        // A cube that becomes uniform gives its brick back, and the next mixed cube takes that slot.
        int freed = bricks.entry(0, 0, 0) & 0xFFFFFF;
        scene.putUniform(0, 0, 0, LightScene.AIR);
        scene.remove(1, 0, 0);
        put(scene, 0, 1, 0, 3);
        long generation = bricks.generation();
        bricks.update(0, 0, 0);
        assertNotEquals(generation, bricks.generation());
        assertEquals(SceneBricks.UNIFORM, bricks.entry(0, 0, 0));
        assertEquals(SceneBricks.NOT_AVAILABLE, bricks.entry(1, 0, 0));
        assertEquals(1, bricks.bricksHeld());
        assertEquals(2, bricks.slots());
        assertTrue((bricks.entry(0, 1, 0) & 0xFFFFFF) <= 1);
        assertTrue(freed <= 1);
        // The cube changed in place keeps its slot and its brick is taken again.
        bricks.takeBricks(slots, 8);
        bricks.takeEntries(indices, 64);
        int slot = bricks.entry(0, 1, 0) & 0xFFFFFF;
        put(scene, 0, 1, 0, 3, 4);
        bricks.update(0, 0, 0);
        assertEquals(slot, bricks.entry(0, 1, 0) & 0xFFFFFF);
        assertEquals(1, bricks.takeBricks(slots, 8));
        assertEquals(slot, slots[0]);
        assertEquals(0, bricks.takeEntries(indices, 64));

        // The scene cleared: every entry goes back to nothing.
        scene.clear();
        bricks.update(0, 0, 0);
        assertEquals(SceneBricks.NOT_AVAILABLE, bricks.entry(0, 1, 0));
        assertEquals(0, bricks.bricksHeld());
    }
}
