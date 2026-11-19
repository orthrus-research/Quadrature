package cc.orthrus.quadrature.nvidia;

import org.lwjgl.opengl.GL11;

/** OpenGL errors around the handoff's setup, where a failure must name the step that caused it. */
final class Gl {

    /** More errors than a driver ever queues; past this the queue is taken to be stuck. */
    private static final int DRAIN_LIMIT = 64;

    private Gl() {
    }

    /** Drops errors something else left, so the next check is about the handoff's own calls. */
    static int clearErrors() {
        int cleared = 0;
        while (cleared < DRAIN_LIMIT && GL11.glGetError() != GL11.GL_NO_ERROR) {
            cleared++;
        }
        return cleared;
    }

    /** Throws, naming the step, if the calls since the last check raised an error. */
    static void check(String step) {
        int error = GL11.glGetError();
        if (error == GL11.GL_NO_ERROR) {
            return;
        }
        StringBuilder errors = new StringBuilder("0x").append(Integer.toHexString(error));
        for (int i = 0; i < DRAIN_LIMIT && (error = GL11.glGetError()) != GL11.GL_NO_ERROR; i++) {
            errors.append(", 0x").append(Integer.toHexString(error));
        }
        throw new IllegalStateException("OpenGL raised " + errors + " " + step + ".");
    }
}
