package cc.orthrus.quadrature.core.depth;

/**
 * The arithmetic of reversed depth: the near plane maps to depth 1 and the far plane to 0, in a clip
 * volume whose depth runs from 0 to w.
 *
 * <p>Hosted code keeps the conventional view: it sets "less or equal", clears depth to 1 and builds
 * projections whose clip depth runs from -w to w. Each value it sets is translated here on its way to
 * the driver, and each value it reads back is translated the other way, so the two views never mix.
 * Every translation is its own inverse except the projection pair.
 *
 * <p>Matrices are 16 values in column-major order, as OpenGL takes them.
 */
public final class ReversedDepth {

    public static final int NEVER = 0x0200;
    public static final int LESS = 0x0201;
    public static final int EQUAL = 0x0202;
    public static final int LESS_OR_EQUAL = 0x0203;
    public static final int GREATER = 0x0204;
    public static final int NOT_EQUAL = 0x0205;
    public static final int GREATER_OR_EQUAL = 0x0206;
    public static final int ALWAYS = 0x0207;

    private ReversedDepth() {
    }

    /** The comparison that passes the same fragments once depth is reversed. */
    public static int comparison(int function) {
        switch (function) {
            case LESS:
                return GREATER;
            case GREATER:
                return LESS;
            case LESS_OR_EQUAL:
                return GREATER_OR_EQUAL;
            case GREATER_OR_EQUAL:
                return LESS_OR_EQUAL;
            default:
                return function;
        }
    }

    /** A depth value, such as the value the depth buffer is cleared to. */
    public static double depth(double conventional) {
        return 1.0 - conventional;
    }

    /** A polygon offset factor or unit count: the offset pushes the other way. */
    public static float offset(float conventional) {
        return -conventional;
    }

    /**
     * Turns a conventional projection into a reversed one, in place: clip depth z becomes
     * (w - z) / 2. Whatever is multiplied onto the result afterwards keeps the reversal.
     */
    public static void reverse(double[] projection) {
        for (int column = 0; column < 4; column++) {
            int z = column * 4 + 2;
            projection[z] = 0.5 * (projection[z + 1] - projection[z]);
        }
    }

    /** The inverse of {@link #reverse}: clip depth z becomes w - 2z. */
    public static void restore(double[] projection) {
        for (int column = 0; column < 4; column++) {
            int z = column * 4 + 2;
            projection[z] = projection[z + 1] - 2.0 * projection[z];
        }
    }
}
