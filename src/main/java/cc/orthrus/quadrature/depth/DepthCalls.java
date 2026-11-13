package cc.orthrus.quadrature.depth;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.depth.ReversedDepth;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL45;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Where every depth-related OpenGL call in the game and in other mods arrives once the call rewriter
 * has pointed it here. Each method has the name and signature of the call it replaces.
 *
 * <p>Until reversed depth is switched on, and on any thread but the render thread, every method passes
 * its arguments straight through. Once it is on, values are translated on the way to the driver and
 * translated back when read, so callers keep seeing the conventional values they set.
 *
 * <p>Quadrature's own code is not rewritten: it calls OpenGL directly and sees the real values.
 */
public final class DepthCalls {

    /** A prototype switch: {@code -Dquadrature.reversedDepth=true}. */
    public static final boolean REQUESTED = Boolean.getBoolean("quadrature.reversedDepth");

    private static final int PROJECTION = 0x1701;
    private static final int MATRIX_MODE = 0x0BA0;
    private static final int PROJECTION_MATRIX = 0x0BA7;
    private static final int DEPTH_RANGE = 0x0B70;
    private static final int DEPTH_CLEAR_VALUE = 0x0B73;
    private static final int DEPTH_FUNC = 0x0B74;
    private static final int POLYGON_OFFSET_UNITS = 0x2A00;
    private static final int POLYGON_OFFSET_FACTOR = 0x8038;

    private static Thread renderThread;
    private static int matrixMode;
    private static final double[] scratch = new double[16];
    private static final float[] scratchFloats = new float[16];

    private DepthCalls() {
    }

    private static boolean translating() {
        return renderThread != null && Thread.currentThread() == renderThread;
    }

    /** Whether reversed depth is on. */
    public static boolean active() {
        return renderThread != null;
    }

    /**
     * Switches reversed depth on for the current context, once, from the render thread. State set
     * before this moment went to the driver untranslated, so it is read back and set again.
     */
    public static void enable() {
        if (!REQUESTED || renderThread != null) {
            return;
        }
        GL45.glClipControl(GL45.GL_LOWER_LEFT, GL45.GL_ZERO_TO_ONE);
        int function = GL11.glGetInteger(DEPTH_FUNC);
        double clear = GL11.glGetDouble(DEPTH_CLEAR_VALUE);
        float factor = GL11.glGetFloat(POLYGON_OFFSET_FACTOR);
        float units = GL11.glGetFloat(POLYGON_OFFSET_UNITS);
        double[] range = new double[2];
        GL11.glGetDoublev(DEPTH_RANGE, range);
        matrixMode = GL11.glGetInteger(MATRIX_MODE);
        GL11.glGetFloatv(PROJECTION_MATRIX, scratchFloats);

        renderThread = Thread.currentThread();
        glDepthFunc(function);
        glClearDepth(clear);
        glPolygonOffset(factor, units);
        glDepthRange(range[0], range[1]);
        int mode = matrixMode;
        glMatrixMode(PROJECTION);
        glLoadMatrixf(scratchFloats.clone());
        glMatrixMode(mode);
        Quadrature.LOGGER.info("Reversed depth is on: clip depth 0 to 1, near plane at 1.");
    }

    // Depth state.

    public static void glDepthFunc(int function) {
        GL11.glDepthFunc(translating() ? ReversedDepth.comparison(function) : function);
    }

    public static void glClearDepth(double depth) {
        GL11.glClearDepth(translating() ? ReversedDepth.depth(depth) : depth);
    }

    public static void glClearDepthf(float depth) {
        glClearDepth(depth);
    }

    public static void glPolygonOffset(float factor, float units) {
        if (translating()) {
            GL11.glPolygonOffset(ReversedDepth.offset(factor), ReversedDepth.offset(units));
        } else {
            GL11.glPolygonOffset(factor, units);
        }
    }

    public static void glDepthRange(double near, double far) {
        if (translating()) {
            GL11.glDepthRange(ReversedDepth.depth(far), ReversedDepth.depth(near));
        } else {
            GL11.glDepthRange(near, far);
        }
    }

    public static void glDepthRangef(float near, float far) {
        if (translating()) {
            glDepthRange(near, far);
        } else {
            GL41.glDepthRangef(near, far);
        }
    }

    // The projection stack. Its base is the reversal instead of identity; what callers multiply onto
    // it afterwards keeps the reversal.

    public static void glMatrixMode(int mode) {
        matrixMode = mode;
        GL11.glMatrixMode(mode);
    }

    public static void glPopAttrib() {
        GL11.glPopAttrib();
        if (translating()) {
            // The attribute stack can restore a matrix mode this class never saw set.
            matrixMode = GL11.glGetInteger(MATRIX_MODE);
        }
    }

    public static void glLoadIdentity() {
        if (translating() && matrixMode == PROJECTION) {
            loadReversed(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        } else {
            GL11.glLoadIdentity();
        }
    }

    public static void glLoadMatrixf(float[] matrix) {
        if (translating() && matrixMode == PROJECTION) {
            for (int i = 0; i < 16; i++) {
                scratch[i] = matrix[i];
            }
            loadReversed();
        } else {
            GL11.glLoadMatrixf(matrix);
        }
    }

    public static void glLoadMatrixf(FloatBuffer matrix) {
        if (translating() && matrixMode == PROJECTION) {
            for (int i = 0; i < 16; i++) {
                scratch[i] = matrix.get(matrix.position() + i);
            }
            loadReversed();
        } else {
            GL11.glLoadMatrixf(matrix);
        }
    }

    public static void glLoadMatrix(FloatBuffer matrix) {
        glLoadMatrixf(matrix);
    }

    public static void glLoadMatrixd(double[] matrix) {
        if (translating() && matrixMode == PROJECTION) {
            System.arraycopy(matrix, 0, scratch, 0, 16);
            loadReversed();
        } else {
            GL11.glLoadMatrixd(matrix);
        }
    }

    public static void glLoadMatrixd(DoubleBuffer matrix) {
        if (translating() && matrixMode == PROJECTION) {
            for (int i = 0; i < 16; i++) {
                scratch[i] = matrix.get(matrix.position() + i);
            }
            loadReversed();
        } else {
            GL11.glLoadMatrixd(matrix);
        }
    }

    public static void glLoadMatrix(DoubleBuffer matrix) {
        glLoadMatrixd(matrix);
    }

    private static void loadReversed(double... matrix) {
        System.arraycopy(matrix, 0, scratch, 0, 16);
        loadReversed();
    }

    private static void loadReversed() {
        ReversedDepth.reverse(scratch);
        GL11.glLoadMatrixd(scratch);
    }

    // Reading state back.

    public static int glGetInteger(int name) {
        int value = GL11.glGetInteger(name);
        return translating() && name == DEPTH_FUNC ? ReversedDepth.comparison(value) : value;
    }

    public static void glGetIntegerv(int name, IntBuffer into) {
        GL11.glGetIntegerv(name, into);
        if (translating() && name == DEPTH_FUNC) {
            into.put(into.position(), ReversedDepth.comparison(into.get(into.position())));
        }
    }

    public static void glGetInteger(int name, IntBuffer into) {
        glGetIntegerv(name, into);
    }

    public static void glGetIntegerv(int name, int[] into) {
        GL11.glGetIntegerv(name, into);
        if (translating() && name == DEPTH_FUNC) {
            into[0] = ReversedDepth.comparison(into[0]);
        }
    }

    public static float glGetFloat(int name) {
        float value = GL11.glGetFloat(name);
        if (!translating()) {
            return value;
        }
        switch (name) {
            case DEPTH_CLEAR_VALUE:
                return (float) ReversedDepth.depth(value);
            case POLYGON_OFFSET_FACTOR:
            case POLYGON_OFFSET_UNITS:
                return ReversedDepth.offset(value);
            default:
                return value;
        }
    }

    public static double glGetDouble(int name) {
        double value = GL11.glGetDouble(name);
        if (!translating()) {
            return value;
        }
        switch (name) {
            case DEPTH_CLEAR_VALUE:
                return ReversedDepth.depth(value);
            case POLYGON_OFFSET_FACTOR:
            case POLYGON_OFFSET_UNITS:
                return -value;
            default:
                return value;
        }
    }

    public static void glGetFloatv(int name, float[] into) {
        GL11.glGetFloatv(name, into);
        if (translating() && translated(name)) {
            int count = count(name);
            for (int i = 0; i < count; i++) {
                scratch[i] = into[i];
            }
            restore(name);
            for (int i = 0; i < count; i++) {
                into[i] = (float) scratch[i];
            }
        }
    }

    public static void glGetFloatv(int name, FloatBuffer into) {
        GL11.glGetFloatv(name, into);
        if (translating() && translated(name)) {
            int count = count(name);
            int at = into.position();
            for (int i = 0; i < count; i++) {
                scratch[i] = into.get(at + i);
            }
            restore(name);
            for (int i = 0; i < count; i++) {
                into.put(at + i, (float) scratch[i]);
            }
        }
    }

    public static void glGetFloat(int name, FloatBuffer into) {
        glGetFloatv(name, into);
    }

    public static void glGetDoublev(int name, double[] into) {
        GL11.glGetDoublev(name, into);
        if (translating() && translated(name)) {
            int count = count(name);
            System.arraycopy(into, 0, scratch, 0, count);
            restore(name);
            System.arraycopy(scratch, 0, into, 0, count);
        }
    }

    public static void glGetDoublev(int name, DoubleBuffer into) {
        GL11.glGetDoublev(name, into);
        if (translating() && translated(name)) {
            int count = count(name);
            int at = into.position();
            for (int i = 0; i < count; i++) {
                scratch[i] = into.get(at + i);
            }
            restore(name);
            for (int i = 0; i < count; i++) {
                into.put(at + i, scratch[i]);
            }
        }
    }

    public static void glGetDouble(int name, DoubleBuffer into) {
        glGetDoublev(name, into);
    }

    private static boolean translated(int name) {
        return name == PROJECTION_MATRIX || name == DEPTH_RANGE || name == DEPTH_CLEAR_VALUE
            || name == POLYGON_OFFSET_FACTOR || name == POLYGON_OFFSET_UNITS;
    }

    private static int count(int name) {
        return name == PROJECTION_MATRIX ? 16 : name == DEPTH_RANGE ? 2 : 1;
    }

    /** Turns the driver's values in the scratch array back into the values the caller set. */
    private static void restore(int name) {
        switch (name) {
            case PROJECTION_MATRIX:
                ReversedDepth.restore(scratch);
                break;
            case DEPTH_RANGE:
                double near = ReversedDepth.depth(scratch[1]);
                scratch[1] = ReversedDepth.depth(scratch[0]);
                scratch[0] = near;
                break;
            case DEPTH_CLEAR_VALUE:
                scratch[0] = ReversedDepth.depth(scratch[0]);
                break;
            default:
                scratch[0] = -scratch[0];
                break;
        }
    }
}
