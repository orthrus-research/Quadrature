package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.Quadrature;

import net.minecraft.client.renderer.GlStateManager;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL45;

/**
 * A development stage of Quadrature's own, off unless {@code -Dquadrature.probe} is set. It draws with
 * everything a modern renderer adds to the context (a shader program, a vertex array object, a
 * buffer, a framebuffer), between two hosted stages, so the state envelope and the depth convention
 * can be checked against a real frame before any terrain exists.
 *
 * <ul>
 * <li>{@code offscreen}: one square into the probe's own framebuffer. The game's frame must not
 * change at all.
 * <li>{@code visible}: that, and a square in the game's frame, flat, 16 blocks wide and two blocks
 * below the eye, depth-tested. Terrain drawn afterwards must cut it exactly where it rises above it.
 * </ul>
 */
final class Probe {

    static final String STAGE = "probe";
    /** The hosted stage the probe runs in front of. */
    static final String BEFORE = "terrain setup";

    private static final String MODE = System.getProperty("quadrature.probe", "off");
    static final boolean ENABLED = MODE.equals("offscreen") || MODE.equals("visible");
    private static final boolean VISIBLE = MODE.equals("visible");

    private static final int TARGET_SIZE = 256;
    private static final int LESS_OR_EQUAL = 515;

    private static final String VERTEX = String.join("\n",
        "#version 460",
        "layout(location = 0) in vec3 position;",
        "uniform mat4 projection;",
        "uniform mat4 view;",
        "void main() {",
        "    gl_Position = projection * view * vec4(position, 1.0);",
        "}");
    private static final String FRAGMENT = String.join("\n",
        "#version 460",
        "layout(location = 0) out vec4 color;",
        "void main() {",
        "    color = vec4(1.0, 0.0, 1.0, 1.0);",
        "}");

    private static int program;
    private static int projectionLocation;
    private static int viewLocation;
    private static int vertexArray;
    private static int target;
    private static final float[] projection = new float[16];
    private static final float[] view = new float[16];
    private static final float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    private Probe() {
    }

    /** Runs the stage. The world pass's projection and camera rotation are loaded at this point. */
    static void run() {
        if (program == 0) {
            create();
        }
        // The matrices the driver holds, read directly: with reversed depth on they are already
        // reversed, so this stage's depth matches what hosted drawing writes. A real stage will take
        // them from where the game sets them instead of asking the driver every frame.
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, view);

        // Entering: every piece of state the stage depends on, set here, none of it assumed.
        GlStateManager.disableCull();
        GlStateManager.disableBlend();
        GlStateManager.colorMask(true, true, true, true);
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vertexArray);

        GlStateManager.disableDepth();
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target);
        GL11.glViewport(0, 0, TARGET_SIZE, TARGET_SIZE);
        GL41.glProgramUniformMatrix4fv(program, projectionLocation, false, identity);
        GL41.glProgramUniformMatrix4fv(program, viewLocation, false, identity);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Leaving, first half: state the game's cache does not know, back to neutral.
        StateEnvelope.leave();

        if (VISIBLE) {
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(LESS_OR_EQUAL);
            GlStateManager.depthMask(true);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vertexArray);
            GL41.glProgramUniformMatrix4fv(program, projectionLocation, false, projection);
            GL41.glProgramUniformMatrix4fv(program, viewLocation, false, view);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            StateEnvelope.leave();
        }

        // Leaving, second half: state the cache knows, back to what this place in the frame expects.
        // In front of terrain setup the game holds depth testing on with "less or equal", depth
        // writes on, blending off and face culling on.
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(LESS_OR_EQUAL);
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
    }

    private static void create() {
        program = GL20.glCreateProgram();
        int vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Probe program: " + GL20.glGetProgramInfoLog(program));
        }
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        projectionLocation = GL20.glGetUniformLocation(program, "projection");
        viewLocation = GL20.glGetUniformLocation(program, "view");

        // Created and filled through direct state access: no binding changes.
        float[] square = {-8, -2, -8, 8, -2, -8, -8, -2, 8, 8, -2, 8};
        int buffer = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(buffer, square, 0);
        vertexArray = GL45.glCreateVertexArrays();
        GL45.glVertexArrayVertexBuffer(vertexArray, 0, buffer, 0, 12);
        GL45.glEnableVertexArrayAttrib(vertexArray, 0);
        GL45.glVertexArrayAttribFormat(vertexArray, 0, 3, GL11.GL_FLOAT, false, 0);
        GL45.glVertexArrayAttribBinding(vertexArray, 0, 0);

        int texture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(texture, 1, GL11.GL_RGBA8, TARGET_SIZE, TARGET_SIZE);
        target = GL45.glCreateFramebuffers();
        GL45.glNamedFramebufferTexture(target, GL30.GL_COLOR_ATTACHMENT0, texture, 0);
        int status = GL45.glCheckNamedFramebufferStatus(target, GL30.GL_DRAW_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Probe framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
        Quadrature.LOGGER.info("Probe stage ready ({}).", MODE);
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("Probe shader: " + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
