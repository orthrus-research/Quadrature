package cc.orthrus.quadrature.core.view;

/**
 * What terrain is drawn for: a position, a pair of matrices, the volume they see and how far the view
 * reaches. Visibility and drawing take a view, so nothing in them assumes the player's camera.
 *
 * <p>One instance is filled again each frame; it allocates nothing after construction. Matrices are
 * column-major, in single precision, as the driver holds them. The model-view matrix maps a world
 * position minus the origin into eye space.
 */
public final class View {

    /** What the view is drawn for. */
    public enum Purpose {
        /** The frame the player sees. */
        MAIN,
        /** Depth alone, as a light's view needs. */
        DEPTH_ONLY
    }

    /** How clip-space depth runs. */
    public enum Depth {
        /** Clip depth from -1 at the near plane to 1 at the far plane. */
        CONVENTIONAL,
        /** Clip depth from 1 at the near plane to 0 at the far plane. */
        REVERSED
    }

    /** How far outside a plane, in blocks, a box may lie and still count as seen. */
    private static final double MARGIN = 0.0625;

    private Purpose purpose = Purpose.MAIN;
    private Depth depth = Depth.CONVENTIONAL;
    private double originX;
    private double originY;
    private double originZ;
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private final float[] modelView = new float[16];
    private final float[] projection = new float[16];
    /** Six planes, four numbers each, in world space relative to the origin; inside is non-negative. */
    private final double[] planes = new double[24];
    private int across;
    private int upward;
    private boolean occlusion;

    /**
     * @param eyeX      the point a search for what is visible starts from; for the player's view the
     *                  viewer's eyes, which third-person views place away from the camera
     * @param across    the horizontal reach, in cubes from the origin's cube
     * @param upward    the vertical reach, in cubes from the origin's cube
     * @param occlusion whether terrain that blocks the line of sight may hide what lies behind it
     */
    public View set(Purpose purpose, Depth depth, double originX, double originY, double originZ, double eyeX,
        double eyeY, double eyeZ, float[] modelView, float[] projection, int across, int upward,
        boolean occlusion) {
        this.purpose = purpose;
        this.depth = depth;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        System.arraycopy(modelView, 0, this.modelView, 0, 16);
        System.arraycopy(projection, 0, this.projection, 0, 16);
        this.across = across;
        this.upward = upward;
        this.occlusion = occlusion;
        derivePlanes();
        return this;
    }

    /**
     * Fills {@code out} as a view along a light's direction that holds everything {@code main} sees out
     * to its horizontal reach: an orthographic box around the main view's volume, extended toward the
     * light by that reach again so that what stands between the light and the volume is inside it.
     *
     * @param towardLightX a vector from the scene toward the light; it need not be of unit length
     */
    public static View alongLight(View out, View main, double towardLightX, double towardLightY,
        double towardLightZ) {
        double length = Math.sqrt(towardLightX * towardLightX + towardLightY * towardLightY
            + towardLightZ * towardLightZ);
        double lx = towardLightX / length;
        double ly = towardLightY / length;
        double lz = towardLightZ / length;
        // A basis with its third axis toward the light.
        double ux = Math.abs(ly) < 0.99 ? 0 : 1;
        double uy = Math.abs(ly) < 0.99 ? 1 : 0;
        double ax = uy * lz;
        double ay = -ux * lz;
        double az = ux * ly - uy * lx;
        double an = Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= an;
        ay /= an;
        az /= an;
        double bx = ly * az - lz * ay;
        double by = lz * ax - lx * az;
        double bz = lx * ay - ly * ax;

        // The main view's camera, its axes, and the corners of what it sees at its reach.
        float[] m = main.modelView;
        double cx = -(m[0] * m[12] + m[1] * m[13] + m[2] * m[14]);
        double cy = -(m[4] * m[12] + m[5] * m[13] + m[6] * m[14]);
        double cz = -(m[8] * m[12] + m[9] * m[13] + m[10] * m[14]);
        double reach = main.across * 16.0;
        double tanX = 1.0 / Math.abs(main.projection[0]);
        double tanY = 1.0 / Math.abs(main.projection[5]);
        double minA = ax * cx + ay * cy + az * cz;
        double maxA = minA;
        double minB = bx * cx + by * cy + bz * cz;
        double maxB = minB;
        double minL = lx * cx + ly * cy + lz * cz;
        double maxL = minL;
        for (int corner = 0; corner < 4; corner++) {
            double sx = (corner & 1) == 0 ? -tanX : tanX;
            double sy = (corner & 2) == 0 ? -tanY : tanY;
            // Right, up and backward are the rows of the model-view's rotation.
            double px = cx + reach * (sx * m[0] + sy * m[1] - m[2]);
            double py = cy + reach * (sx * m[4] + sy * m[5] - m[6]);
            double pz = cz + reach * (sx * m[8] + sy * m[9] - m[10]);
            double a = ax * px + ay * py + az * pz;
            double b = bx * px + by * py + bz * pz;
            double l = lx * px + ly * py + lz * pz;
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minB = Math.min(minB, b);
            maxB = Math.max(maxB, b);
            minL = Math.min(minL, l);
            maxL = Math.max(maxL, l);
        }
        maxL += reach;

        float[] view = out.modelView;
        java.util.Arrays.fill(view, 0);
        view[0] = (float) ax;
        view[4] = (float) ay;
        view[8] = (float) az;
        view[1] = (float) bx;
        view[5] = (float) by;
        view[9] = (float) bz;
        view[2] = (float) lx;
        view[6] = (float) ly;
        view[10] = (float) lz;
        view[15] = 1;
        // Eye space looks down its negative third axis: the nearest point is the one most toward the light.
        double near = -maxL;
        double far = -minL;
        float[] box = out.projection;
        java.util.Arrays.fill(box, 0);
        box[0] = (float) (2 / (maxA - minA));
        box[5] = (float) (2 / (maxB - minB));
        box[10] = (float) (-2 / (far - near));
        box[12] = (float) (-(maxA + minA) / (maxA - minA));
        box[13] = (float) (-(maxB + minB) / (maxB - minB));
        box[14] = (float) (-(far + near) / (far - near));
        box[15] = 1;
        out.purpose = Purpose.DEPTH_ONLY;
        out.depth = Depth.CONVENTIONAL;
        out.originX = main.originX;
        out.originY = main.originY;
        out.originZ = main.originZ;
        out.eyeX = main.originX;
        out.eyeY = main.originY;
        out.eyeZ = main.originZ;
        out.across = main.across;
        out.upward = main.upward;
        out.occlusion = false;
        out.derivePlanes();
        return out;
    }

    /** The planes of the clip volume, from the rows of projection times model-view. */
    private void derivePlanes() {
        double[] clip = new double[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += (double) projection[k * 4 + row] * modelView[column * 4 + k];
                }
                clip[column * 4 + row] = sum;
            }
        }
        plane(0, clip, 3, 0, 1);
        plane(1, clip, 3, 0, -1);
        plane(2, clip, 3, 1, 1);
        plane(3, clip, 3, 1, -1);
        if (depth == Depth.REVERSED) {
            // Clip depth runs from 0 to the fourth coordinate.
            plane(4, clip, 2, 2, 0);
        } else {
            plane(4, clip, 3, 2, 1);
        }
        plane(5, clip, 3, 2, -1);
    }

    /** Plane {@code index} is row {@code a} plus {@code sign} times row {@code b}, with a unit normal. */
    private void plane(int index, double[] clip, int a, int b, int sign) {
        double x = clip[a] + sign * clip[b];
        double y = clip[4 + a] + sign * clip[4 + b];
        double z = clip[8 + a] + sign * clip[8 + b];
        double w = clip[12 + a] + sign * clip[12 + b];
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length == 0) {
            // A degenerate row excludes nothing.
            x = 0;
            y = 0;
            z = 0;
            w = 1;
            length = 1;
        }
        planes[index * 4] = x / length;
        planes[index * 4 + 1] = y / length;
        planes[index * 4 + 2] = z / length;
        planes[index * 4 + 3] = w / length;
    }

    /** Whether any of a world-space box may be inside the view's volume. */
    public boolean boxVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double x0 = minX - originX;
        double y0 = minY - originY;
        double z0 = minZ - originZ;
        double x1 = maxX - originX;
        double y1 = maxY - originY;
        double z1 = maxZ - originZ;
        for (int i = 0; i < 24; i += 4) {
            double a = planes[i];
            double b = planes[i + 1];
            double c = planes[i + 2];
            // The corner farthest along the plane's normal.
            double reach = a * (a >= 0 ? x1 : x0) + b * (b >= 0 ? y1 : y0) + c * (c >= 0 ? z1 : z0) + planes[i + 3];
            if (reach < -MARGIN) {
                return false;
            }
        }
        return true;
    }

    /** Whether any of a cube may be inside the view's volume. */
    public boolean cubeVisible(int cubeX, int cubeY, int cubeZ) {
        double x = cubeX * 16.0;
        double y = cubeY * 16.0;
        double z = cubeZ * 16.0;
        return boxVisible(x, y, z, x + 16, y + 16, z + 16);
    }

    /** Whether a cube is within the view's reach of the origin's cube, counted in cubes on each axis. */
    public boolean cubeWithinReach(int cubeX, int cubeY, int cubeZ) {
        return Math.abs(cubeX - originCubeX()) <= across && Math.abs(cubeY - originCubeY()) <= upward
            && Math.abs(cubeZ - originCubeZ()) <= across;
    }

    public Purpose purpose() {
        return purpose;
    }

    public Depth depth() {
        return depth;
    }

    public double originX() {
        return originX;
    }

    public double originY() {
        return originY;
    }

    public double originZ() {
        return originZ;
    }

    public int originCubeX() {
        return (int) Math.floor(originX) >> 4;
    }

    public int originCubeY() {
        return (int) Math.floor(originY) >> 4;
    }

    public int originCubeZ() {
        return (int) Math.floor(originZ) >> 4;
    }

    public int eyeCubeX() {
        return (int) Math.floor(eyeX) >> 4;
    }

    public int eyeCubeY() {
        return (int) Math.floor(eyeY) >> 4;
    }

    public int eyeCubeZ() {
        return (int) Math.floor(eyeZ) >> 4;
    }

    /** The model-view matrix, column-major; not to be written to. */
    public float[] modelView() {
        return modelView;
    }

    /** The projection matrix, column-major; not to be written to. */
    public float[] projection() {
        return projection;
    }

    public int across() {
        return across;
    }

    public int upward() {
        return upward;
    }

    public boolean occlusion() {
        return occlusion;
    }

    /** Whether this view is the same as another in everything a search for what is visible reads. */
    public boolean sameVolume(View other) {
        return originX == other.originX && originY == other.originY && originZ == other.originZ
            && eyeX == other.eyeX && eyeY == other.eyeY && eyeZ == other.eyeZ && across == other.across
            && upward == other.upward && occlusion == other.occlusion && depth == other.depth
            && java.util.Arrays.equals(modelView, other.modelView)
            && java.util.Arrays.equals(projection, other.projection);
    }

    /** Copies another view into this one. */
    public View copy(View other) {
        return set(other.purpose, other.depth, other.originX, other.originY, other.originZ, other.eyeX, other.eyeY,
            other.eyeZ, other.modelView, other.projection, other.across, other.upward, other.occlusion);
    }
}
