package cc.orthrus.quadrature.core.terrain;

/**
 * A cube position packed into one number: 22 bits each for x and z, 20 for y, all signed. That covers
 * 33 million blocks either way horizontally and 8 million vertically.
 */
public final class CubeKey {

    private static final int XZ_BITS = 22;
    private static final int Y_BITS = 20;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;

    private CubeKey() {
    }

    public static long of(int x, int y, int z) {
        return (x & XZ_MASK) << (XZ_BITS + Y_BITS) | (y & Y_MASK) << XZ_BITS | (z & XZ_MASK);
    }

    public static int x(long key) {
        return (int) (key >> (XZ_BITS + Y_BITS) << (64 - XZ_BITS) >> (64 - XZ_BITS));
    }

    public static int y(long key) {
        return (int) (key >> XZ_BITS << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    public static int z(long key) {
        return (int) (key << (64 - XZ_BITS) >> (64 - XZ_BITS));
    }

    /** Squared distance in blocks from a point to the middle of the cube. */
    public static double distanceSquared(long key, double px, double py, double pz) {
        double dx = (x(key) << 4) + 8 - px;
        double dy = (y(key) << 4) + 8 - py;
        double dz = (z(key) << 4) + 8 - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
