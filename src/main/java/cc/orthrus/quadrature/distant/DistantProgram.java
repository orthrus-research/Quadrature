package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.core.lod.TileDraws;
import cc.orthrus.quadrature.frame.StateEnvelopeAccess;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

/**
 * The shader that draws distant tiles, and the draws. A face is not stored as vertices: its 64-bit
 * record lies in the tile's page, bound as a storage buffer, and the vertex stage builds each of
 * the face's six vertices from it. The vertex number names the face (six to a face) and the corner;
 * the draw's base instance names the tile's record, as near terrain names a cube's.
 *
 * <p>Reading records from a storage buffer was chosen over integer vertex attributes with one
 * instance a face because it leaves the base instance free for the tile, needs no vertex layout, and
 * draws a page's layer with one indirect call whose commands are plain ranges of the page.
 *
 * <p>The projection and model-view are the view's, read from the driver, so depth is whatever the
 * frame uses, reversed or not, and matches near terrain's exactly. Color is the material's flat
 * color, its biome's tint, the face's sky light and corner occlusion, the day's light and a fixed
 * shade for each direction. The game's fog is not applied.
 */
final class DistantProgram {

    /**
     * How much darker a corner is for each of the up to three cells that close it in. The game's own
     * smooth lighting darkens by about a fifth a cell, which suits a block seen from near by; drawn
     * at every one-block step of ground seen from hundreds of blocks away it outlined every contour
     * in the first frame. 0.08 keeps a fully closed corner at three quarters of its light: enough
     * to read a step, not enough to draw a line.
     */
    static final float OCCLUSION_STEP = 0.08f;

    private static final int TILES_BINDING = 0;
    private static final int PAGE_BINDING = 1;
    private static final int MATERIALS_BINDING = 2;
    private static final int BIOMES_BINDING = 3;

    private static final String VERTEX = String.join("\n",
        "#version 460 compatibility",
        "struct Tile { vec3 origin; float cell; uint tintWord; uint level; float shows; uint unused; };",
        "layout(std430, binding = 0) readonly buffer Tiles { Tile tiles[]; };",
        "layout(std430, binding = 1) readonly buffer Page { uint words[]; };",
        "layout(std430, binding = 2) readonly buffer Materials { uint materials[]; };",
        "layout(std430, binding = 3) readonly buffer Biomes { uint biomes[]; };",
        "uniform mat4 viewProjection;",
        "uniform float daylight;",
        "out vec3 fromOrigin;",
        "out vec3 faceColor;",
        "const int CORNER[6] = int[6](0, 1, 2, 2, 1, 3);",
        "const float SHADE[6] = float[6](1.0, 0.5, 0.8, 0.8, 0.6, 0.6);",
        "vec3 rgb(uint value) {",
        "    return vec3(float((value >> 16) & 255u), float((value >> 8) & 255u), float(value & 255u)) / 255.0;",
        "}",
        "void main() {",
        "    int face = gl_VertexID / 6;",
        "    int corner = CORNER[gl_VertexID - face * 6];",
        "    uint low = words[2 * face];",
        "    uint high = words[2 * face + 1];",
        "    uint x = low & 63u;",
        "    uint z = (low >> 6) & 63u;",
        "    uint y = (low >> 12) & 4095u;",
        "    uint extent = (low >> 24) | ((high & 15u) << 8);",
        "    uint direction = (high >> 4) & 7u;",
        "    uint material = (high >> 7) & 8191u;",
        "    uint light = (high >> 20) & 15u;",
        "    uint occlusion = high >> 24;",
        "    Tile tile = tiles[gl_BaseInstance];",
        "    float across = float(corner & 1);",
        "    float along = float(corner >> 1);",
        "    vec3 place;",
        "    if (direction < 2u) {",
        "        float width = float((extent & 63u) + 1u);",
        "        float depth = float((extent >> 6) + 1u);",
        "        place = vec3((float(x) + across * width) * tile.cell, float(y),",
        "            (float(z) + along * depth) * tile.cell);",
        "    } else {",
        "        // A side face's corners run lower left, lower right, upper left, upper right as seen",
        "        // from outside it: north and east faces have their left at the higher coordinate.",
        "        float px = float(x);",
        "        float pz = float(z);",
        "        if (direction == 2u) { px += 1.0 - across; }",
        "        else if (direction == 3u) { px += across; pz += 1.0; }",
        "        else if (direction == 4u) { pz += across; }",
        "        else { px += 1.0; pz += 1.0 - across; }",
        "        place = vec3(px * tile.cell, float(y) + along * float(extent + 1u), pz * tile.cell);",
        "    }",
        "    fromOrigin = tile.origin + place;",
        "    gl_Position = viewProjection * vec4(fromOrigin, 1.0);",
        "    uint entry = materials[2u * material + (direction < 2u ? 0u : 1u)];",
        "    vec3 color = rgb(entry);",
        "    uint tint = entry >> 24;",
        "    if (tint != 0u) {",
        "        uint column = z * 64u + x;",
        "        uint biome = (words[tile.tintWord + (column >> 2)] >> (8u * (column & 3u))) & 255u;",
        "        color *= rgb(biomes[3u * biome + tint - 1u]);",
        "    }",
        "    float closed = float((occlusion >> (2 * corner)) & 3u);",
        "    float lit = 0.2 + 0.8 * (float(light) / 15.0) * daylight;",
        "    faceColor = color * lit * (1.0 - " + OCCLUSION_STEP + " * closed) * SHADE[direction];",
        "}");
    private static final String FRAGMENT = String.join("\n",
        "#version 460 compatibility",
        "uniform vec3 nearMinimum;",
        "uniform vec3 nearMaximum;",
        "in vec3 fromOrigin;",
        "in vec3 faceColor;",
        "layout(location = 0) out vec4 result;",
        "void main() {",
        "    // What lies inside the box of loaded cubes is near terrain's to draw.",
        "    if (all(greaterThanEqual(fromOrigin, nearMinimum)) && all(lessThan(fromOrigin, nearMaximum))) {",
        "        discard;",
        "    }",
        "    result = vec4(faceColor, 1.0);",
        "}");

    private int program;
    private int viewProjectionLocation;
    private int daylightLocation;
    private int nearMinimumLocation;
    private int nearMaximumLocation;
    private int vertexArray;
    private int commandBuffer;
    private int recordBuffer;
    private final float[] viewProjection = new float[16];

    /**
     * Draws a frame's list: its three layers in turn, each with one indirect draw for each page that
     * has commands in it.
     *
     * @param modelView  the view's matrices, column-major, as the driver holds them
     * @param nearBox    the box of loaded cubes, less the view's origin: minimum then maximum
     * @param daylight   how bright the sky is, 0 to 1
     * @return how many draws were issued
     */
    int draw(TileDraws list, DistantArena arena, DistantMaterials materials, float[] modelView, float[] projection,
        float[] nearBox, float daylight) {
        if (program == 0) {
            create();
        }
        GL45.glNamedBufferData(commandBuffer, list.commands(), GL15.GL_STREAM_DRAW);
        GL45.glNamedBufferData(recordBuffer, list.records(), GL15.GL_STREAM_DRAW);
        multiply(projection, modelView, viewProjection);
        // Faces are not culled by winding: the groups that turn away from the eye are not drawn at
        // all, and the rest of a group mostly faces it.
        boolean culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        if (culling) {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        GL20.glUseProgram(program);
        GL20.glUniformMatrix4fv(viewProjectionLocation, false, viewProjection);
        GL20.glUniform1f(daylightLocation, daylight);
        GL20.glUniform3f(nearMinimumLocation, nearBox[0], nearBox[1], nearBox[2]);
        GL20.glUniform3f(nearMaximumLocation, nearBox[3], nearBox[4], nearBox[5]);
        GL30.glBindVertexArray(vertexArray);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, TILES_BINDING, recordBuffer);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, MATERIALS_BINDING, materials.materialBuffer());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BIOMES_BINDING, materials.biomeBuffer());
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        int draws = 0;
        for (int layer = 0; layer < TileDraws.LAYERS; layer++) {
            for (int page = 0; page < list.pages(); page++) {
                int count = list.runCount(layer, page);
                if (count == 0) {
                    continue;
                }
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, PAGE_BINDING, arena.buffer(page));
                GL43.glMultiDrawArraysIndirect(GL11.GL_TRIANGLES, list.runOffset(layer, page), count,
                    TileDraws.COMMAND_BYTES);
                draws++;
            }
        }
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
        for (int binding = TILES_BINDING; binding <= BIOMES_BINDING; binding++) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, 0);
        }
        if (culling) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        StateEnvelopeAccess.leave();
        return draws;
    }

    /** {@code left * right} for column-major matrices. */
    static void multiply(float[] left, float[] right, float[] into) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += left[k * 4 + row] * right[column * 4 + k];
                }
                into[column * 4 + row] = sum;
            }
        }
    }

    private void create() {
        program = GL20.glCreateProgram();
        int vertex = compile(GL20.GL_VERTEX_SHADER, VERTEX);
        int fragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Distant terrain program: " + GL20.glGetProgramInfoLog(program));
        }
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        viewProjectionLocation = GL20.glGetUniformLocation(program, "viewProjection");
        daylightLocation = GL20.glGetUniformLocation(program, "daylight");
        nearMinimumLocation = GL20.glGetUniformLocation(program, "nearMinimum");
        nearMaximumLocation = GL20.glGetUniformLocation(program, "nearMaximum");
        // No vertex attributes: every vertex comes from the page.
        vertexArray = GL45.glCreateVertexArrays();
        commandBuffer = GL45.glCreateBuffers();
        recordBuffer = GL45.glCreateBuffers();
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("Distant terrain shader: " + GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
