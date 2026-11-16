package cc.orthrus.quadrature.core.lod;

/**
 * How far from the eye distant terrain is drawn without an edge, for whatever fades terrain into
 * the sky before its end: the smallest of the distances along x or z from the eye to the four
 * sides of the outermost level's square.
 *
 * <p>A level's square sits on its tiles' grid and not on the eye, so its sides are not equally far:
 * each is within one tile of the level's reach, and the nearest moves between the reach and the
 * reach less a tile as the eye moves, without a jump. A side that lies on the world's bound is not
 * an edge to hide, since nothing is beyond it, and an eye beside the bound would otherwise fade
 * the whole world away: such sides are left out, and where all four are the world's the answer is
 * the distance to the farthest of them.
 */
public final class FarEdge {

    private FarEdge() {
    }

    /**
     * The least the distance can be for any eye in a world without bounds: the outermost level's
     * reach less one of its tiles, five sixths of the reach at a reach of 384. A fade that is
     * complete by here hides the edge wherever the eye stands.
     */
    public static long least(int reach, int levels) {
        return ((long) reach << (levels - 1)) - TileKey.side(levels - 1);
    }

    /**
     * The distance in blocks, without a world bound.
     *
     * @param minX the lowest block of the level's square; {@code maxX} is the block after its highest
     */
    public static float distance(double eyeX, double eyeZ, long minX, long minZ, long maxX, long maxZ) {
        return distance(eyeX, eyeZ, minX, minZ, maxX, maxZ, Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE,
            Long.MAX_VALUE);
    }

    /**
     * The distance in blocks in a world that holds the blocks from (worldMinX, worldMinZ) up to
     * (worldMaxX, worldMaxZ). 0 for an eye outside what is drawn.
     */
    public static float distance(double eyeX, double eyeZ, long minX, long minZ, long maxX, long maxZ, long worldMinX,
            long worldMinZ, long worldMaxX, long worldMaxZ) {
        double nearest = Double.MAX_VALUE;
        double farthestBound = 0;
        boolean open = false;
        // West, north, east, south: the side's place, the world's, and which way is out.
        long[] side = {minX, minZ, maxX, maxZ};
        long[] world = {worldMinX, worldMinZ, worldMaxX, worldMaxZ};
        for (int i = 0; i < 4; i++) {
            boolean low = i < 2;
            double eye = i % 2 == 0 ? eyeX : eyeZ;
            boolean bound = low ? side[i] <= world[i] : side[i] >= world[i];
            double edge = bound ? world[i] : side[i];
            double away = Math.max(0, low ? eye - edge : edge - eye);
            if (bound) {
                farthestBound = Math.max(farthestBound, away);
            } else {
                open = true;
                nearest = Math.min(nearest, away);
            }
        }
        return (float) (open ? nearest : farthestBound);
    }
}
