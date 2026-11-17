package cc.orthrus.quadrature.core.fade;

/**
 * The numbers of the fade at the edge of near terrain, apart from the game: the band it runs over,
 * the place its distance is measured from, and the share that shows at a place. Near terrain's
 * fragment stages do the same sums; what draws beyond reads these to do them too.
 *
 * <p>The distance is horizontal and square: the greater of the distance along x and the distance
 * along z from the origin, in blocks, since what near terrain holds is a square of chunk columns.
 */
public final class NearFade {

    /** How far before the last chunk of the view distance the band begins and ends where none is set. */
    public static final float DEFAULT_START = 32f;
    public static final float DEFAULT_END = 16f;

    private NearFade() {
    }

    /**
     * The band in use: where near terrain still shows whole, and from where none of it shows.
     *
     * @param setStart the start as set, or not a number where none was
     * @param setEnd   the end as set, or not a number where none was
     * @param across   the view distance in chunks
     * @return start and end in blocks, the end at least one block past the start
     */
    public static float[] band(float setStart, float setEnd, int across) {
        float reach = across * 16f;
        float start = Float.isNaN(setStart) ? reach - DEFAULT_START : setStart;
        float end = Float.isNaN(setEnd) ? reach - DEFAULT_END : setEnd;
        return new float[] {start, Math.max(end, start + 1f)};
    }

    /**
     * The place the fragment stages measure from: where the eye of the model-view matrix stands. That
     * is the view's origin moved by the matrix's own translation, which is nothing for a first-person
     * view at rest and the camera's way back for a view from behind.
     *
     * @param originX   the view's origin, to which the cubes' places are relative
     * @param modelView the view's model-view matrix, column-major: a rotation and a translation
     * @return x, y and z in the world
     */
    public static double[] origin(double originX, double originY, double originZ, float[] modelView) {
        double tx = modelView[12];
        double ty = modelView[13];
        double tz = modelView[14];
        // The eye is where the matrix sends to nothing: minus the rotation undone, of the translation.
        double x = -(modelView[0] * tx + modelView[1] * ty + modelView[2] * tz);
        double y = -(modelView[4] * tx + modelView[5] * ty + modelView[6] * tz);
        double z = -(modelView[8] * tx + modelView[9] * ty + modelView[10] * tz);
        return new double[] {originX + x, originY + y, originZ + z};
    }

    /** The distance the fade goes by, from an origin to a place: the greater of that along x and that along z. */
    public static double distance(double[] origin, double x, double z) {
        return Math.max(Math.abs(x - origin[0]), Math.abs(z - origin[2]));
    }

    /**
     * How much of what is drawn beyond near terrain shows at a distance, 0 to 1: the fade both sides
     * give {@link FadePattern}, near terrain as the complement side.
     */
    public static float shows(double distance, float[] band) {
        return (float) Math.max(0, Math.min(1, (distance - band[0]) / (band[1] - band[0])));
    }
}
