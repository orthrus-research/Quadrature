package cc.orthrus.quadrature.core.lod;

/**
 * One face of distant terrain as 64 bits, expanded to a quad in the vertex stage. From the lowest
 * bit: x 6 and z 6 (the column within its tile), y 12 (blocks above the tile's floor), extent 12,
 * direction 3, material 13, sky light 4, occlusion 8.
 *
 * <p>A side face stands on its column's edge from y upward and its extent is its height less one.
 * A top or bottom face lies at y and covers a rectangle of columns from (x, z): its extent holds
 * the width less one in the low 6 bits and the depth less one in the high 6. Occlusion is 2 bits a
 * corner, 0 for open to 3 for closed in. Corners of a top or bottom face run (x, z), (x + w, z),
 * (x, z + d), (x + w, z + d); of a side face, lower left, lower right, upper left, upper right as
 * seen from outside.
 */
public final class FaceRecord {

    /** Bytes a record takes. */
    public static final int BYTES = Long.BYTES;

    /** The tallest tile a record's y can address, in blocks. */
    public static final int MAXIMUM_HEIGHT = 1 << 12;

    /** Materials a record can name. */
    public static final int MATERIALS = 1 << 13;

    /** Directions, which are also the six groups a tile's faces are stored in. */
    public static final int UP = 0, DOWN = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5, DIRECTIONS = 6;

    private FaceRecord() {
    }

    /** A side face from {@code y} up for {@code height} blocks. */
    public static long side(int x, int z, int y, int height, int direction, int material, int light, int occlusion) {
        return pack(x, z, y, height - 1, direction, material, light, occlusion);
    }

    /** A top or bottom face at {@code y} over {@code width} by {@code depth} columns. */
    public static long flat(int x, int z, int y, int width, int depth, int direction, int material, int light,
            int occlusion) {
        return pack(x, z, y, (width - 1) | (depth - 1) << 6, direction, material, light, occlusion);
    }

    private static long pack(int x, int z, int y, int extent, int direction, int material, int light,
            int occlusion) {
        return (long) x
                | (long) z << 6
                | (long) y << 12
                | (long) extent << 24
                | (long) direction << 36
                | (long) material << 39
                | (long) light << 52
                | (long) occlusion << 56;
    }

    public static int x(long record) {
        return (int) record & 63;
    }

    public static int z(long record) {
        return (int) (record >>> 6) & 63;
    }

    public static int y(long record) {
        return (int) (record >>> 12) & 0xFFF;
    }

    /** A side face's height in blocks. */
    public static int height(long record) {
        return ((int) (record >>> 24) & 0xFFF) + 1;
    }

    /** A top or bottom face's width in columns, along x. */
    public static int width(long record) {
        return ((int) (record >>> 24) & 63) + 1;
    }

    /** A top or bottom face's depth in columns, along z. */
    public static int depth(long record) {
        return ((int) (record >>> 30) & 63) + 1;
    }

    public static int direction(long record) {
        return (int) (record >>> 36) & 7;
    }

    public static int material(long record) {
        return (int) (record >>> 39) & 0x1FFF;
    }

    public static int light(long record) {
        return (int) (record >>> 52) & 15;
    }

    public static int occlusion(long record) {
        return (int) (record >>> 56) & 0xFF;
    }
}
