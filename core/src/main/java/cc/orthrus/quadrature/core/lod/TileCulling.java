package cc.orthrus.quadrature.core.lod;

/**
 * What of a distant tile a view can leave out without looking at its faces: the groups whose faces
 * all turn away from the eye, and the whole tile where loaded cubes stand in for it.
 */
public final class TileCulling {

    private TileCulling() {
    }

    /**
     * The directions whose faces may turn toward an eye, as a mask of {@code 1 << direction}. A face
     * looking west can be seen only from west of it, and every such face of a tile lies west of the
     * tile's eastern edge; so for the other five.
     *
     * @param minimumY the lowest and highest level any face of the tile reaches
     */
    public static int facing(long tile, int minimumY, int maximumY, double eyeX, double eyeY, double eyeZ) {
        long side = TileKey.side(TileKey.level(tile));
        long minX = TileKey.originX(tile);
        long minZ = TileKey.originZ(tile);
        int mask = 0;
        if (eyeY > minimumY) {
            mask |= 1 << FaceRecord.UP;
        }
        if (eyeY < maximumY) {
            mask |= 1 << FaceRecord.DOWN;
        }
        if (eyeZ < minZ + side) {
            mask |= 1 << FaceRecord.NORTH;
        }
        if (eyeZ > minZ) {
            mask |= 1 << FaceRecord.SOUTH;
        }
        if (eyeX < minX + side) {
            mask |= 1 << FaceRecord.WEST;
        }
        if (eyeX > minX) {
            mask |= 1 << FaceRecord.EAST;
        }
        return mask;
    }

    /**
     * Whether every face of a tile lies inside a box of blocks, so that what is loaded there draws
     * that ground and the tile need not be drawn at all. The box holds the blocks from its minimum
     * up to its maximum, exclusive.
     */
    public static boolean inside(long tile, int minimumY, int maximumY, long boxMinX, long boxMinY, long boxMinZ,
            long boxMaxX, long boxMaxY, long boxMaxZ) {
        long side = TileKey.side(TileKey.level(tile));
        long minX = TileKey.originX(tile);
        long minZ = TileKey.originZ(tile);
        return minX >= boxMinX && minX + side <= boxMaxX && minZ >= boxMinZ && minZ + side <= boxMaxZ
            && minimumY >= boxMinY && maximumY <= boxMaxY;
    }
}
