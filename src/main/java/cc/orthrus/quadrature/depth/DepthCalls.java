package cc.orthrus.quadrature.depth;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.depth.ReversedDepth;

import org.lwjgl.opengl.EXTDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

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
    private static final int DEPTH_COMPONENT = 0x1902;
    private static final int UNSIGNED_BYTE = 0x1401;
    private static final int UNSIGNED_SHORT = 0x1403;
    private static final int UNSIGNED_INT = 0x1405;
    private static final int FLOAT = 0x1406;

    private static final int COMPILE = 0x1300;

    private static Thread renderThread;
    private static int matrixMode;
    /** The matrix mode to return to when the display list being compiled ends; -1 outside one. */
    private static int modeBeforeList = -1;
    /**
     * Set for good once a display list that sets the matrix mode has been compiled. Calling such a
     * list changes the mode where no rewritten call sees it, so from then on the mode is read from
     * the driver instead of taken from this class's own record.
     */
    private static boolean listsSetMatrixMode;
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
        if (Boolean.getBoolean("quadrature.depthSelfTest")) {
            DepthSelfTest.run();
        }
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
        if (modeBeforeList != -1 && !listsSetMatrixMode && translating()) {
            listsSetMatrixMode = true;
            Quadrature.LOGGER.warn("A display list sets the matrix mode; the mode is now read from the driver "
                + "on every projection load. Loads in a list that rely on the caller's mode are not reversed.");
        }
    }

    /**
     * Whether a matrix load now goes to the projection stack. Inside a display list being compiled
     * the answer is the mode the list itself has set so far, since that is what will hold when it
     * runs.
     */
    private static boolean projection() {
        if (listsSetMatrixMode && modeBeforeList == -1) {
            matrixMode = GL11.glGetInteger(MATRIX_MODE);
        }
        return matrixMode == PROJECTION;
    }

    public static void glNewList(int list, int mode) {
        GL11.glNewList(list, mode);
        if (translating() && mode == COMPILE) {
            // Compiling runs nothing, so the driver's mode stays what it is now.
            modeBeforeList = matrixMode;
        }
    }

    public static void glEndList() {
        GL11.glEndList();
        if (modeBeforeList != -1) {
            matrixMode = modeBeforeList;
            modeBeforeList = -1;
        }
    }

    public static void glPopAttrib() {
        GL11.glPopAttrib();
        if (translating()) {
            // The attribute stack can restore a matrix mode this class never saw set.
            matrixMode = GL11.glGetInteger(MATRIX_MODE);
        }
    }

    public static void glLoadIdentity() {
        if (translating() && projection()) {
            loadReversed(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
        } else {
            GL11.glLoadIdentity();
        }
    }

    public static void glLoadMatrixf(float[] matrix) {
        if (translating() && projection()) {
            for (int i = 0; i < 16; i++) {
                scratch[i] = matrix[i];
            }
            loadReversed();
        } else {
            GL11.glLoadMatrixf(matrix);
        }
    }

    public static void glLoadMatrixf(FloatBuffer matrix) {
        if (translating() && projection()) {
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
        if (translating() && projection()) {
            System.arraycopy(matrix, 0, scratch, 0, 16);
            loadReversed();
        } else {
            GL11.glLoadMatrixd(matrix);
        }
    }

    public static void glLoadMatrixd(DoubleBuffer matrix) {
        if (translating() && projection()) {
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

    // Row-major loads: transposed into the scratch array, then loaded like any other.

    public static void glLoadTransposeMatrixf(float[] matrix) {
        if (translating() && projection()) {
            for (int i = 0; i < 16; i++) {
                scratch[(i & 3) * 4 + (i >> 2)] = matrix[i];
            }
            loadReversed();
        } else {
            GL13.glLoadTransposeMatrixf(matrix);
        }
    }

    public static void glLoadTransposeMatrixf(FloatBuffer matrix) {
        if (translating() && projection()) {
            for (int i = 0; i < 16; i++) {
                scratch[(i & 3) * 4 + (i >> 2)] = matrix.get(matrix.position() + i);
            }
            loadReversed();
        } else {
            GL13.glLoadTransposeMatrixf(matrix);
        }
    }

    public static void glLoadTransposeMatrix(FloatBuffer matrix) {
        glLoadTransposeMatrixf(matrix);
    }

    public static void glLoadTransposeMatrixd(double[] matrix) {
        if (translating() && projection()) {
            for (int i = 0; i < 16; i++) {
                scratch[(i & 3) * 4 + (i >> 2)] = matrix[i];
            }
            loadReversed();
        } else {
            GL13.glLoadTransposeMatrixd(matrix);
        }
    }

    public static void glLoadTransposeMatrixd(DoubleBuffer matrix) {
        if (translating() && projection()) {
            for (int i = 0; i < 16; i++) {
                scratch[(i & 3) * 4 + (i >> 2)] = matrix.get(matrix.position() + i);
            }
            loadReversed();
        } else {
            GL13.glLoadTransposeMatrixd(matrix);
        }
    }

    public static void glLoadTransposeMatrix(DoubleBuffer matrix) {
        glLoadTransposeMatrixd(matrix);
    }

    // Loads that name their stack instead of using the matrix mode.

    public static void glMatrixLoadIdentityEXT(int stack) {
        if (translating() && stack == PROJECTION) {
            identity();
            ReversedDepth.reverse(scratch);
            EXTDirectStateAccess.glMatrixLoaddEXT(stack, scratch);
        } else {
            EXTDirectStateAccess.glMatrixLoadIdentityEXT(stack);
        }
    }

    public static void glMatrixLoadfEXT(int stack, float[] matrix) {
        if (translating() && stack == PROJECTION) {
            for (int i = 0; i < 16; i++) {
                scratch[i] = matrix[i];
            }
            ReversedDepth.reverse(scratch);
            EXTDirectStateAccess.glMatrixLoaddEXT(stack, scratch);
        } else {
            EXTDirectStateAccess.glMatrixLoadfEXT(stack, matrix);
        }
    }

    public static void glMatrixLoadfEXT(int stack, FloatBuffer matrix) {
        if (translating() && stack == PROJECTION) {
            for (int i = 0; i < 16; i++) {
                scratch[i] = matrix.get(matrix.position() + i);
            }
            ReversedDepth.reverse(scratch);
            EXTDirectStateAccess.glMatrixLoaddEXT(stack, scratch);
        } else {
            EXTDirectStateAccess.glMatrixLoadfEXT(stack, matrix);
        }
    }

    public static void glMatrixLoadEXT(int stack, FloatBuffer matrix) {
        glMatrixLoadfEXT(stack, matrix);
    }

    public static void glMatrixLoaddEXT(int stack, double[] matrix) {
        if (translating() && stack == PROJECTION) {
            System.arraycopy(matrix, 0, scratch, 0, 16);
            ReversedDepth.reverse(scratch);
            EXTDirectStateAccess.glMatrixLoaddEXT(stack, scratch);
        } else {
            EXTDirectStateAccess.glMatrixLoaddEXT(stack, matrix);
        }
    }

    public static void glMatrixLoaddEXT(int stack, DoubleBuffer matrix) {
        if (translating() && stack == PROJECTION) {
            for (int i = 0; i < 16; i++) {
                scratch[i] = matrix.get(matrix.position() + i);
            }
            ReversedDepth.reverse(scratch);
            EXTDirectStateAccess.glMatrixLoaddEXT(stack, scratch);
        } else {
            EXTDirectStateAccess.glMatrixLoaddEXT(stack, matrix);
        }
    }

    public static void glMatrixLoadEXT(int stack, DoubleBuffer matrix) {
        glMatrixLoaddEXT(stack, matrix);
    }

    private static void identity() {
        for (int i = 0; i < 16; i++) {
            scratch[i] = (i % 5 == 0) ? 1.0 : 0.0;
        }
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

    // Reading depth pixels. Hosted code that reads a depth value unprojects it through the projection
    // it also read here, which is conventional, so the depth must be conventional too. For the
    // normalized integer types, 1 - d is the bitwise complement.

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
        GL11.glReadPixels(x, y, width, height, format, type, pixels);
        if (translating() && format == DEPTH_COMPONENT && type == FLOAT) {
            restoreFloats(pixels, width * height);
        }
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, float[] pixels) {
        GL11.glReadPixels(x, y, width, height, format, type, pixels);
        if (translating() && format == DEPTH_COMPONENT && type == FLOAT) {
            for (int i = 0; i < width * height; i++) {
                pixels[i] = 1.0f - pixels[i];
            }
        }
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        GL11.glReadPixels(x, y, width, height, format, type, pixels);
        if (!translating() || format != DEPTH_COMPONENT) {
            return;
        }
        // The driver writes in the machine's byte order whatever order the buffer declares.
        ByteBuffer view = pixels.duplicate().order(ByteOrder.nativeOrder());
        int count = width * height;
        int at = view.position();
        switch (type) {
            case FLOAT:
                restoreFloats(view.asFloatBuffer(), count);
                break;
            case UNSIGNED_INT:
                for (int i = 0; i < count; i++) {
                    view.putInt(at + 4 * i, ~view.getInt(at + 4 * i));
                }
                break;
            case UNSIGNED_SHORT:
                for (int i = 0; i < count; i++) {
                    view.putShort(at + 2 * i, (short) ~view.getShort(at + 2 * i));
                }
                break;
            case UNSIGNED_BYTE:
                for (int i = 0; i < count; i++) {
                    view.put(at + i, (byte) ~view.get(at + i));
                }
                break;
            default:
                break;
        }
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        GL11.glReadPixels(x, y, width, height, format, type, pixels);
        if (translating() && format == DEPTH_COMPONENT && type == UNSIGNED_INT) {
            int at = pixels.position();
            for (int i = 0; i < width * height; i++) {
                pixels.put(at + i, ~pixels.get(at + i));
            }
        }
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, ShortBuffer pixels) {
        GL11.glReadPixels(x, y, width, height, format, type, pixels);
        if (translating() && format == DEPTH_COMPONENT && type == UNSIGNED_SHORT) {
            int at = pixels.position();
            for (int i = 0; i < width * height; i++) {
                pixels.put(at + i, (short) ~pixels.get(at + i));
            }
        }
    }

    private static void restoreFloats(FloatBuffer pixels, int count) {
        int at = pixels.position();
        for (int i = 0; i < count; i++) {
            pixels.put(at + i, 1.0f - pixels.get(at + i));
        }
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
