package cc.orthrus.quadrature.core.view;

/**
 * Where the sun and the moon stand, as directions in the world: east is +x, up is +y, south is +z.
 *
 * <p>The game's sun circles the north-south axis and passes straight overhead. A tilt leans that
 * path toward the south, so that the sun at noon stands short of overhead and shadows at noon have a
 * length. The tilt is given by its effect: degrees toward the south at noon.
 *
 * <p>With {@code a} the game's celestial angle as an angle, 0 at noon, a quarter turn at sunset, a
 * half turn at midnight, three quarters at sunrise, and {@code t} the tilt:
 *
 * <pre>
 * sun  = ( -sin a,  cos a * cos t,  cos a * sin t )
 * moon = -sun
 * </pre>
 *
 * The game draws its own sky and knows nothing of the tilt: with a tilt the sun the game draws and the
 * direction given here differ by it.
 */
public final class SunPath {

    private SunPath() {
    }

    /**
     * The unit direction from the scene toward the sun.
     *
     * @param celestialAngle the game's celestial angle, 0 to 1 over a day, 0 at noon
     * @param tiltDegrees    how far toward the south the sun stands at noon, in degrees; 0 for the game's own path
     * @param out            receives x, y and z
     */
    public static void towardSun(double celestialAngle, double tiltDegrees, double[] out) {
        double a = celestialAngle * 2 * Math.PI;
        double t = Math.toRadians(tiltDegrees);
        double height = Math.cos(a);
        out[0] = -Math.sin(a);
        out[1] = height * Math.cos(t);
        out[2] = tiltDegrees == 0 ? 0 : height * Math.sin(t);
    }

    /** The unit direction toward the moon: opposite the sun. */
    public static void towardMoon(double celestialAngle, double tiltDegrees, double[] out) {
        towardSun(celestialAngle, tiltDegrees, out);
        out[0] = -out[0];
        out[1] = -out[1];
        // Nothing stays nothing, not minus nothing: with no tilt the third number is 0 as it always was.
        out[2] = 0.0 - out[2];
    }

    /**
     * The unit direction toward whichever of the two is up and so casts shadows: the sun while it is
     * above the horizon, the moon otherwise.
     *
     * @return whether it is the sun
     */
    public static boolean towardLight(double celestialAngle, double tiltDegrees, double[] out) {
        towardSun(celestialAngle, tiltDegrees, out);
        if (out[1] < 0) {
            out[0] = -out[0];
            out[1] = -out[1];
            // Nothing stays nothing, not minus nothing: with no tilt the third number is 0 as it always was.
            out[2] = 0.0 - out[2];
            return false;
        }
        return true;
    }
}
