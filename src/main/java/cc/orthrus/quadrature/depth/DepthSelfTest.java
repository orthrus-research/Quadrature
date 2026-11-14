package cc.orthrus.quadrature.depth;

import cc.orthrus.quadrature.Quadrature;

import org.lwjgl.opengl.GL11;

/**
 * A check of the projection translations against the driver, run once when reversed depth is switched
 * on with {@code -Dquadrature.depthSelfTest=true}. Each case loads a projection the way hosted code
 * might, through {@link DepthCalls}, and reads back what the driver then holds: a reversed identity
 * has -0.5 and 0.5 where identity has 1 and 0 in its depth row.
 */
final class DepthSelfTest {

    private static final int MODELVIEW = 0x1700;
    private static final int PROJECTION = 0x1701;
    private static final int PROJECTION_MATRIX = 0x0BA7;
    private static final int MATRIX_MODE = 0x0BA0;
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    private DepthSelfTest() {
    }

    static void run() {
        float[] saved = new float[16];
        DepthCalls.glGetFloatv(PROJECTION_MATRIX, saved);
        int failures = 0;

        DepthCalls.glMatrixMode(PROJECTION);
        DepthCalls.glLoadIdentity();
        failures += check("identity", true);
        DepthCalls.glLoadMatrixf(IDENTITY);
        failures += check("matrix", true);
        DepthCalls.glLoadTransposeMatrixf(IDENTITY);
        failures += check("transposed matrix", true);
        DepthCalls.glMatrixMode(MODELVIEW);

        DepthCalls.glMatrixLoadfEXT(PROJECTION, IDENTITY);
        failures += check("named stack, matrix", true);
        DepthCalls.glMatrixLoadIdentityEXT(PROJECTION);
        failures += check("named stack, identity", true);

        // A display list that sets the matrix mode itself and leaves it on the projection stack.
        int list = GL11.glGenLists(1);
        DepthCalls.glNewList(list, 0x1300);
        DepthCalls.glMatrixMode(PROJECTION);
        DepthCalls.glLoadIdentity();
        DepthCalls.glEndList();
        failures += expect("mode after compiling a list", MODELVIEW, GL11.glGetInteger(MATRIX_MODE));
        GL11.glMatrixMode(PROJECTION);
        GL11.glLoadIdentity(); // the driver's own identity, so the list's load is what gets checked
        GL11.glMatrixMode(MODELVIEW);
        GL11.glCallList(list);
        failures += check("load inside a list", true);
        failures += expect("mode after calling the list", PROJECTION, GL11.glGetInteger(MATRIX_MODE));
        // The list left the mode on the projection stack; no rewritten call saw that happen.
        DepthCalls.glLoadMatrixf(IDENTITY);
        failures += check("load after a list changed the mode", true);
        GL11.glDeleteLists(list, 1);

        DepthCalls.glMatrixMode(MODELVIEW);
        GL11.glPushMatrix();
        DepthCalls.glLoadIdentity();
        float[] modelview = new float[16];
        GL11.glGetFloatv(0x0BA6, modelview);
        failures += expect("modelview identity untouched", 1.0f, modelview[10]);
        GL11.glPopMatrix();

        DepthCalls.glMatrixMode(PROJECTION);
        DepthCalls.glLoadMatrixf(saved);
        DepthCalls.glMatrixMode(MODELVIEW);
        float[] restored = new float[16];
        DepthCalls.glGetFloatv(PROJECTION_MATRIX, restored);
        for (int i = 0; i < 16; i++) {
            if (Math.abs(restored[i] - saved[i]) > 1e-5f) {
                failures += expect("projection restored, element " + i, saved[i], restored[i]);
            }
        }
        Quadrature.LOGGER.info("Depth self-test: {}.", failures == 0 ? "all cases passed" : failures + " failed");
    }

    private static int check(String label, boolean reversed) {
        float[] driver = new float[16];
        GL11.glGetFloatv(PROJECTION_MATRIX, driver);
        boolean is = Math.abs(driver[10] + 0.5f) < 1e-6f && Math.abs(driver[14] - 0.5f) < 1e-6f;
        if (is != reversed) {
            Quadrature.LOGGER.error("Depth self-test, {}: driver depth row {} {}", label, driver[10], driver[14]);
            return 1;
        }
        return 0;
    }

    private static int expect(String label, float expected, float actual) {
        if (expected != actual) {
            Quadrature.LOGGER.error("Depth self-test, {}: expected {}, found {}", label, expected, actual);
            return 1;
        }
        return 0;
    }
}
