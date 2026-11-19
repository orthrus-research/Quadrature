package cc.orthrus.quadrature.core.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.BitSet;

class LightLayerPlanTest {

    private static final double TURN = Math.cos(Math.toRadians(0.1));
    private static final double[] LIGHT = {0.9389919, 0.3117149, 0.14535503};
    private static final double SIDE = 544;

    /** Every tile costs the same: 10 cubes. */
    private static int[] even() {
        int[] costs = new int[LightTiles.COUNT];
        Arrays.fill(costs, 10);
        return costs;
    }

    private static void frame(LightLayerPlan plan, double x, double y, double z) {
        plan.frame(LIGHT[0], LIGHT[1], LIGHT[2], x, y, z, SIDE, SIDE, 4096);
    }

    /** Draws the box being drawn to the end, a frame at a time; returns the frames it took. */
    private static int drawWhole(LightLayerPlan plan, int[] costs) {
        int frames = 0;
        while (plan.next() != null) {
            BitSet tiles = plan.chooseNext(costs);
            frames++;
            if (plan.nextDrawn(tiles)) {
                plan.publish();
            }
            if (frames > 1000) {
                throw new AssertionError("never finished");
            }
        }
        return frames;
    }

    @Test
    void theFirstBoxIsDrawnOverAboutSpreadFramesAndOnlyThenPublished() {
        LightLayerPlan plan = new LightLayerPlan(TURN, 8);
        frame(plan, 2563.4, 146, 7624.7);
        assertNull(plan.front());
        assertNotNull(plan.next());
        assertEquals(LightLayerPlan.Cause.FIRST, plan.nextCause());
        int[] costs = even();
        BitSet first = plan.chooseNext(costs);
        // An eighth of 2,560 cubes is 320: 32 tiles of 10.
        assertEquals(32, first.cardinality());
        assertTrue(first.get(LightTiles.outward(0)));
        assertFalse(plan.nextDrawn(first));
        assertNull(plan.front(), "not published until every tile is drawn");
        assertEquals(7, drawWhole(plan, costs));
        assertNotNull(plan.front());
        assertEquals(1, plan.published());
        assertTrue(plan.takeLightChanged());
        assertTrue(plan.takeContentChanged());
        assertFalse(plan.takeContentChanged());
    }

    @Test
    void aBoxBegunWhileMeshesArriveIsHeldToTheShareOfWhatStandsEachFrame() {
        // After a teleport: at first a cube a tile, then a hundred as the place loads.
        LightLayerPlan plan = new LightLayerPlan(TURN, 8);
        frame(plan, 0, 64, 0);
        int[] sparse = new int[LightTiles.COUNT];
        Arrays.fill(sparse, 1);
        assertEquals(32, plan.chooseNext(sparse).cardinality());
        plan.nextDrawn(plan.chooseNext(sparse));
        int[] loaded = new int[LightTiles.COUNT];
        Arrays.fill(loaded, 100);
        // An eighth of what stands now, not of what stood in the first frame: still 32 tiles a frame.
        assertEquals(32, plan.chooseNext(loaded).cardinality());
        assertEquals(6, drawWhole(plan, loaded) - 1);
    }

    @Test
    void aBoxBegunWhereNothingIsMeshedIsPublishedAtOnceAndWhatArrivesIsDrawnInPlace() {
        LightLayerPlan plan = new LightLayerPlan(TURN, 8);
        frame(plan, 0, 64, 0);
        assertEquals(1, drawWhole(plan, new int[LightTiles.COUNT]));
        assertNotNull(plan.front());
        plan.changed(0, 4, 0);
        assertTrue(plan.frontDirty());
    }

    @Test
    void tilesNoCubeCoversCostNothingAndAreTakenAtOnce() {
        LightLayerPlan plan = new LightLayerPlan(TURN, 8);
        frame(plan, 0, 64, 0);
        int[] costs = new int[LightTiles.COUNT];
        costs[LightTiles.outward(0)] = 50;
        BitSet tiles = plan.chooseNext(costs);
        assertEquals(LightTiles.COUNT, tiles.cardinality());
        assertTrue(plan.nextDrawn(tiles));
    }

    @Test
    void aStepWithinTheDriftKeepsTheBoxAndOneBeyondItBeginsAnother() {
        LightLayerPlan plan = new LightLayerPlan(TURN, 8);
        frame(plan, 2563.4, 146, 7624.7);
        drawWhole(plan, even());
        LightBox front = plan.front();
        // 544 / 64 = 8.5 blocks.
        frame(plan, 2563.4 + 7, 146, 7624.7);
        assertNull(plan.next());
        frame(plan, 2563.4 + 12, 146, 7624.7);
        assertEquals(LightLayerPlan.Cause.MOVE, plan.nextCause());
        assertSame(front, plan.front(), "the front stays published while the next is drawn");
        drawWhole(plan, even());
        assertNotSame(front, plan.front());
        assertTrue(plan.front().offCenter(2563.4 + 12, 146, 7624.7) <= plan.front().texel());
    }

    @Test
    void aTurnBeyondTheAngleBeginsAnotherBoxAndOnlyTheLightIsSaidToHaveChanged() {
        LightLayerPlan plan = new LightLayerPlan(TURN, 8);
        frame(plan, 0, 64, 0);
        drawWhole(plan, even());
        plan.takeContentChanged();
        plan.takeLightChanged();
        double small = Math.toRadians(0.05);
        plan.frame(Math.cos(small) * LIGHT[0] - Math.sin(small) * LIGHT[1], Math.sin(small) * LIGHT[0]
            + Math.cos(small) * LIGHT[1], LIGHT[2], 0, 64, 0, SIDE, SIDE, 4096);
        assertNull(plan.next());
        double big = Math.toRadians(0.2);
        plan.frame(Math.cos(big) * LIGHT[0] - Math.sin(big) * LIGHT[1], Math.sin(big) * LIGHT[0]
            + Math.cos(big) * LIGHT[1], LIGHT[2], 0, 64, 0, SIDE, SIDE, 4096);
        assertEquals(LightLayerPlan.Cause.TURN, plan.nextCause());
        drawWhole(plan, even());
        assertTrue(plan.takeLightChanged());
        assertFalse(plan.takeContentChanged(), "a turn alone changes only the light");
        assertEquals(1, plan.begun(LightLayerPlan.Cause.TURN));
    }

    @Test
    void aNewReachMakesANewBox() {
        LightLayerPlan plan = new LightLayerPlan(TURN, 8);
        frame(plan, 0, 64, 0);
        drawWhole(plan, even());
        plan.frame(LIGHT[0], LIGHT[1], LIGHT[2], 0, 64, 0, 725, 725, 4096);
        assertEquals(LightLayerPlan.Cause.SIZE, plan.nextCause());
    }

    @Test
    void aChangedMeshMakesItsTilesDirtyInTheFrontAndAgainInTheBoxBeingDrawn() {
        LightLayerPlan plan = new LightLayerPlan(TURN, 8);
        frame(plan, 0, 64, 0);
        drawWhole(plan, even());
        assertFalse(plan.frontDirty());
        plan.changed(0, 4, 0);
        assertTrue(plan.frontDirty());
        int dirty = plan.dirtyTiles();
        assertTrue(dirty >= 1 && dirty <= 4, "a cube covers a tile or a few, not " + dirty);
        BitSet tiles = plan.chooseFront(even());
        assertEquals(dirty, tiles.cardinality());
        plan.frontDrawn(tiles);
        assertFalse(plan.frontDirty());
        assertTrue(plan.takeContentChanged());

        // While a new box is drawn, a change after its tiles were drawn draws them again there.
        frame(plan, 20, 64, 0);
        BitSet first = plan.chooseNext(even());
        BitSet drawn = (BitSet) first.clone();
        plan.nextDrawn(first);
        int pendingBefore = plan.pendingTiles();
        plan.changed(1, 4, 0);
        assertTrue(plan.pendingTiles() > pendingBefore, "drawn tiles are pending again");
        assertTrue(plan.dirtyTiles() > 0);
        assertFalse(plan.frontDirty(), "the front's dirty tiles wait while the box replacing it is drawn");
        drawWhole(plan, even());
        assertFalse(plan.frontDirty(), "what changed was drawn into the box published");
        assertTrue(plan.takeContentChanged());
        assertTrue(drawn.cardinality() > 0);
    }

    @Test
    void aBoxBeingDrawnIsBegunAgainWhereThePlayerHasGoneWhenTheyLeaveIt() {
        LightLayerPlan plan = new LightLayerPlan(TURN, 8);
        frame(plan, 0, 64, 0);
        plan.nextDrawn(plan.chooseNext(even()));
        // An eighth of the side is 68 blocks.
        frame(plan, 50, 64, 0);
        assertEquals(0, plan.restarts());
        frame(plan, 100, 64, 0);
        assertEquals(1, plan.restarts());
        assertEquals(LightTiles.COUNT, plan.pendingTiles());
        assertTrue(plan.next().offCenter(100, 64, 0) <= plan.next().texel());
    }

    @Test
    void theFrontsDirtyTilesAreBoundedByTheSameShareAndTheRestWait() {
        LightLayerPlan plan = new LightLayerPlan(TURN, 4);
        frame(plan, 0, 64, 0);
        drawWhole(plan, even());
        // Every cube of a block of them about the box: across an oblique light every tile is covered.
        for (int x = -16; x <= 16; x++) {
            for (int y = -12; y <= 20; y++) {
                for (int z = -16; z <= 16; z++) {
                    plan.changed(x, y, z);
                }
            }
        }
        assertEquals(LightTiles.COUNT, plan.dirtyTiles());
        BitSet tiles = plan.chooseFront(even());
        // A quarter of 2,560 cubes: 64 tiles.
        assertEquals(64, tiles.cardinality());
        plan.frontDrawn(tiles);
        assertEquals(LightTiles.COUNT - 64, plan.dirtyTiles());
    }

    @Test
    void redrawingAllDirtiesEveryTileOfTheFrontAndOfABoxBeingDrawn() {
        LightLayerPlan plan = new LightLayerPlan(TURN, 8);
        frame(plan, 0, 64, 0);
        drawWhole(plan, even());
        plan.redrawAll();
        assertEquals(LightTiles.COUNT, plan.dirtyTiles());
        frame(plan, 20, 64, 0);
        plan.nextDrawn(plan.chooseNext(even()));
        plan.redrawAll();
        assertEquals(LightTiles.COUNT, plan.pendingTiles());
        drawWhole(plan, even());
        assertTrue(plan.takeContentChanged());
    }

    @Test
    void aResetForgetsEveryBox() {
        LightLayerPlan plan = new LightLayerPlan(TURN, 8);
        frame(plan, 0, 64, 0);
        drawWhole(plan, even());
        plan.changed(0, 4, 0);
        plan.reset();
        assertNull(plan.front());
        assertNull(plan.next());
        assertFalse(plan.frontDirty());
        frame(plan, 0, 64, 0);
        assertEquals(LightLayerPlan.Cause.FIRST, plan.nextCause());
    }
}
