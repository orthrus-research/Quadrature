package cc.orthrus.quadrature.core.view;

/**
 * An orthographic box along a light, fixed in the world: the direction toward the light, the two axes
 * across it, the box's center on those three axes measured from the world's origin and moved to a
 * whole texel, its side across the light and its depth along it.
 *
 * <p>A box is made once and kept while the light's depth is drawn through it; a {@link View} through
 * it is made for each frame's origin ({@link View#throughLight}), so a map drawn in one frame and
 * sampled in another lines up to the precision of one frame's matrices, with nothing carried from
 * frame to frame. Positions here are world positions in blocks, in double precision.
 */
public final class LightBox {

    final double ax;
    final double ay;
    final double az;
    final double bx;
    final double by;
    final double bz;
    final double lx;
    final double ly;
    final double lz;
    /** The center along each axis, from the world's origin: whole texels. */
    final double centerA;
    final double centerB;
    final double centerL;
    final double side;
    final double depth;
    final int texels;

    private LightBox(double lx, double ly, double lz, double x, double y, double z, double side, double depth,
        int texels) {
        this.lx = lx;
        this.ly = ly;
        this.lz = lz;
        // Across the light: the first axis is up crossed with the light, or x where the light is
        // nearly straight up; the second completes the frame.
        double ux = Math.abs(ly) < 0.99 ? 0 : 1;
        double uy = Math.abs(ly) < 0.99 ? 1 : 0;
        double a0 = uy * lz;
        double a1 = -ux * lz;
        double a2 = ux * ly - uy * lx;
        double length = Math.sqrt(a0 * a0 + a1 * a1 + a2 * a2);
        ax = a0 / length;
        ay = a1 / length;
        az = a2 / length;
        bx = ly * az - lz * ay;
        by = lz * ax - lx * az;
        bz = lx * ay - ly * ax;
        this.side = side;
        this.depth = depth;
        this.texels = texels;
        double texel = side / texels;
        centerA = Math.rint((ax * x + ay * y + az * z) / texel) * texel;
        centerB = Math.rint((bx * x + by * y + bz * z) / texel) * texel;
        centerL = Math.rint((lx * x + ly * y + lz * z) / texel) * texel;
    }

    /**
     * The box centered on a place, at the whole texel nearest it.
     *
     * @param towardX a vector from the scene toward the light; it need not be of unit length
     * @param side    the side across the light, in blocks
     * @param depth   the extent along the light, in blocks
     * @param texels  texels along a side of the map drawn through it
     */
    public static LightBox around(double towardX, double towardY, double towardZ, double x, double y, double z,
        double side, double depth, int texels) {
        double length = Math.sqrt(towardX * towardX + towardY * towardY + towardZ * towardZ);
        return new LightBox(towardX / length, towardY / length, towardZ / length, x, y, z, side, depth, texels);
    }

    public double side() {
        return side;
    }

    public double depth() {
        return depth;
    }

    public int texels() {
        return texels;
    }

    /** Blocks a texel covers across the light. */
    public double texel() {
        return side / texels;
    }

    /** The unit vector toward the light the box was made for, by component: 0, 1 or 2. */
    public double toward(int axis) {
        return axis == 0 ? lx : axis == 1 ? ly : lz;
    }

    /** Whether the box has this side and depth. */
    public boolean sized(double otherSide, double otherDepth) {
        return side == otherSide && depth == otherDepth;
    }

    /** The cosine of the angle between the box's light and another direction toward a light. */
    public double cosine(double towardX, double towardY, double towardZ) {
        double length = Math.sqrt(towardX * towardX + towardY * towardY + towardZ * towardZ);
        return (lx * towardX + ly * towardY + lz * towardZ) / length;
    }

    /** How far a place is from the box's center, the most along any of its three axes, in blocks. */
    public double offCenter(double x, double y, double z) {
        double a = Math.abs(ax * x + ay * y + az * z - centerA);
        double b = Math.abs(bx * x + by * y + bz * z - centerB);
        double l = Math.abs(lx * x + ly * y + lz * z - centerL);
        return Math.max(a, Math.max(b, l));
    }

    /**
     * How far past its own cell a block's model may reach, in blocks: the game moves flowers and grass up
     * to a quarter of a block aside, and other mods' models can stand further out. A cube's footprint is
     * widened by this much each way.
     */
    public static final double OVERHANG = 1.0;

    /**
     * The texels a cube may cover in a map drawn through the box, as an inclusive range across each of
     * the box's two axes: the cube widened by {@link #OVERHANG} each way, and by a texel more so that a
     * cube touching a texel's edge is counted in it.
     *
     * @param out filled with the first and last texel along the first axis, then along the second
     * @return whether any of it lies across the map
     */
    public boolean texelsOf(int cubeX, int cubeY, int cubeZ, int[] out) {
        double x = (cubeX << 4) + 8.0;
        double y = (cubeY << 4) + 8.0;
        double z = (cubeZ << 4) + 8.0;
        double texel = side / texels;
        double half = side / 2;
        // A box's half extent along an axis is its half side times the sum of the axis's absolute components.
        double reachA = (8 + OVERHANG) * (Math.abs(ax) + Math.abs(ay) + Math.abs(az));
        double reachB = (8 + OVERHANG) * (Math.abs(bx) + Math.abs(by) + Math.abs(bz));
        double a = ax * x + ay * y + az * z - centerA + half;
        double b = bx * x + by * y + bz * z - centerB + half;
        int a0 = (int) Math.floor((a - reachA) / texel) - 1;
        int a1 = (int) Math.ceil((a + reachA) / texel) + 1;
        int b0 = (int) Math.floor((b - reachB) / texel) - 1;
        int b1 = (int) Math.ceil((b + reachB) / texel) + 1;
        if (a1 < 0 || b1 < 0 || a0 >= texels || b0 >= texels) {
            return false;
        }
        out[0] = Math.max(0, a0);
        out[1] = Math.min(texels - 1, a1);
        out[2] = Math.max(0, b0);
        out[3] = Math.min(texels - 1, b1);
        return true;
    }
}
