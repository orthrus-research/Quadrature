package cc.orthrus.quadrature.rays;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

/**
 * What a compute dispatch here binds, read before and put back after: the program in use, shader
 * storage points 0 to 5 with their ranges, the general shader storage binding, and the buffer textures
 * on the two units the trace may read through. A buffer bound to an indexed point also becomes the
 * general binding, so the general one is put back last.
 */
final class ComputeState {

    private static final int POINTS = 6;

    private final int[] buffers = new int[POINTS];
    private final long[] starts = new long[POINTS];
    private final long[] sizes = new long[POINTS];
    private int program;
    private int general;
    private int lookupTexture;
    private int brickTexture;

    void save() {
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        general = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        for (int point = 0; point < POINTS; point++) {
            buffers[point] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, point);
            starts[point] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, point);
            sizes[point] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, point);
        }
        if (RayTracer.TEXTURES) {
            lookupTexture = GL30.glGetIntegeri(GL31.GL_TEXTURE_BINDING_BUFFER, TraceProgram.LOOKUP_UNIT);
            brickTexture = GL30.glGetIntegeri(GL31.GL_TEXTURE_BINDING_BUFFER, TraceProgram.BRICK_UNIT);
        }
    }

    void restore() {
        GL20.glUseProgram(program);
        for (int point = 0; point < POINTS; point++) {
            if (buffers[point] != 0 && sizes[point] != 0) {
                GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, point, buffers[point], starts[point], sizes[point]);
            } else {
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, point, buffers[point]);
            }
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, general);
        if (RayTracer.TEXTURES) {
            GL45.glBindTextureUnit(TraceProgram.LOOKUP_UNIT, lookupTexture);
            GL45.glBindTextureUnit(TraceProgram.BRICK_UNIT, brickTexture);
        }
    }
}
