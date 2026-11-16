package cc.orthrus.quadrature.core.lod;

/**
 * Where near terrain stands in for distant terrain, and what distant terrain must match at the
 * join: the box of cubes near terrain meshes, and the level of a water surface.
 *
 * <p>Near terrain meshes a cube only once the six cubes sharing a face with it are loaded. A client
 * that holds the cubes within {@code reach} of the viewer's cube on an axis therefore meshes those
 * within {@code reach - 1}: the outermost row always lacks a neighbor. The box is that, in blocks.
 */
public final class NearCover {

    /**
     * How far below its block's top the game draws the surface of still water: a ninth of a block.
     * A distant water face drawn at the block's top stands that much above near water, and the
     * slot between them at the box's edge shows the unlit bed beyond: at 190 blocks a third of a
     * pixel, which appeared in the second run as a row of dark dashes along the join.
     */
    public static final float WATER_SURFACE_DROP = 1f / 9f;

    private NearCover() {
    }

    /** The cubes from the viewer's that near terrain meshes on an axis, given how far cubes load. */
    public static int meshedReach(int loadedReach) {
        return Math.max(0, loadedReach - 1);
    }

    /** The lowest block of the box on an axis. */
    public static long minimum(int originCube, int loadedReach) {
        return (originCube - (long) meshedReach(loadedReach)) * 16L;
    }

    /** The block after the highest of the box on an axis. */
    public static long maximum(int originCube, int loadedReach) {
        return (originCube + (long) meshedReach(loadedReach) + 1) * 16L;
    }

    /** How far a draw layer's top faces are lowered, in blocks: water to the game's surface. */
    public static float topDrop(int drawLayer) {
        return drawLayer == TileDraws.WATER ? WATER_SURFACE_DROP : 0f;
    }
}
