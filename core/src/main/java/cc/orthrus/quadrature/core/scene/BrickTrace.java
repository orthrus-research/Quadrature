package cc.orthrus.quadrature.core.scene;

/**
 * Walks a ray through the light scene as a shader reads it: the wrapped lookup of
 * {@link PublishedScene}, the bricks behind it, and a bit for each state that blocks light. It is the
 * reference the compute program is checked against and does what the program does, step for step, in
 * the same single-precision arithmetic.
 *
 * <p>The walk has three sizes of node: a cube of one state that does not block light is passed whole;
 * in a brick, a block of two cells a side whose coarser bit is clear is passed whole; otherwise a single
 * cell is visited. Leaving a node, the distance to each of its far faces is worked out from the ray's
 * origin and the inverse of its direction, {@code (face - origin) * inverse}, never accumulated, and the
 * nearest face is crossed, x before y before z where two are equal. The ray then stands in the cell just
 * past that face; along the other two axes it stands in the last cell whose near face it crossed before
 * that distance, by the same rule. That is exactly where a walk of one cell at a time, crossing the
 * nearest face each step, would stand: the walk visits fewer nodes but ends in the same cell at the same
 * distance, to the bit.
 *
 * <p>A ray stops in the first cell that blocks light, at the first cube nothing is known of (which never
 * reads as empty), on leaving the box, or where the next face lies beyond its length.
 *
 * <p>Holds no state between rays; give it the scene's contents and box first.
 */
public final class BrickTrace {

    /** The most nodes a ray visits before it is stopped. */
    public static final int MOST_VISITS = 4096;
    /** An inverse direction is clamped to this, so that an infinite one is still a number. */
    public static final float MOST_INVERSE = 1e30f;
    /** An estimate of a position is clamped to this before it is made a whole number. */
    private static final float MOST_ESTIMATE = 1e6f;

    private int across;
    private int upward;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private int modX;
    private int modY;
    private int modZ;
    private int[] lookup;
    private int[] bricks;
    private int[] blocking;

    /**
     * Says what the ray walks through.
     *
     * @param across   the box reaches this many cubes to each side of the origin cube, horizontally
     * @param upward   and this many up and down
     * @param originX  the origin cube
     * @param lookup   an entry for each cube position of the box, as {@link SceneBricks#entryIndex} places them
     * @param bricks   {@link PublishedScene#BRICK_INTEGERS} integers a slot, as the GPU holds them
     * @param blocking a bit for each state that blocks light, 2,048 integers
     */
    public void scene(int across, int upward, int originX, int originY, int originZ, int[] lookup, int[] bricks,
        int[] blocking) {
        if (across > TraceFormat.MOST_REACH || upward > TraceFormat.MOST_REACH) {
            throw new IllegalArgumentException("A box past " + TraceFormat.MOST_REACH + " cubes is not traced");
        }
        this.across = across;
        this.upward = upward;
        sizeX = 2 * across + 1;
        sizeY = 2 * upward + 1;
        sizeZ = sizeX;
        modX = Math.floorMod(originX, sizeX);
        modY = Math.floorMod(originY, sizeY);
        modZ = Math.floorMod(originZ, sizeZ);
        this.lookup = lookup;
        this.bricks = bricks;
        this.blocking = blocking;
    }

    /** Traces the ray at a float offset of an array laid out as {@link TraceFormat} says, writing four integers. */
    public void trace(float[] rays, int rayAt, int[] results, int resultAt) {
        trace(rays[rayAt], rays[rayAt + 1], rays[rayAt + 2], rays[rayAt + 3], rays[rayAt + 4], rays[rayAt + 5],
            rays[rayAt + 6], results, resultAt);
    }

    /**
     * Traces one ray.
     *
     * @param ox the origin relative to the origin cube's lowest corner, in blocks
     * @param length how far the ray reaches
     * @param ix the inverse of the direction
     */
    public void trace(float ox, float oy, float oz, float length, float ix, float iy, float iz, int[] results, int at) {
        float[] o = {ox, oy, oz};
        float[] inv = {clamp(ix), clamp(iy), clamp(iz)};
        int[] s = new int[3];
        float[] d = new float[3];
        int[] c = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            s[axis] = inv[axis] > 0.0f ? 1 : -1;
            d[axis] = 1.0f / inv[axis];
            c[axis] = (int) Math.floor(o[axis]);
        }
        float t = 0.0f;
        int outcome = TraceFormat.STEPS;
        int visits = 0;
        int[] n = new int[3];
        float[] exit = new float[3];
        while (visits < MOST_VISITS) {
            visits++;
            int qx = c[0] >> 4;
            int qy = c[1] >> 4;
            int qz = c[2] >> 4;
            if (Math.abs(qx) > across || Math.abs(qz) > across || Math.abs(qy) > upward) {
                outcome = TraceFormat.LEFT;
                break;
            }
            int wx = (modX + qx + sizeX) % sizeX;
            int wy = (modY + qy + sizeY) % sizeY;
            int wz = (modZ + qz + sizeZ) % sizeZ;
            int entry = lookup[(wy * sizeZ + wz) * sizeX + wx];
            int kind = entry >>> 30;
            int size;
            if (kind == 1) {
                int state = entry & 0xFFFF;
                if ((blocking[state >>> 5] >>> (state & 31) & 1) != 0) {
                    outcome = TraceFormat.HIT;
                    break;
                }
                size = 16;
            } else if (kind == 2) {
                int base = (entry & 0xFFFFFF) * PublishedScene.BRICK_INTEGERS;
                int lx = c[0] & 15;
                int ly = c[1] & 15;
                int lz = c[2] & 15;
                int coarse = (ly >> 1) << 6 | (lz >> 1) << 3 | lx >> 1;
                if ((bricks[base + 128 + (coarse >>> 5)] >>> (coarse & 31) & 1) == 0) {
                    size = 2;
                } else {
                    int cell = ly << 8 | lz << 4 | lx;
                    if ((bricks[base + (cell >>> 5)] >>> (cell & 31) & 1) != 0) {
                        outcome = TraceFormat.HIT;
                        break;
                    }
                    size = 1;
                }
            } else {
                outcome = TraceFormat.NOT_AVAILABLE;
                break;
            }

            // Leave the node: the nearest of its far faces, x before y before z where two are equal.
            for (int axis = 0; axis < 3; axis++) {
                n[axis] = c[axis] & -size;
                exit[axis] = ((float) (s[axis] > 0 ? n[axis] + size : n[axis]) - o[axis]) * inv[axis];
            }
            int axis = 0;
            float leave = exit[0];
            if (exit[1] < leave) {
                axis = 1;
                leave = exit[1];
            }
            if (exit[2] < leave) {
                axis = 2;
                leave = exit[2];
            }
            if (leave > length) {
                outcome = TraceFormat.MISS;
                t = length;
                break;
            }
            if (size > 1) {
                for (int other = 0; other < 3; other++) {
                    if (other != axis) {
                        c[other] = within(o[other], inv[other], d[other], s[other], c[other], n[other], size, leave,
                            other < axis);
                    }
                }
            }
            c[axis] = s[axis] > 0 ? n[axis] + size : n[axis] - 1;
            t = leave;
        }
        results[at] = Float.floatToRawIntBits(t);
        results[at + 1] = TraceFormat.cell(c[0], c[1], c[2]);
        results[at + 2] = outcome | visits << 4;
        results[at + 3] = 0;
    }

    /**
     * Along an axis other than the one a node is left by, the cell the ray stands in when it leaves: the
     * last whose near face it crossed before {@code leave}, or at it where this axis comes first.
     */
    private static int within(float o, float inv, float d, int s, int start, int n, int size, float leave, boolean first) {
        int end = s > 0 ? n + size - 1 : n;
        // An estimate, which need not be exact: the crossings below decide.
        float p = Math.min(Math.max(o + leave * d, -MOST_ESTIMATE), MOST_ESTIMATE);
        int e = (int) Math.floor(p);
        e = s > 0 ? Math.min(Math.max(e, start), end) : Math.min(Math.max(e, end), start);
        while (e != start && !crossed(((float) (s > 0 ? e : e + 1) - o) * inv, leave, first)) {
            e -= s;
        }
        while (e != end && crossed(((float) (s > 0 ? e + 1 : e) - o) * inv, leave, first)) {
            e += s;
        }
        return e;
    }

    private static boolean crossed(float at, float leave, boolean first) {
        return at < leave || at == leave && first;
    }

    private static float clamp(float inverse) {
        return Math.min(Math.max(inverse, -MOST_INVERSE), MOST_INVERSE);
    }
}
