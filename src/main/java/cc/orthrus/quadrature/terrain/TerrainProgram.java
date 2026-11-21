package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.core.fade.FadePattern;
import cc.orthrus.quadrature.core.fade.NearFade;
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
import org.lwjgl.opengl.GL46;

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

    /**
     * What the targets for shading are written from, whichever way the vertex came: the block texture,
     * the tint alone, what the game multiplied into the vertex color, the light, and the state's row
     * of the material table. No lit color is written, and the game's shading of a face by the way it
     * is turned is divided out with its tint to leave the game's occlusion.
     */
    private static final String FRAGMENT_TARGETS = String.join("\n",
        "#version 460 compatibility",
        "layout(std430, binding = 3) readonly buffer Materials { uvec2 materials[]; };",
        "uniform sampler2D blocks;",
        "uniform float alphaReference;",
        "in vec4 vertexColor;",
        "in vec2 textureCoordinate;",
        "in vec2 lightValue;",
        "in vec3 eyePosition;",
        "flat in vec3 tint;",
        "flat in uint state;",
        "flat in uint flags;",
        "flat in vec3 eyeNormal;",
        "flat in mat3 rotation;",
        "layout(location = 0) out vec4 targetA;",
        "layout(location = 1) out vec4 targetB;",
        "layout(location = 2) out vec4 targetC;",
        "layout(location = 3) out vec4 targetD;",
        "vec3 linear(vec3 stored) {",
        "    return mix(stored / 12.92, pow((stored + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), stored));",
        "}",
        // The rule of KindEmission, word for word: a test holds its numbers to the rule's own.
        cc.orthrus.quadrature.core.material.KindEmission.GLSL,
        "void main() {",
        "    vec4 texel = texture(blocks, textureCoordinate);",
        "    if (alphaReference >= 0.0 && texel.a * vertexColor.a <= alphaReference) {",
        "        discard;",
        "    }",
        "    vec3 normal = eyeNormal;",
        "    if (dot(normal, normal) == 0.0) {",
        "        // A quad that is no whole face: the normal of the surface as drawn, turned toward the viewer.",
        "        normal = normalize(cross(dFdx(eyePosition), dFdy(eyePosition)));",
        "        if (dot(normal, eyePosition) > 0.0) {",
        "            normal = -normal;",
        "        }",
        "    }",
        "    vec3 world = normalize(normal * rotation);",
        "    uvec2 material = materials[state];",
        "    float roughness = float(material.x & 255u) / 255.0;",
        "    float reflectance = float((material.x >> 8) & 255u) / 255.0;",
        "    float subsurface = float((material.x >> 16) & 255u) / 255.0;",
        "    uint kind = (material.y >> 8) & 255u;",
        "    bool conductor = (material.y & 1u) != 0u;",
        "    // The game darkens a face by the way it is turned: 0.5 down, 1 up, 0.8 north and south, 0.6 west and east.",
        "    float shade = (flags & 4u) != 0u ? min(world.x * world.x * 0.6 + world.y * world.y * ((3.0 + world.y) / 4.0)",
        "        + world.z * world.z * 0.8, 1.0) : 1.0;",
        "    float most = max(max(tint.r, tint.g), tint.b) * shade;",
        "    float occlusion = (flags & 2u) != 0u && most > 0.0",
        "        ? clamp(max(max(vertexColor.r, vertexColor.g), vertexColor.b) / most, 0.0, 1.0) : 1.0;",
        "    vec3 albedo = linear(texel.rgb * tint);",
        "    // Each texel's own: the bright parts of an emitter's texture glow and its dark frame does not.",
        "    float emission = rivenKindEmission(kind, albedo);",
        "    targetA = vec4(world * 0.5 + 0.5, roughness);",
        "    targetB = vec4(lightValue.y / 240.0, emission, lightValue.x / 240.0, 0.375);",
        "    targetC = vec4(sqrt(albedo), conductor ? 1.0 : 0.0);",
        "    targetD = conductor ? vec4(albedo, occlusion) : vec4(reflectance, subsurface, 0.0, occlusion);",
        "}");

    private static final String TARGET_OUTPUTS = String.join("\n",
        "out vec4 vertexColor;",
        "out vec2 textureCoordinate;",
        "out vec2 lightValue;",
        "out vec3 eyePosition;",
        "flat out vec3 tint;",
        "flat out uint state;",
        "flat out uint flags;",
        "flat out vec3 eyeNormal;",
        "flat out mat3 rotation;");

    /** The vertex stage for the targets, from face records: the face's own normal is known. */
    private static final String VERTEX_TARGETS_FACES = String.join("\n",
        "#version 460 compatibility",
        PULL,
        TARGET_OUTPUTS,
        "void main() {",
        "    pull();",
        "    int word = (gl_VertexID >> 2) * 8;",
        "    mat4 modelView = cubes[gl_BaseInstance].modelView;",
        "    vec4 eye = modelView * vec4(facePosition, 1.0);",
        "    gl_Position = gl_ProjectionMatrix * eye;",
        "    eyePosition = eye.xyz;",
        "    vertexColor = faceColor;",
        "    textureCoordinate = faceTexture;",
        "    lightValue = faceLight;",
        "    uint w2 = words[word + 2];",
        "    tint = vec3(float((w2 >> 16) & 255u), float((w2 >> 8) & 255u), float(w2 & 255u)) / 255.0;",
        "    flags = w2 >> 24;",
        "    state = words[word + 1] >> 16;",
        "    uint face = (words[word] >> 12) & 7u;",
        "    float side = (face & 1u) != 0u ? 1.0 : -1.0;",
        "    vec3 normal = face < 2u ? vec3(0.0, side, 0.0) : face < 4u ? vec3(0.0, 0.0, side) : vec3(side, 0.0, 0.0);",
        "    rotation = mat3(modelView);",
        "    eyeNormal = rotation * normal;",
        "}");

    /** The vertex stage for the targets, from kept vertices that carry their quad's header. */
    private static final String VERTEX_TARGETS = String.join("\n",
        "#version 460 compatibility",
        "layout(location = 0) in vec3 position;",
        "layout(location = 1) in vec4 color;",
        "layout(location = 2) in vec2 texture;",
        "layout(location = 3) in vec2 light;",
        "layout(location = 4) in uvec2 header;",
        "struct CubeRecord { mat4 modelView; mat4 previous; };",
        "layout(std430, binding = 0) readonly buffer CubeRecords { CubeRecord cubes[]; };",
        TARGET_OUTPUTS,
        "void main() {",
        "    mat4 modelView = cubes[gl_BaseInstance].modelView;",
        "    vec4 eye = modelView * vec4(position, 1.0);",
        "    gl_Position = gl_ProjectionMatrix * eye;",
        "    eyePosition = eye.xyz;",
        "    vertexColor = color;",
        "    textureCoordinate = texture;",
        "    lightValue = light;",
        "    tint = vec3(float((header.y >> 16) & 255u), float((header.y >> 8) & 255u), float(header.y & 255u)) / 255.0;",
        "    flags = header.y >> 24;",
        "    state = header.x >> 16;",
        "    rotation = mat3(modelView);",
        "    eyeNormal = vec3(0.0);",
        "}");

    /**
     * Off unless {@code -Dquadrature.terrain.fade=true}: across a band before the edge of what near
     * terrain holds, the player's view gives its pixels up to what is drawn beyond, by the pattern
     * both sides share. Near terrain is the complement side, at frame 0. The depth drawn along the
     * light takes no part. Where it is off the stages are as they were, to the character.
     */
    static final boolean FADE = Boolean.getBoolean("quadrature.terrain.fade");

    /** Gives a vertex stage of the player's view what the fade needs: the place in the eye's space. */
    private static String fadeVertex(String source) {
        if (!FADE) {
            return source;
        }
        return source.replace("out float fogDistance;", "out float fogDistance;\nout vec3 fadeEye;")
            .replace("    fogDistance = length(eye.xyz);", "    fogDistance = length(eye.xyz);\n    fadeEye = eye.xyz;");
    }

    /**
     * Gives a fragment stage of the player's view the fade: the fragment's horizontal distance from
     * the view's position, the greater of that along x and that along z, set against the band.
     *
     * @param eye the name of the stage's input holding the fragment's place in the eye's space
     */
    private static String fadeFragment(String source, String eye, String declaredAfter) {
        if (!FADE) {
            return source;
        }
        String declarations = declaredAfter + "\n" + (eye.equals("fadeEye") ? "in vec3 fadeEye;\n" : "")
            + "uniform mat3 fadeInverse;\nuniform vec2 fadeBand;\n" + FadePattern.glsl();
        return source.replace(declaredAfter, declarations).replace("void main() {", String.join("\n",
            "void main() {",
            "    vec3 fadeAt = fadeInverse * " + eye + ";",
            "    float fadeShows = clamp((max(abs(fadeAt.x), abs(fadeAt.z)) - fadeBand.x) / (fadeBand.y - fadeBand.x), 0.0, 1.0);",
            "    if (!" + FadePattern.GLSL_FUNCTION + "(gl_FragCoord.xy, fadeShows, true, 0)) {",
            "        discard;",
            "    }"));
    }

    private static final String FRAGMENT_FADED = fadeFragment(FRAGMENT, "fadeEye", "in float fogDistance;");
    /**
     * The same for a declared emissive layer (D27): beside the color, its bloom kind's two weights into a second
     * target, the fragment's alpha their cover, blended into the target as the color is into the picture.
     */
    private static final String FRAGMENT_EMISSIVE = FRAGMENT_FADED
        .replace("layout(location = 0) out vec4 result;", String.join("\n",
            "layout(location = 0) out vec4 result;",
            "layout(location = 1) out vec4 weight;",
            "uniform vec2 kindWeights;"))
        .replace("    result = color;", String.join("\n",
            "    result = color;",
            "    weight = vec4(kindWeights, 0.0, color.a);"));
    private static final String FRAGMENT_TARGETS_FADED = fadeFragment(FRAGMENT_TARGETS, "eyePosition", "in vec3 eyePosition;");
    private final int[] fadePrograms = new int[6];
    private final float[] fadeInverse = new float[9];

    private static final int RECORDS_BINDING = 0;
    private static final int MATERIALS_BINDING = 3;
    private static final int FACES_BINDING = 1;
    private static final int SPRITES_BINDING = 2;

    private int program;
    private int cubeModelViewLocation;
    private int fogModeLocation;
    private int emissiveProgram;
    private int emissiveModelViewLocation;
    private int emissiveFogModeLocation;
    private int emissiveWeightsLocation;
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
    private int targetsProgram;
    private int targetsAlphaLocation;
    private int targetsFacesProgram;
    private int targetsFacesAlphaLocation;
    private int materialBuffer;
    /** A vertex array for vertices that carry their quad's header after the game's 28 bytes. */
    private int wideVertexArray;
    private final int[] faceCommandBuffers = new int[2];
    private final int[] faceRecordBuffers = new int[2];
    /** Which program is in use since {@link #begin}: 0 neither, 1 the direct one, 2 the indirect one. */
    /** The bytes from one vertex to the next in the pages drawn from. */
    private int vertexStride = CubeMesh.STRIDE;
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
        if (FADE) {
            // The view's rotation undone, so that a place in the eye's space is told along the world's axes.
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    fadeInverse[column * 3 + row] = modelView[row * 4 + column];
                }
            }
            float[] band = NearFade.band(TerrainHook.fadeStart(), TerrainHook.fadeEnd(), view.across());
            for (int fading : fadePrograms) {
                GL45.glProgramUniformMatrix3fv(fading, GL20.glGetUniformLocation(fading, "fadeInverse"), false, fadeInverse);
                GL45.glProgramUniform2f(fading, GL20.glGetUniformLocation(fading, "fadeBand"), band[0], band[1]);
            }
            // What was set is said, with the place the stages measure from: the eye of this matrix.
            TerrainHook.fadeDrawn(band, NearFade.origin(view.originX(), view.originY(), view.originZ(), modelView));
        }
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
            GL45.glVertexArrayVertexBuffer(vertexArray, 0, boundBuffer, 0, vertexStride);
        }
        CubeMatrix.compose(modelView, x, y, z, cubeModelView, 0);
        GL20.glUniformMatrix4fv(cubeModelViewLocation, false, cubeModelView);
        GL11.glDrawArrays(QUADS, mesh.first(layer), mesh.count(layer));
    }

    /**
     * Draws one layer of one cube as {@link #draw} does, and its bloom kind's weights over the greatest a kind carries
     * into the bound framebuffer's second target (D27).
     */
    void drawEmissive(CubeMesh mesh, int layer, float x, float y, float z, float overBlack, float overWhite) {
        if (inUse != 3) {
            GL20.glUseProgram(emissiveProgram);
            GL20.glUniform1i(emissiveFogModeLocation, fogMode);
            inUse = 3;
        }
        GL20.glUniform2f(emissiveWeightsLocation, overBlack, overWhite);
        if (boundBuffer != mesh.buffer(layer)) {
            boundBuffer = mesh.buffer(layer);
            GL45.glVertexArrayVertexBuffer(vertexArray, 0, boundBuffer, 0, vertexStride);
        }
        CubeMatrix.compose(modelView, x, y, z, cubeModelView, 0);
        GL20.glUniformMatrix4fv(emissiveModelViewLocation, false, cubeModelView);
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
            GL45.glVertexArrayVertexBuffer(vertexArray, 0, boundBuffer, 0, vertexStride);
            GL43.glMultiDrawArraysIndirect(QUADS, list.runOffset(layer, page), count, DrawCommands.COMMAND_BYTES);
            draws++;
        }
        return draws;
    }

    /** The player's commands and records as last uploaded, for culling on the GPU to read. */
    int commandBuffer() {
        return commandBuffer;
    }

    int recordBuffer() {
        return recordBuffer;
    }

    /** A view's face-record commands and records as last uploaded (0 the player's). */
    int faceCommandBuffer(int which) {
        return faceCommandBuffers[which];
    }

    int faceRecordBuffer(int which) {
        return faceRecordBuffers[which];
    }

    /**
     * As {@link #drawIndirect}, from commands a compute stage kept: {@code kept} holds each run at the list's
     * offsets and {@code counts} each run's count, which the GPU reads.
     */
    int drawIndirectCount(DrawCommands list, int layer, TerrainArena arena, int kept, int counts) {
        GL20.glUseProgram(indirectProgram);
        GL20.glUniform1i(indirectFogModeLocation, fogMode);
        inUse = 2;
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, RECORDS_BINDING, recordBuffer);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, kept);
        GL15.glBindBuffer(GL46.GL_PARAMETER_BUFFER, counts);
        int draws = 0;
        for (int page = 0; page < list.pages(); page++) {
            int count = list.runCount(layer, page);
            if (count == 0) {
                continue;
            }
            boundBuffer = arena.buffer(page);
            GL45.glVertexArrayVertexBuffer(vertexArray, 0, boundBuffer, 0, vertexStride);
            GL46.glMultiDrawArraysIndirectCount(QUADS, list.runOffset(layer, page), list.run(layer, page) * 4L, count,
                DrawCommands.COMMAND_BYTES);
            draws++;
        }
        GL15.glBindBuffer(GL46.GL_PARAMETER_BUFFER, 0);
        return draws;
    }

    /** As {@link #drawFaces}, from commands a compute stage kept, the counts read on the GPU. */
    int drawFacesCount(DrawCommands list, int layer, TerrainArena faces, int kept, int counts) {
        GL30.glBindVertexArray(emptyVertexArray);
        GL20.glUseProgram(facesProgram);
        GL20.glUniform1i(facesFogModeLocation, fogMode);
        inUse = 3;
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, RECORDS_BINDING, faceRecordBuffers[0]);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SPRITES_BINDING, spriteBuffer);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, kept);
        GL15.glBindBuffer(GL46.GL_PARAMETER_BUFFER, counts);
        int draws = 0;
        for (int page = 0; page < list.pages(); page++) {
            int count = list.runCount(layer, page);
            if (count == 0) {
                continue;
            }
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, FACES_BINDING, faces.buffer(page));
            GL46.glMultiDrawArraysIndirectCount(QUADS, list.runOffset(layer, page), list.run(layer, page) * 4L, count,
                DrawCommands.COMMAND_BYTES);
            draws++;
        }
        GL15.glBindBuffer(GL46.GL_PARAMETER_BUFFER, 0);
        GL30.glBindVertexArray(vertexArray);
        return draws;
    }

    /** Says how far apart vertices lie in the pages drawn from: the game's 28 bytes, or more where a header follows. */
    void vertexStride(int bytes) {
        if (program == 0) {
            create();
        }
        vertexStride = bytes;
        if (bytes > CubeMesh.STRIDE && wideVertexArray == 0) {
            // The game's four attributes where they were, and the header's two words after them.
            int usual = vertexArray;
            wideVertexArray = GL45.glCreateVertexArrays();
            vertexArray = wideVertexArray;
            attribute(0, 3, GL11.GL_FLOAT, false, 0);
            attribute(1, 4, GL11.GL_UNSIGNED_BYTE, true, 12);
            attribute(2, 2, GL11.GL_FLOAT, false, 16);
            attribute(3, 2, GL11.GL_SHORT, false, 24);
            GL45.glEnableVertexArrayAttrib(wideVertexArray, 4);
            GL45.glVertexArrayAttribIFormat(wideVertexArray, 4, 2, GL11.GL_UNSIGNED_INT, CubeMesh.STRIDE);
            GL45.glVertexArrayAttribBinding(wideVertexArray, 4, 0);
            vertexArray = bytes > CubeMesh.STRIDE ? wideVertexArray : usual;
        }
    }

    /** The buffer the material table lies in, bound at shader storage binding 3 while the targets are drawn. */
    int materialBuffer() {
        if (program == 0) {
            create();
        }
        return materialBuffer;
    }

    /** Hands the driver the material table: eight bytes a state. */
    void uploadMaterials(byte[] table) {
        if (program == 0) {
            create();
        }
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocateDirect(table.length);
        data.put(table).flip();
        GL45.glNamedBufferData(materialBuffer, data, GL15.GL_STATIC_DRAW);
    }

    /**
     * Draws one layer of what the player's view draws into the targets for shading, from the commands
     * and records already handed over for that view: the kept vertices, then the face records.
     *
     * @param alphaReference the alpha at or below which a fragment is dropped, or negative to keep all
     * @return how many draws were issued
     */
    int drawTargets(DrawCommands list, DrawCommands faceList, int layer, TerrainArena arena, TerrainArena faces,
        float alphaReference) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, MATERIALS_BINDING, materialBuffer);
        GL30.glBindVertexArray(vertexArray);
        GL20.glUseProgram(targetsProgram);
        GL20.glUniform1f(targetsAlphaLocation, alphaReference);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, RECORDS_BINDING, recordBuffer);
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        int draws = 0;
        for (int page = 0; page < list.pages(); page++) {
            int count = list.runCount(layer, page);
            if (count == 0) {
                continue;
            }
            GL45.glVertexArrayVertexBuffer(vertexArray, 0, arena.buffer(page), 0, vertexStride);
            GL43.glMultiDrawArraysIndirect(QUADS, list.runOffset(layer, page), count, DrawCommands.COMMAND_BYTES);
            draws++;
        }
        GL30.glBindVertexArray(emptyVertexArray);
        GL20.glUseProgram(targetsFacesProgram);
        GL20.glUniform1f(targetsFacesAlphaLocation, alphaReference);
        draws += pages(faceList, layer, faces, 0);
        GL30.glBindVertexArray(vertexArray);
        inUse = 0;
        boundBuffer = 0;
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, MATERIALS_BINDING, 0);
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
            GL45.glVertexArrayVertexBuffer(vertexArray, 0, arena.buffer(page), 0, vertexStride);
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
        program = link(fadeVertex(VERTEX));
        cubeModelViewLocation = GL20.glGetUniformLocation(program, "cubeModelView");
        fogModeLocation = GL20.glGetUniformLocation(program, "fogMode");
        emissiveProgram = link(fadeVertex(VERTEX), FRAGMENT_EMISSIVE);
        emissiveModelViewLocation = GL20.glGetUniformLocation(emissiveProgram, "cubeModelView");
        emissiveFogModeLocation = GL20.glGetUniformLocation(emissiveProgram, "fogMode");
        emissiveWeightsLocation = GL20.glGetUniformLocation(emissiveProgram, "kindWeights");
        indirectProgram = link(fadeVertex(VERTEX_INDIRECT));
        indirectFogModeLocation = GL20.glGetUniformLocation(indirectProgram, "fogMode");
        commandBuffer = GL45.glCreateBuffers();
        recordBuffer = GL45.glCreateBuffers();
        depthProgram = link(VERTEX_DEPTH, FRAGMENT_DEPTH);
        depthProjectionLocation = GL20.glGetUniformLocation(depthProgram, "viewProjection");
        depthAlphaLocation = GL20.glGetUniformLocation(depthProgram, "alphaReference");
        depthCommandBuffer = GL45.glCreateBuffers();
        depthRecordBuffer = GL45.glCreateBuffers();
        facesProgram = link(fadeVertex(VERTEX_FACES));
        facesFogModeLocation = GL20.glGetUniformLocation(facesProgram, "fogMode");
        depthFacesProgram = link(VERTEX_DEPTH_FACES, FRAGMENT_DEPTH);
        depthFacesProjectionLocation = GL20.glGetUniformLocation(depthFacesProgram, "viewProjection");
        depthFacesAlphaLocation = GL20.glGetUniformLocation(depthFacesProgram, "alphaReference");
        emptyVertexArray = GL45.glCreateVertexArrays();
        spriteBuffer = GL45.glCreateBuffers();
        targetsProgram = link(VERTEX_TARGETS, FRAGMENT_TARGETS_FADED);
        targetsAlphaLocation = GL20.glGetUniformLocation(targetsProgram, "alphaReference");
        targetsFacesProgram = link(VERTEX_TARGETS_FACES, FRAGMENT_TARGETS_FADED);
        targetsFacesAlphaLocation = GL20.glGetUniformLocation(targetsFacesProgram, "alphaReference");
        materialBuffer = GL45.glCreateBuffers();
        fadePrograms[0] = program;
        fadePrograms[1] = indirectProgram;
        fadePrograms[2] = facesProgram;
        fadePrograms[3] = targetsProgram;
        fadePrograms[4] = targetsFacesProgram;
        fadePrograms[5] = emissiveProgram;
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
        return link(vertexSource, FRAGMENT_FADED);
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
