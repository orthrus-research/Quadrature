package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.core.terrain.CubeMatrix;
import cc.orthrus.quadrature.core.terrain.DrawCommands;
import cc.orthrus.quadrature.core.view.View;
import cc.orthrus.quadrature.frame.StateEnvelopeAccess;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

/**
 * The shader and vertex layout that draw cube meshes as the game's fixed-function path would: the
 * block texture times the vertex color times the light map, then fog. The model-view matrix is the
 * view's. The projection, the fog parameters and the texture units are still read from what the game
 * has set, through the compatibility built-ins, which holds for the player's view only; with reversed
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
     * The same vertex stage for indirect draws: the cube's matrix comes from a record the draw names
     * by its base instance, not from a uniform. A record holds the matrix and room for the one the
     * cube had the frame before.
     */
    private static final String VERTEX_INDIRECT = VERTEX
        .replace("uniform mat4 cubeModelView;", "struct CubeRecord { mat4 modelView; mat4 previous; };\n"
            + "layout(std430, binding = 0) readonly buffer CubeRecords { CubeRecord cubes[]; };")
        .replace("cubeModelView * vec4(position, 1.0)", "cubes[gl_BaseInstance].modelView * vec4(position, 1.0)");

    /**
     * Depth alone, for a view of Quadrature's own: the cube's matrix from its record, the projection
     * from the view, nothing from the game's fixed-function state. A fragment of a cutout layer is
     * dropped where the block texture's alpha fails the game's alpha test, so that what casts a shadow
     * is the shape that is seen.
     */
    private static final String VERTEX_DEPTH = String.join("\n",
        "#version 460 compatibility",
        "layout(location = 0) in vec3 position;",
        "layout(location = 1) in vec4 color;",
        "layout(location = 2) in vec2 texture;",
        "struct CubeRecord { mat4 modelView; mat4 previous; };",
        "layout(std430, binding = 0) readonly buffer CubeRecords { CubeRecord cubes[]; };",
        "uniform mat4 viewProjection;",
        "out vec2 textureCoordinate;",
        "out float vertexAlpha;",
        "void main() {",
        "    gl_Position = viewProjection * (cubes[gl_BaseInstance].modelView * vec4(position, 1.0));",
        "    textureCoordinate = texture;",
        "    vertexAlpha = color.a;",
        "}");
    private static final String FRAGMENT_DEPTH = String.join("\n",
        "#version 460 compatibility",
        "uniform sampler2D blocks;",
        "uniform float alphaReference;",
        "in vec2 textureCoordinate;",
        "in float vertexAlpha;",
        "void main() {",
        "    if (alphaReference >= 0.0 && texture(blocks, textureCoordinate).a * vertexAlpha <= alphaReference) {",
        "        discard;",
        "    }",
        "}");

    /**
     * What a vertex stage that draws from face records does before anything else: it reads the record
     * its vertex belongs to, four vertices to a record, and builds that corner of the face. No vertex
     * attribute is read. The values it builds are the ones the game's vertex would have held.
     */
    private static final String PULL = String.join("\n",
        "struct CubeRecord { mat4 modelView; mat4 previous; };",
        "layout(std430, binding = 0) readonly buffer CubeRecords { CubeRecord cubes[]; };",
        "layout(std430, binding = 1) readonly buffer Faces { uint words[]; };",
        "layout(std430, binding = 2) readonly buffer Sprites { vec4 rects[]; };",
        "vec3 facePosition;",
        "vec4 faceColor;",
        "vec2 faceTexture;",
        "vec2 faceLight;",
        "void pull() {",
        "    int word = (gl_VertexID >> 2) * 8;",
        "    int vertex = gl_VertexID & 3;",
        "    uint w0 = words[word];",
        "    vec3 cell = vec3(float(w0 & 15u), float((w0 >> 4) & 15u), float((w0 >> 8) & 15u));",
        "    uint face = (w0 >> 12) & 7u;",
        "    uint corner = (w0 >> (15 + 2 * vertex)) & 3u;",
        "    float side = float(face & 1u);",
        "    float first = float(corner & 1u);",
        "    float second = float(corner >> 1);",
        "    // Up and down: x then z. North and south: x then y. West and east: z then y.",
        "    vec3 offset = face < 2u ? vec3(first, side, second) : face < 4u ? vec3(first, second, side)",
        "        : vec3(side, second, first);",
        "    facePosition = cell + offset;",
        "    uint w3 = words[word + 3];",
        "    uint w4 = words[word + 4];",
        "    uint w5 = words[word + 5];",
        "    uint color = vertex == 0 ? w3 & 0xFFFFFFu : vertex == 1 ? (w3 >> 24) | ((w4 & 0xFFFFu) << 8)",
        "        : vertex == 2 ? (w4 >> 16) | ((w5 & 0xFFu) << 16) : w5 >> 8;",
        "    faceColor = vec4(float(color & 255u), float((color >> 8) & 255u), float((color >> 16) & 255u), 255.0) / 255.0;",
        "    vec4 rect = rects[words[word + 1] & 0xFFFFu];",
        "    uint showing = (w0 >> (23 + 2 * vertex)) & 3u;",
        "    faceTexture = vec2((showing & 1u) != 0u ? rect.z : rect.x, (showing & 2u) != 0u ? rect.w : rect.y);",
        "    uint light = (words[word + 6 + (vertex >> 1)] >> (16 * (vertex & 1))) & 0xFFFFu;",
        "    faceLight = vec2(float(light & 255u), float(light >> 8));",
        "}");

    /** The forward vertex stage, drawn from face records. */
    private static final String VERTEX_FACES = String.join("\n",
        "#version 460 compatibility",
        PULL,
        "out vec4 vertexColor;",
        "out vec2 textureCoordinate;",
        "out vec2 lightCoordinate;",
        "out float fogDistance;",
        "void main() {",
        "    pull();",
        "    vec4 eye = cubes[gl_BaseInstance].modelView * vec4(facePosition, 1.0);",
        "    gl_Position = gl_ProjectionMatrix * eye;",
        "    vertexColor = faceColor;",
        "    textureCoordinate = faceTexture;",
        "    lightCoordinate = (gl_TextureMatrix[1] * vec4(faceLight, 0.0, 1.0)).xy;",
        "    fogDistance = length(eye.xyz);",
        "}");

    /** The depth-only vertex stage, drawn from face records. */
    private static final String VERTEX_DEPTH_FACES = String.join("\n",
        "#version 460 compatibility",
        PULL,
        "uniform mat4 viewProjection;",
        "out vec2 textureCoordinate;",
        "out float vertexAlpha;",
        "void main() {",
        "    pull();",
        "    gl_Position = viewProjection * (cubes[gl_BaseInstance].modelView * vec4(facePosition, 1.0));",
        "    textureCoordinate = faceTexture;",
        "    vertexAlpha = 1.0;",
        "}");

    private static final int RECORDS_BINDING = 0;
    private static final int FACES_BINDING = 1;
    private static final int SPRITES_BINDING = 2;

    private int program;
    private int cubeModelViewLocation;
    private int fogModeLocation;
    private int indirectProgram;
    private int indirectFogModeLocation;
    private int vertexArray;
    private int commandBuffer;
    private int recordBuffer;
    private int depthProgram;
    private int depthProjectionLocation;
    private int depthAlphaLocation;
    private int depthCommandBuffer;
    private int depthRecordBuffer;
    private int facesProgram;
    private int facesFogModeLocation;
    private int depthFacesProgram;
    private int depthFacesProjectionLocation;
    private int depthFacesAlphaLocation;
    /** A vertex array with nothing in it: a draw from face records reads no attribute. */
    private int emptyVertexArray;
    private int spriteBuffer;
    private final int[] faceCommandBuffers = new int[2];
    private final int[] faceRecordBuffers = new int[2];
    /** Which program is in use since {@link #begin}: 0 neither, 1 the direct one, 2 the indirect one. */
    private int inUse;
    private int boundBuffer;
    private int fogMode;
    private final float[] modelView = new float[16];
    private final float[] cubeModelView = new float[16];

    /** Binds the vertex layout and reads the fog. The state the layer depends on beyond that is the game's. */
    void begin(View view) {
        if (program == 0) {
            create();
        }
        // Fog is fixed-function state a shader cannot read as on or off, or by mode. Asked of the
        // driver here; to be taken from where the game sets it instead.
        fogMode = 0;
        if (GL11.glIsEnabled(GL11.GL_FOG)) {
            int mode = GL11.glGetInteger(GL11.GL_FOG_MODE);
            fogMode = mode == FOG_LINEAR ? 1 : mode == FOG_EXP ? 2 : 3;
        }
        // The game moves to each chunk by translating its model-view matrix, then multiplies by the
        // chunk's own matrix, both in single precision, and transforms chunk-local vertices by the
        // result. The same is done per cube, in that order, so both place a vertex the same way;
        // adding the offset to the vertex in the shader does not, and leaving the chunk matrix out
        // moves the rows of a near texture by a pixel where a boundary falls on a pixel center.
        System.arraycopy(view.modelView(), 0, modelView, 0, 16);
        GL30.glBindVertexArray(vertexArray);
        inUse = 0;
        boundBuffer = 0;
    }

    /** Draws one layer of one cube, its matrix in a uniform. */
    void draw(CubeMesh mesh, int layer, float x, float y, float z) {
        if (inUse != 1) {
            GL20.glUseProgram(program);
            GL20.glUniform1i(fogModeLocation, fogMode);
            inUse = 1;
        }
        if (boundBuffer != mesh.buffer(layer)) {
            boundBuffer = mesh.buffer(layer);
            GL45.glVertexArrayVertexBuffer(vertexArray, 0, boundBuffer, 0, CubeMesh.STRIDE);
        }
        CubeMatrix.compose(modelView, x, y, z, cubeModelView, 0);
        GL20.glUniformMatrix4fv(cubeModelViewLocation, false, cubeModelView);
        GL11.glDrawArrays(QUADS, mesh.first(layer), mesh.count(layer));
    }

    /** Hands the driver a frame's commands and records, once, before its layers are drawn. */
    void upload(DrawCommands list) {
        if (program == 0) {
            create();
        }
        // Each frame's data replaces the last's whole, so the driver need not wait for draws that
        // still read the old.
        GL45.glNamedBufferData(commandBuffer, list.commands(), GL15.GL_STREAM_DRAW);
        GL45.glNamedBufferData(recordBuffer, list.records(), GL15.GL_STREAM_DRAW);
    }

    /**
     * Draws one layer of everything a list holds: one indirect draw for each page of the arena that
     * has commands in that layer.
     *
     * @return how many draws were issued
     */
    int drawIndirect(DrawCommands list, int layer, TerrainArena arena) {
        GL20.glUseProgram(indirectProgram);
        GL20.glUniform1i(indirectFogModeLocation, fogMode);
        inUse = 2;
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, RECORDS_BINDING, recordBuffer);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        int draws = 0;
        for (int page = 0; page < list.pages(); page++) {
            int count = list.runCount(layer, page);
            if (count == 0) {
                continue;
            }
            boundBuffer = arena.buffer(page);
            GL45.glVertexArrayVertexBuffer(vertexArray, 0, boundBuffer, 0, CubeMesh.STRIDE);
            GL43.glMultiDrawArraysIndirect(QUADS, list.runOffset(layer, page), count, DrawCommands.COMMAND_BYTES);
            draws++;
        }
        return draws;
    }

    /** Hands the driver every sprite's rectangle in the atlas, when a sprite has been met for the first time. */
    void uploadSprites(float[] rects, int sprites) {
        if (program == 0) {
            create();
        }
        java.nio.FloatBuffer data = java.nio.ByteBuffer.allocateDirect(Math.max(1, sprites) * 16)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        data.put(rects, 0, sprites * 4).flip();
        GL45.glNamedBufferData(spriteBuffer, data, GL15.GL_STATIC_DRAW);
    }

    /**
     * Hands the driver the commands and records of what a view draws from face records.
     *
     * @param which 0 for the player's view, 1 for a view drawn for depth alone
     */
    void uploadFaces(DrawCommands list, int which) {
        if (program == 0) {
            create();
        }
        GL45.glNamedBufferData(faceCommandBuffers[which], list.commands(), GL15.GL_STREAM_DRAW);
        GL45.glNamedBufferData(faceRecordBuffers[which], list.records(), GL15.GL_STREAM_DRAW);
    }

    /**
     * Draws one layer of a list from face records: one indirect draw for each page of the arena that
     * holds records and has commands in that layer, four vertices to a record.
     *
     * @return how many draws were issued
     */
    int drawFaces(DrawCommands list, int layer, TerrainArena faces) {
        GL30.glBindVertexArray(emptyVertexArray);
        GL20.glUseProgram(facesProgram);
        GL20.glUniform1i(facesFogModeLocation, fogMode);
        inUse = 3;
        int draws = pages(list, layer, faces, 0);
        GL30.glBindVertexArray(vertexArray);
        return draws;
    }

    /** As {@link #drawDepth}, from face records. */
    int drawDepthFaces(DrawCommands list, int layer, TerrainArena faces, float[] projection, float alphaReference) {
        GL30.glBindVertexArray(emptyVertexArray);
        GL20.glUseProgram(depthFacesProgram);
        GL20.glUniformMatrix4fv(depthFacesProjectionLocation, false, projection);
        GL20.glUniform1f(depthFacesAlphaLocation, alphaReference);
        return pages(list, layer, faces, 1);
    }

    private int pages(DrawCommands list, int layer, TerrainArena faces, int which) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, RECORDS_BINDING, faceRecordBuffers[which]);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SPRITES_BINDING, spriteBuffer);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, faceCommandBuffers[which]);
        int draws = 0;
        for (int page = 0; page < list.pages(); page++) {
            int count = list.runCount(layer, page);
            if (count == 0) {
                continue;
            }
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, FACES_BINDING, faces.buffer(page));
            GL43.glMultiDrawArraysIndirect(QUADS, list.runOffset(layer, page), count, DrawCommands.COMMAND_BYTES);
            draws++;
        }
        return draws;
    }

    /** Hands the driver the commands and records of a view drawn for depth alone. */
    void uploadDepth(DrawCommands list) {
        if (program == 0) {
            create();
        }
        GL45.glNamedBufferData(depthCommandBuffer, list.commands(), GL15.GL_STREAM_DRAW);
        GL45.glNamedBufferData(depthRecordBuffer, list.records(), GL15.GL_STREAM_DRAW);
    }

    /**
     * Draws one layer of a list for depth alone, under a projection of the view's own. The block
     * texture is read from unit 0, where the game keeps it while terrain is drawn.
     *
     * @param alphaReference the alpha at or below which a fragment is dropped, or negative to keep all
     * @return how many draws were issued
     */
    int drawDepth(DrawCommands list, int layer, TerrainArena arena, float[] projection, float alphaReference) {
        GL30.glBindVertexArray(vertexArray);
        GL20.glUseProgram(depthProgram);
        GL20.glUniformMatrix4fv(depthProjectionLocation, false, projection);
        GL20.glUniform1f(depthAlphaLocation, alphaReference);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, RECORDS_BINDING, depthRecordBuffer);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, depthCommandBuffer);
        int draws = 0;
        for (int page = 0; page < list.pages(); page++) {
            int count = list.runCount(layer, page);
            if (count == 0) {
                continue;
            }
            GL45.glVertexArrayVertexBuffer(vertexArray, 0, arena.buffer(page), 0, CubeMesh.STRIDE);
            GL43.glMultiDrawArraysIndirect(QUADS, list.runOffset(layer, page), count, DrawCommands.COMMAND_BYTES);
            draws++;
        }
        return draws;
    }

    void end() {
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, RECORDS_BINDING, 0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, FACES_BINDING, 0);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SPRITES_BINDING, 0);
        StateEnvelopeAccess.leave();
    }

    private void create() {
        program = link(VERTEX);
        cubeModelViewLocation = GL20.glGetUniformLocation(program, "cubeModelView");
        fogModeLocation = GL20.glGetUniformLocation(program, "fogMode");
        indirectProgram = link(VERTEX_INDIRECT);
        indirectFogModeLocation = GL20.glGetUniformLocation(indirectProgram, "fogMode");
        commandBuffer = GL45.glCreateBuffers();
        recordBuffer = GL45.glCreateBuffers();
        depthProgram = link(VERTEX_DEPTH, FRAGMENT_DEPTH);
        depthProjectionLocation = GL20.glGetUniformLocation(depthProgram, "viewProjection");
        depthAlphaLocation = GL20.glGetUniformLocation(depthProgram, "alphaReference");
        depthCommandBuffer = GL45.glCreateBuffers();
        depthRecordBuffer = GL45.glCreateBuffers();
        facesProgram = link(VERTEX_FACES);
        facesFogModeLocation = GL20.glGetUniformLocation(facesProgram, "fogMode");
        depthFacesProgram = link(VERTEX_DEPTH_FACES, FRAGMENT_DEPTH);
        depthFacesProjectionLocation = GL20.glGetUniformLocation(depthFacesProgram, "viewProjection");
        depthFacesAlphaLocation = GL20.glGetUniformLocation(depthFacesProgram, "alphaReference");
        emptyVertexArray = GL45.glCreateVertexArrays();
        spriteBuffer = GL45.glCreateBuffers();
        for (int which = 0; which < 2; which++) {
            faceCommandBuffers[which] = GL45.glCreateBuffers();
            faceRecordBuffers[which] = GL45.glCreateBuffers();
        }

        vertexArray = GL45.glCreateVertexArrays();
        attribute(0, 3, GL11.GL_FLOAT, false, 0);
        attribute(1, 4, GL11.GL_UNSIGNED_BYTE, true, 12);
        attribute(2, 2, GL11.GL_FLOAT, false, 16);
        attribute(3, 2, GL11.GL_SHORT, false, 24);
    }

    private static int link(String vertexSource) {
        return link(vertexSource, FRAGMENT);
    }

    private static int link(String vertexSource, String fragmentSource) {
        int linked = GL20.glCreateProgram();
        int vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);
        GL20.glAttachShader(linked, vertex);
        GL20.glAttachShader(linked, fragment);
        GL20.glLinkProgram(linked);
        if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Terrain program: " + GL20.glGetProgramInfoLog(linked));
        }
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        // The game keeps the block texture on unit 0 and the light map on unit 1.
        GL45.glProgramUniform1i(linked, GL20.glGetUniformLocation(linked, "blocks"), 0);
        int lightMap = GL20.glGetUniformLocation(linked, "lightMap");
        if (lightMap >= 0) {
            GL45.glProgramUniform1i(linked, lightMap, 1);
        }
        return linked;
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
