package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.core.lod.NearCover;
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
 * shade for each direction, then the world's fog as the fixed-function state holds it when the draw
 * is made, by distance from the eye, exactly as near terrain's program applies it: with the shared
 * fog on (see {@code DistantHook.fog}) both fade by one rule.
 *
 * <p>Water's top faces are lowered to the level the game draws a water surface at
 * ({@link NearCover#WATER_SURFACE_DROP}), so near and distant water meet without a step.
 *
 * <p>Two debug views ({@code quadrature.distant.debug}). {@code level} gives every face a color for
 * its tile's level. {@code box} shows where the near box stands open: after the ordinary draw, the
 * faces the box holds are drawn again in magenta, each moved straight away from the eye by
 * {@link #BOX_VIEW_PUSH} of its distance. A face moved so keeps its place on the screen and loses
 * to anything drawn at its own depth or nearer, so a pixel is magenta only where distant terrain
 * has ground in the box and near terrain drew nothing in front of it. Near terrain's cut-out
 * leaves let it through between their texels, where distant terrain's solid crowns stand behind
 * them. Distant water is left out of that second draw, and the game draws its translucent layer
 * afterwards, so near water with no near sea bed behind it shows as magenta seen through water.
 *
 * <p>The first box view drew those faces at their own depth and counted on near terrain to hide
 * them. Distant ground lies in the plane of near ground, so depth tied and magenta covered near
 * terrain almost everywhere (the third run): it showed where the two coincide, not where near
 * terrain is missing.
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

    /**
     * How far the box view moves its faces from the eye, as a share of their distance: a hundredth,
     * 2 blocks at the box's edge and a tenth of a block at 10. Any amount settles a tie of depth;
     * this much also clears the lowered water surface and near terrain's own offsets.
     */
    static final float BOX_VIEW_PUSH = 0.01f;

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
        "uniform mat4 modelView;",
        "uniform float daylight;",
        "uniform float topDrop;",
        "uniform int debugMode;",
        "uniform int overlay;",
        "uniform mat4 projection;",
        "out vec3 fromOrigin;",
        "out vec3 fromEye;",
        "out vec3 faceColor;",
        "const vec3 LEVEL[8] = vec3[8](vec3(1.0, 0.2, 0.2), vec3(1.0, 0.6, 0.1), vec3(1.0, 1.0, 0.2),",
        "    vec3(0.2, 0.9, 0.2), vec3(0.2, 0.9, 0.9), vec3(0.3, 0.4, 1.0), vec3(0.8, 0.3, 1.0), vec3(1.0));",
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
        "        place = vec3((float(x) + across * width) * tile.cell,",
        "            float(y) - (direction == 0u ? topDrop : 0.0),",
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
        "    fromEye = (modelView * vec4(fromOrigin, 1.0)).xyz;",
        "    gl_Position = viewProjection * vec4(fromOrigin, 1.0);",
        "    if (overlay == 1) {",
        "        // Straight away from the eye: the same place on the screen, a little deeper.",
        "        gl_Position = projection * vec4(fromEye * " + (1f + BOX_VIEW_PUSH) + ", 1.0);",
        "    }",
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
        "    if (debugMode == 2) {",
        "        faceColor = LEVEL[min(tile.level, 7u)] * (0.55 + 0.45 * SHADE[direction]) * (1.0 - 0.1 * closed);",
        "    }",
        "}");
    private static final String FRAGMENT = String.join("\n",
        "#version 460 compatibility",
        "uniform vec3 nearMinimum;",
        "uniform vec3 nearMaximum;",
        "uniform int fogMode;",
        "uniform int overlay;",
        "in vec3 fromOrigin;",
        "in vec3 fromEye;",
        "in vec3 faceColor;",
        "layout(location = 0) out vec4 result;",
        "void main() {",
        "    // What lies inside the box of loaded cubes is near terrain's to draw.",
        "    bool inside = all(greaterThanEqual(fromOrigin, nearMinimum)) && all(lessThan(fromOrigin, nearMaximum));",
        "    if (overlay == 1) {",
        "        // The box view's second draw: only what the box holds; near terrain hides it by depth.",
        "        if (!inside) {",
        "            discard;",
        "        }",
        "        result = vec4(1.0, 0.0, 1.0, 1.0);",
        "        return;",
        "    }",
        "    if (inside) {",
        "        discard;",
        "    }",
        "    // The world's fog, as near terrain's program applies it.",
        "    float fogDistance = length(fromEye);",
        "    float clear = 1.0;",
        "    if (fogMode == 1) {",
        "        clear = (gl_Fog.end - fogDistance) * gl_Fog.scale;",
        "    } else if (fogMode == 2) {",
        "        clear = exp(-gl_Fog.density * fogDistance);",
        "    } else if (fogMode == 3) {",
        "        float d = gl_Fog.density * fogDistance;",
        "        clear = exp(-d * d);",
        "    }",
        "    result = vec4(mix(gl_Fog.color.rgb, faceColor, clamp(clear, 0.0, 1.0)), 1.0);",
        "}");

    private int program;
    private int viewProjectionLocation;
    private int daylightLocation;
    private int nearMinimumLocation;
    private int nearMaximumLocation;
    private int modelViewLocation;
    private int topDropLocation;
    private int fogModeLocation;
    private int debugModeLocation;
    private int overlayLocation;
    private int projectionLocation;
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
        GL20.glUniformMatrix4fv(modelViewLocation, false, modelView);
        GL20.glUniform1i(debugModeLocation, DistantSettings.DEBUG);
        GL20.glUniform1i(fogModeLocation, fogMode());
        GL30.glBindVertexArray(vertexArray);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, TILES_BINDING, recordBuffer);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, MATERIALS_BINDING, materials.materialBuffer());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BIOMES_BINDING, materials.biomeBuffer());
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        GL20.glUniform1i(overlayLocation, 0);
        int draws = layers(list, arena, true);
        if (DistantSettings.DEBUG == DistantSettings.DEBUG_BOX) {
            // The box view: the faces the box holds, in magenta, a little deeper than they lie.
            GL20.glUniform1i(overlayLocation, 1);
            GL20.glUniformMatrix4fv(projectionLocation, false, projection);
            // Not the water: its faces lie over the sea bed, and near water, which writes no depth,
            // cannot hide them. Without them, magenta seen through near water is a missing sea bed.
            draws += layers(list, arena, false);
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

    /** The frame's three layers, one indirect draw for each page that has commands in it. */
    private int layers(TileDraws list, DistantArena arena, boolean water) {
        int draws = 0;
        for (int layer = 0; layer < TileDraws.LAYERS; layer++) {
            if (layer == TileDraws.WATER && !water) {
                continue;
            }
            for (int page = 0; page < list.pages(); page++) {
                int count = list.runCount(layer, page);
                if (count == 0) {
                    continue;
                }
                GL20.glUniform1f(topDropLocation, NearCover.topDrop(layer));
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, PAGE_BINDING, arena.buffer(page));
                GL43.glMultiDrawArraysIndirect(GL11.GL_TRIANGLES, list.runOffset(layer, page), count,
                    TileDraws.COMMAND_BYTES);
                draws++;
            }
        }
        return draws;
    }

    /**
     * Which fog the fragment stage applies: none, linear, exponential or squared exponential. Fog
     * is fixed-function state a shader cannot read as on or off, or by mode, so it is asked of the
     * driver, as near terrain does. With the shared fog off, or in the level view, distant terrain
     * takes none: the game's own would hide all of it.
     */
    private static int fogMode() {
        if (!DistantSettings.FOG || DistantSettings.DEBUG == DistantSettings.DEBUG_LEVEL
            || !GL11.glIsEnabled(GL11.GL_FOG)) {
            return 0;
        }
        int mode = GL11.glGetInteger(GL11.GL_FOG_MODE);
        return mode == GL11.GL_LINEAR ? 1 : mode == GL11.GL_EXP ? 2 : 3;
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
        modelViewLocation = GL20.glGetUniformLocation(program, "modelView");
        topDropLocation = GL20.glGetUniformLocation(program, "topDrop");
        fogModeLocation = GL20.glGetUniformLocation(program, "fogMode");
        debugModeLocation = GL20.glGetUniformLocation(program, "debugMode");
        overlayLocation = GL20.glGetUniformLocation(program, "overlay");
        projectionLocation = GL20.glGetUniformLocation(program, "projection");
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
