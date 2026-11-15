package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.frame.StateEnvelopeAccess;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

/**
 * The shader and vertex layout that draw cube meshes as the game's fixed-function path would: the
 * block texture times the vertex color times the light map, then fog. It reads the matrices, fog
 * parameters and texture units the game has set, through the compatibility built-ins, so with reversed
 * depth on it draws in reversed depth with no change here.
 */
final class TerrainProgram {

    private static final int QUADS = 7;
    private static final int FOG_LINEAR = 0x2601;
    private static final int FOG_EXP = 0x0800;

    private static final String VERTEX = String.join("\n",
        "#version 460 compatibility",
        "layout(location = 0) in vec3 position;",
        "layout(location = 1) in vec4 color;",
        "layout(location = 2) in vec2 texture;",
        "layout(location = 3) in vec2 light;",
        "uniform mat4 cubeModelView;",
        "out vec4 vertexColor;",
        "out vec2 textureCoordinate;",
        "out vec2 lightCoordinate;",
        "out float fogDistance;",
        "void main() {",
        "    vec4 eye = cubeModelView * vec4(position, 1.0);",
        "    gl_Position = gl_ProjectionMatrix * eye;",
        "    vertexColor = color;",
        "    textureCoordinate = texture;",
        "    lightCoordinate = (gl_TextureMatrix[1] * vec4(light, 0.0, 1.0)).xy;",
        "    fogDistance = length(eye.xyz);",
        "}");
    private static final String FRAGMENT = String.join("\n",
        "#version 460 compatibility",
        "uniform sampler2D blocks;",
        "uniform sampler2D lightMap;",
        "uniform int fogMode;",
        "in vec4 vertexColor;",
        "in vec2 textureCoordinate;",
        "in vec2 lightCoordinate;",
        "in float fogDistance;",
        "layout(location = 0) out vec4 result;",
        "void main() {",
        "    vec4 color = texture(blocks, textureCoordinate) * vertexColor;",
        "    color *= texture(lightMap, lightCoordinate);",
        "    float clear = 1.0;",
        "    if (fogMode == 1) {",
        "        clear = (gl_Fog.end - fogDistance) * gl_Fog.scale;",
        "    } else if (fogMode == 2) {",
        "        clear = exp(-gl_Fog.density * fogDistance);",
        "    } else if (fogMode == 3) {",
        "        float d = gl_Fog.density * fogDistance;",
        "        clear = exp(-d * d);",
        "    }",
        "    color.rgb = mix(gl_Fog.color.rgb, color.rgb, clamp(clear, 0.0, 1.0));",
        "    result = color;",
        "}");

    /**
     * The matrix the game gives each of its render chunks and applies after moving to the chunk: an
     * enlargement by one part in a million about the point eight blocks before the chunk's lowest
     * corner on each axis. Composed in single precision as a move by eight blocks, the enlargement and
     * the move back, which gives a scale of 1.00000095 and an offset of 7.62939453e-06: the values read
     * from the running game.
     */
    private static final float CHUNK_SCALE = 1.000001F;
    private static final float CHUNK_OFFSET = 8.0F * CHUNK_SCALE - 8.0F;

    private int program;
    private int cubeModelViewLocation;
    private int fogModeLocation;
    private int vertexArray;
    private final float[] modelView = new float[16];
    private final float[] cubeModelView = new float[16];

    /** Binds the program and vertex layout. The state the layer depends on beyond that is the game's. */
    void begin() {
        if (program == 0) {
            create();
        }
        // Fog is fixed-function state a shader cannot read as on or off, or by mode. Asked of the
        // driver here; to be taken from where the game sets it instead.
        int fogMode = 0;
        if (GL11.glIsEnabled(GL11.GL_FOG)) {
            int mode = GL11.glGetInteger(GL11.GL_FOG_MODE);
            fogMode = mode == FOG_LINEAR ? 1 : mode == FOG_EXP ? 2 : 3;
        }
        // The game moves to each chunk by translating its model-view matrix, then multiplies by the
        // chunk's own matrix, both in single precision, and transforms chunk-local vertices by the
        // result. The same is done here per cube, in that order, so both place a vertex the same way;
        // adding the offset to the vertex in the shader does not, and leaving the chunk matrix out
        // moves the rows of a near texture by a pixel where a boundary falls on a pixel center.
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, modelView);
        GL20.glUseProgram(program);
        GL20.glUniform1i(fogModeLocation, fogMode);
        GL30.glBindVertexArray(vertexArray);
    }

    void draw(CubeMesh mesh, int layer, float x, float y, float z) {
        for (int row = 0; row < 4; row++) {
            float c0 = modelView[row];
            float c1 = modelView[4 + row];
            float c2 = modelView[8 + row];
            float moved = c0 * x + c1 * y + c2 * z + modelView[12 + row];
            cubeModelView[row] = c0 * CHUNK_SCALE;
            cubeModelView[4 + row] = c1 * CHUNK_SCALE;
            cubeModelView[8 + row] = c2 * CHUNK_SCALE;
            cubeModelView[12 + row] = c0 * CHUNK_OFFSET + c1 * CHUNK_OFFSET + c2 * CHUNK_OFFSET + moved;
        }
        GL20.glUniformMatrix4fv(cubeModelViewLocation, false, cubeModelView);
        GL45.glVertexArrayVertexBuffer(vertexArray, 0, mesh.buffer(layer), 0, CubeMesh.STRIDE);
        GL11.glDrawArrays(QUADS, 0, mesh.count(layer));
    }

    void end() {
        StateEnvelopeAccess.leave();
    }

    private void create() {
        program = GL20.glCreateProgram();
        int vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Terrain program: " + GL20.glGetProgramInfoLog(program));
        }
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        cubeModelViewLocation = GL20.glGetUniformLocation(program, "cubeModelView");
        fogModeLocation = GL20.glGetUniformLocation(program, "fogMode");
        // The game keeps the block texture on unit 0 and the light map on unit 1.
        GL45.glProgramUniform1i(program, GL20.glGetUniformLocation(program, "blocks"), 0);
        GL45.glProgramUniform1i(program, GL20.glGetUniformLocation(program, "lightMap"), 1);

        vertexArray = GL45.glCreateVertexArrays();
        attribute(0, 3, GL11.GL_FLOAT, false, 0);
        attribute(1, 4, GL11.GL_UNSIGNED_BYTE, true, 12);
        attribute(2, 2, GL11.GL_FLOAT, false, 16);
        attribute(3, 2, GL11.GL_SHORT, false, 24);
    }

    private void attribute(int index, int size, int type, boolean normalized, int offset) {
        GL45.glEnableVertexArrayAttrib(vertexArray, index);
        GL45.glVertexArrayAttribFormat(vertexArray, index, size, type, normalized, offset);
        GL45.glVertexArrayAttribBinding(vertexArray, index, 0);
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("Terrain shader: " + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
