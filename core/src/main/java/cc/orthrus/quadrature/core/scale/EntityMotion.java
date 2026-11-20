package cc.orthrus.quadrature.core.scale;

/**
 * Where a point on an entity the game draws lay the frame before (research R4, 7b.5). The game draws an entity at its
 * interpolated position, turned about the vertical axis by {@code 180 - yaw} degrees; between two frames an entity
 * moves rigidly as far as its position and yaw go (a limb's own swing is not covered). A point on it, given relative to
 * this frame's origin, lies in the entity's own frame at {@code R(yaw - 180) (q + origin - position)}, and the frame
 * before at {@code previousPosition + R(180 - previousYaw)} of that; relative to the previous frame's origin:
 *
 * <pre>    M = T(previousPosition - previousOrigin) R(yaw - previousYaw) T(origin - position)</pre>
 *
 * where {@code R(a)} turns by {@code a} degrees about the vertical axis as the fixed-function {@code glRotate} does.
 */
public final class EntityMotion {

    private EntityMotion() {
    }

    /**
     * The matrix, column-major, taking a point on the entity relative to this frame's origin to the same point the
     * frame before, relative to that frame's origin.
     *
     * @param position         the entity's interpolated position this frame, in the world
     * @param yaw              the yaw it is drawn with this frame, in degrees
     * @param previousPosition the same the frame before
     * @param previousYaw      the same the frame before
     * @param origin           this frame's origin, the place the view's coordinates are relative to
     * @param previousOrigin   the previous frame's origin
     */
    public static double[] toPrevious(double[] position, double yaw, double[] previousPosition, double previousYaw,
        double[] origin, double[] previousOrigin) {
        double turn = Math.toRadians(yaw - previousYaw);
        double cos = Math.cos(turn);
        double sin = Math.sin(turn);
        // glRotate(a, 0, 1, 0): x' = cos x + sin z, z' = -sin x + cos z.
        double ax = origin[0] - position[0];
        double ay = origin[1] - position[1];
        double az = origin[2] - position[2];
        // R applied to the first translation, then the second translation added.
        double tx = cos * ax + sin * az + previousPosition[0] - previousOrigin[0];
        double ty = ay + previousPosition[1] - previousOrigin[1];
        double tz = -sin * ax + cos * az + previousPosition[2] - previousOrigin[2];
        return new double[] {
            cos, 0, -sin, 0,
            0, 1, 0, 0,
            sin, 0, cos, 0,
            tx, ty, tz, 1};
    }
}
