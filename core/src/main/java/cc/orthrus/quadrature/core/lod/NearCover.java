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

    /**
     * The box of blocks near terrain meshes when nothing says so column by column: the cubes the
     * server keeps sent to the client, less the outermost shell, whose cubes always lack a
     * neighbor. From the cube source's held box when it states one, which is where the server has
     * the player and how far it sends, whatever the client's own settings say; else from the
     * viewer's cube and the client's view distances, which is the same box once the server has
     * followed the viewer and agrees on the distances.
     *
     * @param heldBox {@code {centerX, centerY, centerZ, horizontal, vertical}} in cubes, or null
     * @param across  how far cubes load over the ground by the client's settings; {@code upward} in height
     * @param into    the lowest block on x, y and z, then the block after the highest on each
     * @return how far cubes load over the ground, as the box was taken
     */
    public static int box(int[] heldBox, int originCubeX, int originCubeY, int originCubeZ, int across, int upward,
            long[] into) {
        int x = originCubeX;
        int y = originCubeY;
        int z = originCubeZ;
        if (heldBox != null) {
            x = heldBox[0];
            y = heldBox[1];
            z = heldBox[2];
            across = heldBox[3];
            upward = heldBox[4];
        }
        into[0] = minimum(x, across);
        into[1] = minimum(y, upward);
        into[2] = minimum(z, across);
        into[3] = maximum(x, across);
        into[4] = maximum(y, upward);
        into[5] = maximum(z, across);
        return across;
    }

    /** A column's state in near terrain's coverage map when its surface is meshed. */
    public static final byte SURFACE_MESHED = 3;

    /**
     * Whether near terrain has meshed the surface of every chunk column a square of blocks touches,
     * by its coverage map: a tile over such ground need not be drawn. A column outside the map is
     * not covered.
     *
     * @param states     the map's columns, {@code side} to a row, x fastest
     * @param firstCubeX the cube x of the map's first column
     * @param minX       the square's lowest block; {@code maxX} is the block after its highest
     */
    public static boolean covered(byte[] states, int side, int firstCubeX, int firstCubeZ, long minX, long minZ,
            long maxX, long maxZ) {
        long lowX = Math.floorDiv(minX, 16L) - firstCubeX;
        long lowZ = Math.floorDiv(minZ, 16L) - firstCubeZ;
        long highX = Math.floorDiv(maxX - 1, 16L) - firstCubeX;
        long highZ = Math.floorDiv(maxZ - 1, 16L) - firstCubeZ;
        if (lowX < 0 || lowZ < 0 || highX >= side || highZ >= side || states.length < side * side) {
            return false;
        }
        for (long z = lowZ; z <= highZ; z++) {
            for (long x = lowX; x <= highX; x++) {
                if (states[(int) (z * side + x)] != SURFACE_MESHED) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * How far through the join's fade a place is: 0 up to where the band starts, 1 from where it
     * ends, by its distance from the view along the farther of x and z, which is how near terrain
     * measures it. Near terrain keeps a pixel where the shared pattern's complement keeps it at
     * this value and distant terrain where the pattern itself does, so over ground both draw
     * each pixel is drawn once.
     */
    public static float joinFade(double fromViewX, double fromViewZ, float start, float end) {
        double square = Math.max(Math.abs(fromViewX), Math.abs(fromViewZ));
        return (float) Math.max(0, Math.min(1, (square - start) / (end - start)));
    }

    /**
     * Whether a square of ground lies wholly nearer the view than where the join's fade starts,
     * by the same distance. Only such a tile, when near terrain covers it, need not be drawn:
     * one that reaches into the band is drawn there in the pixels near terrain gives up.
     *
     * @param minX the square's lowest block; {@code maxX} is the block after its highest
     */
    public static boolean beforeJoin(long minX, long minZ, long maxX, long maxZ, double viewX, double viewZ,
            float start) {
        double farthest = Math.max(Math.max(Math.abs(minX - viewX), Math.abs(maxX - viewX)),
            Math.max(Math.abs(minZ - viewZ), Math.abs(maxZ - viewZ)));
        return farthest <= start;
    }

    /**
     * Where level 0's images fade to flat color, in blocks from the eye: as asked for, but never
     * beginning inside what near terrain covers, so that the join is not also a fade, and ending
     * inside ring 0, so that no image ends on a level's edge.
     *
     * @param nearBlocks how far near terrain reaches from the eye
     * @param ringReach  how far ring 0 reaches
     * @return where the fade starts and where it ends
     */
    public static float[] imageFade(float start, float end, float nearBlocks, float ringReach) {
        float last = Math.max(2f, ringReach - 32f);
        float from = Math.min(Math.max(start, nearBlocks + 32f), last - 1f);
        float to = Math.min(Math.max(end, from + 128f), last);
        return new float[] {from, Math.max(to, from + 1f)};
    }

    /** How far a draw layer's top faces are lowered, in blocks: water to the game's surface. */
    public static float topDrop(int drawLayer) {
        return drawLayer == TileDraws.WATER ? WATER_SURFACE_DROP : 0f;
    }
}
