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
     * Fills {@code out} as the view through a light's box for {@code main}'s origin: the box's axes as
     * the model-view, with no translation, and an orthographic projection whose center is the box's
     * center taken from that origin. Positions are relative to the origin, as in every view; clip depth
     * runs from -1 nearest the light to 1 farthest. It has no reach of its own ({@link #UNBOUNDED}):
     * what it holds is bounded by the box alone, so that what a layer drawn through it holds depends on
     * the meshes held and not on which cube the player stood in when each part was drawn.
     */
    public static View throughLight(View out, View main, LightBox box) {
        float[] view = out.modelView;
        java.util.Arrays.fill(view, 0);
        view[0] = (float) box.ax;
        view[4] = (float) box.ay;
        view[8] = (float) box.az;
        view[1] = (float) box.bx;
        view[5] = (float) box.by;
        view[9] = (float) box.bz;
        view[2] = (float) box.lx;
        view[6] = (float) box.ly;
        view[10] = (float) box.lz;
        view[15] = 1;
        // The box's center from the origin, worked out in double precision before it is rounded.
        double centerA = box.centerA - (box.ax * main.originX + box.ay * main.originY + box.az * main.originZ);
        double centerB = box.centerB - (box.bx * main.originX + box.by * main.originY + box.bz * main.originZ);
        double centerL = box.centerL - (box.lx * main.originX + box.ly * main.originY + box.lz * main.originZ);
        double half = box.side / 2;
        double deep = box.depth / 2;
        float[] projection = out.projection;
        java.util.Arrays.fill(projection, 0);
        projection[0] = (float) (1 / half);
        projection[5] = (float) (1 / half);
        // Eye space looks down its negative third axis: the nearest point is the one most toward the light.
        projection[10] = (float) (-1 / deep);
        projection[12] = (float) (-centerA / half);
        projection[13] = (float) (-centerB / half);
        projection[14] = (float) (centerL / deep);
        projection[15] = 1;
        out.purpose = Purpose.DEPTH_ONLY;
        out.depth = Depth.CONVENTIONAL;
        out.originX = main.originX;
        out.originY = main.originY;
        out.originZ = main.originZ;
        out.eyeX = main.originX;
        out.eyeY = main.originY;
        out.eyeZ = main.originZ;
        out.across = UNBOUNDED;
        out.upward = UNBOUNDED;
        out.occlusion = false;
        out.derivePlanes();
        return out;
    }

    /** A reach in cubes that holds every cube a world can have: the view's volume alone bounds it. */
    public static final int UNBOUNDED = Integer.MAX_VALUE / 4;

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
