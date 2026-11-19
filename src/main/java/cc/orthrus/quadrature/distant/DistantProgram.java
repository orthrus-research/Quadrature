package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.core.fade.FadePattern;
import cc.orthrus.quadrature.core.material.KindEmission;
import cc.orthrus.quadrature.core.lod.MaterialTable;
import cc.orthrus.quadrature.core.lod.NearCover;
import cc.orthrus.quadrature.core.lod.TileDraws;
import cc.orthrus.quadrature.frame.StateEnvelopeAccess;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
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
 * ({@link NearCover#WATER_SURFACE_DROP}), so near and distant water meet without a step. Water is the
 * last layer and is blended over the opaque ones by the alpha of the game's water image, which the
 * material carries as its cover: the game draws near water so, over its bed, and distant water drawn
 * opaque stood out far brighter (47, 68, 244 against 36, 51, 173 in the fourth run). Color, cover
 * and fog are worked out apart, so a later stage can light the surface itself.
 *
 * <p>Near the join a face of level 0 shows its material's image, not a flat color, so that near
 * terrain's images do not end on a line. The images are layers of one array texture, sixteen texels
 * a side with smaller copies, two a material as the colors are; a face finds its texel from its
 * place in its tile, whose corner is a block's corner. From {@code quadrature.distant.detailStart}
 * to {@code detailEnd} blocks the image fades into the flat color, which is its mean, so nothing
 * changes at the end and farther levels need no images. Water has none. The fragment stage works out
 * the surface (image or flat color, with the biome's tint), then its light, then the fog, each apart,
 * so that a later stage can take the surface and light it by other means.
 *
 * <p>Where two levels meet both are drawn over a band, one tile of the coarser wide, and each pixel
 * keeps one of them by {@link FadePattern}, the coarser showing more toward the edge of its hole. A
 * tile gives pixels up only to a partner that is stored: the coarser to its four children, the finer
 * to the tile over it, so a level that is still arriving leaves no hole. Which level draws a place
 * is decided for each fragment: the finest level with a shown tile there, read from one bit a tile
 * ({@code Cover}), so a level's fill tiles inside its hole are drawn exactly where finer tiles are
 * not yet. A tile that has just been
 * stored comes in by the same pattern over a few frames. A tile kept past
 * its ring takes no part. See {@code Rings.overlap} and docs/DISTANT.md, section 14.
 *
 * <p>Leaves are drawn solid. Distant terrain begins some 180 blocks out, where a leaf image's texel
 * is a third of a pixel or less: the game's own leaves are solid there, since its smaller copies of
 * the image average the clear texels in and the alpha test passes what is left. A cut-out at that
 * size is either a pattern at the scale of a pixel, which crawls as the camera moves, or coverage
 * from a multisampled target, which the game's frame is not. So a leaf face keeps its whole
 * rectangle and takes the color those smaller copies give it ({@code MaterialTable.seenFromAfar}).
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

    /** Room in the fragment stage for every level's bits: 8 levels of up to 16 tiles a side. */
    private static final int MASK_UINTS = 64;

    private static final int TILES_BINDING = 0;
    private static final int PAGE_BINDING = 1;
    private static final int MATERIALS_BINDING = 2;
    private static final int BIOMES_BINDING = 3;
    /**
     * The mean of the game's occlusion over the tops of near crowns, measured in the targets (the
     * ninth run): 57 % of their pixels open and the rest spread from 0.5 up, where leaves stand
     * over leaves. Distant crowns wrote 0.999 beside it.
     */
    static final float LEAF_OCCLUSION = 0.936f;

    /** Near terrain's material table, bound here for the shaded path. */
    private static final int SHADED_BINDING = 4;

    private static final String VERTEX = String.join("\n",
        "#version 460 compatibility",
        "struct Tile { vec3 origin; float cell; uint tintWord; uint level; float shows; uint partners; };",
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
        "uniform int imageLayers;",
        "uniform int imaged;",
        "out vec3 fromOrigin;",
        "out vec3 fromEye;",
        "// What the surface is, apart from how it is lit: its flat color with the biome's tint, the",
        "// tint alone for its image, and where its image lies.",
        "out vec3 faceAlbedo;",
        "out vec3 faceTint;",
        "out vec2 faceTexel;",
        "flat out float faceLayer;",
        "// How far below its top a side face's fragment lies, in blocks; 0 on a top or bottom face.",
        "out float faceBelow;",
        "// How it is lit: sky light, the day, corner occlusion and the shade of its direction.",
        "out float faceLight;",
        "out float faceCover;",
        "// A hair's breadth into the block a side face belongs to, so that the face is counted to",
        "// that block's chunk column and not to the one it looks at.",
        "flat out vec2 faceInto;",
        "// The tile's level, and how much of it shows: 0 to 1 while it arrives, below 0 for a tile",
        "// kept past its ring, which takes no part in any fade.",
        "flat out int faceLevel;",
        "flat out float faceShows;",
        "// Which of the tile's partners in a crossfade are stored: 1 its children, 2 the tile over it.",
        "flat out int facePartners;",
        "// For the targets: which way the face looks, its state's number in near terrain's table, its",
        "// sky light as stored, 0 to 1, and its corner occlusion as a factor.",
        "flat out int faceDirection;",
        "flat out uint faceState;",
        "flat out float faceSky;",
        "out float faceOcclusion;",
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
        "        // A tile's corner is a block's corner, so a block's image repeats a block apart.",
        "        faceTexel = place.xz;",
        "        faceBelow = 0.0;",
        "        faceInto = vec2(0.0);",
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
        "        // Along the face and down it; a mirrored image is not told from one that is not.",
        "        faceTexel = vec2(direction < 4u ? place.x : place.z, -place.y);",
        "        faceBelow = float(y) + float(extent + 1u) - place.y;",
        "        faceInto = direction == 2u ? vec2(0.0, 0.01) : direction == 3u ? vec2(0.0, -0.01)",
        "            : direction == 4u ? vec2(0.01, 0.0) : vec2(-0.01, 0.0);",
        "    }",
        "    faceLevel = int(tile.level);",
        "    faceShows = tile.shows;",
        "    facePartners = int(tile.partners);",
        "    fromOrigin = tile.origin + place;",
        "    fromEye = (modelView * vec4(fromOrigin, 1.0)).xyz;",
        "    gl_Position = viewProjection * vec4(fromOrigin, 1.0);",
        "    if (overlay == 1) {",
        "        // Straight away from the eye: the same place on the screen, a little deeper.",
        "        gl_Position = projection * vec4(fromEye * " + (1f + BOX_VIEW_PUSH) + ", 1.0);",
        "    }",
        "    // Three words a material: its top, its sides, and its state's number, which nothing reads",
        "    // yet. Its images are two layers a material.",
        "    uint side = direction < 2u ? 0u : 1u;",
        "    uint entry = materials[" + MaterialTable.ENTRY_WORDS + "u * material + side];",
        "    uint slot = 2u * material + side;",
        "    uint tint = (entry >> 24) & 3u;",
        "    faceCover = float(entry >> 26) / 63.0;",
        "    faceTint = vec3(1.0);",
        "    if (tint != 0u) {",
        "        uint column = z * 64u + x;",
        "        uint biome = (words[tile.tintWord + (column >> 2)] >> (8u * (column & 3u))) & 255u;",
        "        faceTint = rgb(biomes[3u * biome + tint - 1u]);",
        "    }",
        "    faceAlbedo = rgb(entry) * faceTint;",
        "    // Images are for level 0, whose cells are blocks, and not for water; -1 is none.",
        "    faceLayer = imaged == 1 && tile.level == 0u && int(slot) < imageLayers ? float(slot) : -1.0;",
        "    float closed = float((occlusion >> (2 * corner)) & 3u);",
        "    float lit = 0.2 + 0.8 * (float(light) / 15.0) * daylight;",
        "    faceLight = lit * (1.0 - " + OCCLUSION_STEP + " * closed) * SHADE[direction];",
        "    faceDirection = int(direction);",
        "    faceState = materials[" + MaterialTable.ENTRY_WORDS + "u * material + " + MaterialTable.STATE_WORD + "u];",
        "    faceSky = float(light) / 15.0;",
        "    // For the targets, occlusion on the game's scale, which near terrain writes: 1 open, 0.2",
        "    // less for each closed neighbor of the corner, 0.4 where both edge neighbors are closed.",
        "    // A coarse cell stands for many blocks of which only those along the edge are closed in:",
        "    // its value is the mean over the cell, the fine value's darkening over the cell's size.",
        "    float game = closed >= 3.0 ? 0.4 : 1.0 - 0.2 * closed;",
        "    faceOcclusion = 1.0 - (1.0 - game) / tile.cell;",
        "    if (debugMode == 2) {",
        "        faceAlbedo = LEVEL[min(tile.level, 7u)];",
        "        faceLight = (0.55 + 0.45 * SHADE[direction]) * (1.0 - 0.1 * closed);",
        "        faceLayer = -1.0;",
        "    }",
        "}");
    /**
     * The fragment stage's last step on the forward path, and its output: the surface times the
     * face's own light, then the world's fog. Everything before it (what near terrain covers, the
     * fades, the surface) is the same whatever comes last, so a path that hands the surface to a
     * later stage instead of lighting it replaces this text and nothing else: two functions,
     * {@code finish} for a surface and {@code finishFlat} for a debug color.
     */
    private static final String FORWARD_OUTPUT = String.join("\n",
        "layout(location = 0) out vec4 result;",
        "void finish(vec3 albedo, float fogDistance) {",
        "    // Its light, kept apart from the surface so that a later stage can light it instead.",
        "    vec3 lit = albedo * faceLight;",
        "    // The world's fog, as near terrain's program applies it.",
        "    float clear = 1.0;",
        "    if (fogMode == 1) {",
        "        clear = (gl_Fog.end - fogDistance) * gl_Fog.scale;",
        "    } else if (fogMode == 2) {",
        "        clear = exp(-gl_Fog.density * fogDistance);",
        "    } else if (fogMode == 3) {",
        "        float d = gl_Fog.density * fogDistance;",
        "        clear = exp(-d * d);",
        "    }",
        "    // Cover matters only to the water layer, which is blended over what was drawn before it.",
        "    // Leaves carry theirs too and are drawn solid: see the class comment.",
        "    result = vec4(mix(gl_Fog.color.rgb, lit, clamp(clear, 0.0, 1.0)), faceCover);",
        "}",
        "void finishFlat(vec3 color) {",
        "    result = vec4(color, 1.0);",
        "}");

    /**
     * The fragment stage's last step on the shaded path: the surface is not lit and not fogged but
     * written to near terrain's four targets, by its contract (docs/ARCHITECTURE.md, 6.6): A the
     * world normal and roughness; B sky light, emission, no block light, and the class of terrain;
     * C the square root of linear albedo and whether it is a conductor; D reflectance and subsurface,
     * or a metal's colored reflectance, and occlusion. Roughness and the rest come from near
     * terrain's material table by the state's number.
     */
    private static final String TARGETS_OUTPUT = String.join("\n",
        "layout(std430, binding = " + SHADED_BINDING + ") readonly buffer Shaded { uvec2 shaded[]; };",
        "layout(location = 0) out vec4 targetA;",
        "layout(location = 1) out vec4 targetB;",
        "layout(location = 2) out vec4 targetC;",
        "layout(location = 3) out vec4 targetD;",
        "const vec3 NORMAL[6] = vec3[6](vec3(0.0, 1.0, 0.0), vec3(0.0, -1.0, 0.0), vec3(0.0, 0.0, -1.0),",
        "    vec3(0.0, 0.0, 1.0), vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0));",
        "vec3 linear(vec3 stored) {",
        "    return mix(stored / 12.92, pow((stored + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), stored));",
        "}",
        KindEmission.GLSL,
        "void finish(vec3 albedo, float fogDistance) {",
        "    uvec2 material = shaded[faceState];",
        "    float roughness = float(material.x & 255u) / 255.0;",
        "    float reflectance = float((material.x >> 8) & 255u) / 255.0;",
        "    float subsurface = float((material.x >> 16) & 255u) / 255.0;",
        "    bool conductor = (material.y & 1u) != 0u;",
        "    vec3 light = linear(albedo);",
        "    float occlusion = faceOcclusion;",
        "    // A crown is drawn solid in the far color, which is its leaves' color darkened by their",
        "    // gaps. For shading the leaves' own color is wanted and the gaps as occlusion: the far",
        "    // color in light over the share of the image that is leaf, and occlusion from 0.8.",
        "    if (" + (DistantSettings.SHADED_LEAVES_PLAIN ? "true" : "false") + " && (material.y & 2u) != 0u && imaged == 1) {",
        "        light = min(light / max(faceCover, 0.25), vec3(1.0));",
        "        occlusion *= 0.8;",
        "    } else if ((material.y & 2u) != 0u && imaged == 1) {",
        "        // In the far color: leaves close each other in, which the game's occlusion shows on",
        "        // near crowns and this mesher's does not, since it counts opaque cells only. Their",
        "        // mean over near crowns' tops is written, so a crown is as bright inside the join as",
        "        // outside it.",
        "        occlusion *= " + LEAF_OCCLUSION + ";",
        "    }",
        "    targetA = vec4(NORMAL[faceDirection] * 0.5 + 0.5, roughness);",
        "    // Emission by near terrain's rule, in its own text: from the surface's kind, byte 5 of its",
        "    // row, and the linear albedo written to C. The row's byte 3 is not read.",
        "    float emission = rivenKindEmission((material.y >> 8) & 255u, light);",
        "    targetB = vec4(faceSky, emission, 0.0, 0.375);",
        "    targetC = vec4(sqrt(light), conductor ? 1.0 : 0.0);",
        "    targetD = conductor ? vec4(light, occlusion) : vec4(reflectance, subsurface, 0.0, occlusion);",
        "}",
        "void finishFlat(vec3 color) {",
        "    // The box view's second draw has no place in the targets.",
        "    discard;",
        "}");

    private static final String FRAGMENT = String.join("\n",
        "#version 460 compatibility",
        "uniform vec3 nearMinimum;",
        "uniform vec3 nearMaximum;",
        "uniform int fogMode;",
        "uniform int overlay;",
        "uniform sampler2DArray images;",
        "uniform usampler2D cover;",
        "// Level k's hole, from the view's origin: its lowest x and z, its highest x and z.",
        "uniform vec4 holes[8];",
        "// The band inside level k's hole over which it fades into the level below: one of its tiles.",
        "uniform float bandWidth[8];",
        "uniform int levelCount;",
        "uniform int crossfade;",
        "uniform int coverSide;",
        "uniform vec2 coverMinimum;",
        "uniform float detailStart;",
        "uniform float detailEnd;",
        "in vec3 fromOrigin;",
        "in vec3 fromEye;",
        "in vec3 faceAlbedo;",
        "in vec3 faceTint;",
        "in vec2 faceTexel;",
        "flat in float faceLayer;",
        "in float faceBelow;",
        "in float faceLight;",
        "in float faceCover;",
        "flat in vec2 faceInto;",
        "flat in int faceLevel;",
        "flat in float faceShows;",
        "flat in int facePartners;",
        "flat in int faceDirection;",
        "flat in uint faceState;",
        "flat in float faceSky;",
        "in float faceOcclusion;",
        "// One bit for each tile of each level's square: whether it is shown. A level's bits start at",
        "// level * maskWords; a tile's bit is its place in the square, x fastest.",
        "uniform uint shownMask[" + MASK_UINTS + "];",
        "uniform int maskWords;",
        "uniform int acrossTiles;",
        "// Where each level's square begins, from the view's origin.",
        "uniform vec2 squareOrigin[8];",
        "// The join's fade: where it starts and ends, in blocks from the view along the farther of x",
        "// and z. End not past start: none.",
        "uniform vec2 joinBand;",
        "// Where that distance is measured from, x and z from the view's origin: near terrain's camera.",
        "uniform vec2 joinOrigin;",
        "// The frame the fades between levels and of arriving tiles take their pattern from.",
        "uniform int fadeFrame;",
        "// 0 while the water layer is drawn.",
        "uniform int imaged;",
        "// The debug view, as the vertex stage's: 3 shows the pixels distant terrain keeps in the join.",
        "uniform int debugMode;",
        FadePattern.glsl(),
        "// How far inside a hole a place is, in blocks: 0 on its edge, below 0 outside it.",
        "float within(vec4 hole, vec2 place) {",
        "    return min(min(place.x - hole.x, hole.z - place.x), min(place.y - hole.y, hole.w - place.y));",
        "}",
        FORWARD_OUTPUT,
        "// Whether a level has a shown tile over a place.",
        "bool shownAt(int level, vec2 place) {",
        "    ivec2 tile = ivec2(floor((place - squareOrigin[level]) / float(64 << level)));",
        "    if (any(lessThan(tile, ivec2(0))) || any(greaterThanEqual(tile, ivec2(acrossTiles)))) {",
        "        return false;",
        "    }",
        "    int bit = tile.y * acrossTiles + tile.x;",
        "    return ((shownMask[level * maskWords + (bit >> 5)] >> uint(bit & 31)) & 1u) != 0u;",
        "}",
        "void main() {",
        "    // What near terrain draws is left to it. With its coverage map: a chunk column whose surface",
        "    // it has meshed, over the heights its cubes span. Without: the box of cubes it meshes.",
        "    bool inside;",
        "    if (coverSide > 0) {",
        "        inside = false;",
        "        if (fromOrigin.y >= nearMinimum.y && fromOrigin.y < nearMaximum.y) {",
        "            ivec2 column = ivec2(floor((fromOrigin.xz + faceInto - coverMinimum) / 16.0));",
        "            if (all(greaterThanEqual(column, ivec2(0))) && all(lessThan(column, ivec2(coverSide)))) {",
        "                inside = texelFetch(cover, column, 0).r == 3u;",
        "            }",
        "        }",
        "    } else {",
        "        inside = all(greaterThanEqual(fromOrigin, nearMinimum)) && all(lessThan(fromOrigin, nearMaximum));",
        "    }",
        "    if (overlay == 1) {",
        "        // The box view's second draw: only what the box holds; near terrain hides it by depth.",
        "        if (!inside) {",
        "            discard;",
        "        }",
        "        finishFlat(vec3(1.0, 0.0, 1.0));",
        "        return;",
        "    }",
        "    if (inside) {",
        "        // With the join's fade, near terrain gives pixels up across its last blocks and distant",
        "        // terrain takes exactly those: the same distance, the same band, the pattern where",
        "        // near terrain keeps its complement.",
        "        // Not water: near water is the game's own and does not fade, so distant water ends",
        "        // where near terrain's columns begin and every pixel has one water surface.",
        "        float square = max(abs(fromOrigin.x - joinOrigin.x), abs(fromOrigin.z - joinOrigin.y));",
        "        if (imaged == 0 || joinBand.y <= joinBand.x || square <= joinBand.x || !" + FadePattern.GLSL_FUNCTION
            + "(gl_FragCoord.xy, clamp((square - joinBand.x) / (joinBand.y - joinBand.x), 0.0, 1.0), false, 0)) {",
        "            discard;",
        "        }",
        "        if (debugMode == 3) {",
        "            // The join view: a pixel distant terrain keeps over near terrain's columns, in orange.",
        "            finishFlat(vec3(1.0, 0.45, 0.0));",
        "            return;",
        "        }",
        "    }",
        "    if (faceShows >= 0.0) {",
        "        // A tile that has just arrived comes in over a few frames.",
        "        if (faceShows < 1.0 && !" + FadePattern.GLSL_FUNCTION + "(gl_FragCoord.xy, faceShows, false, fadeFrame)) {",
        "            discard;",
        "        }",
        "        // The finest shown tile over a place draws it. So a coarser tile, which may be a fill",
        "        // tile standing in for levels that have not arrived, gives a place up as soon as any",
        "        // finer level shows a tile there, and only there.",
        "        vec2 here = fromOrigin.xz + faceInto;",
        "        for (int finer = faceLevel - 1; finer >= 0; finer--) {",
        "            if (shownAt(finer, here)) {",
        "                // But for a band: just inside its own hole a level fades into the next finer",
        "                // one, showing more toward the hole's edge.",
        "                if (crossfade == 1 && finer == faceLevel - 1) {",
        "                    float depth = within(holes[faceLevel], here);",
        "                    if (depth > 0.0 && " + FadePattern.GLSL_FUNCTION
            + "(gl_FragCoord.xy, 1.0 - depth / bandWidth[faceLevel], false, fadeFrame)) {",
        "                        break;",
        "                    }",
        "                }",
        "                discard;",
        "            }",
        "        }",
        "        // As the finer of a band: within the band inside the next level's hole, which is its own",
        "        // ring's edge, and only where the coarser level shows a tile to take the pixels.",
        "        if (crossfade == 1 && faceLevel + 1 < levelCount && shownAt(faceLevel + 1, here)) {",
        "            float depth = within(holes[faceLevel + 1], here);",
        "            if (depth < bandWidth[faceLevel + 1] && !" + FadePattern.GLSL_FUNCTION
            + "(gl_FragCoord.xy, 1.0 - depth / bandWidth[faceLevel + 1], true, fadeFrame)) {",
        "                discard;",
        "            }",
        "        }",
        "    } else {",
        "        // A tile kept past its ring, after the camera has moved: it draws a place only where no",
        "        // wanted tile of any level is shown, and so gives way as what replaces it is stored.",
        "        vec2 here = fromOrigin.xz + faceInto;",
        "        for (int level = 0; level < levelCount; level++) {",
        "            if (shownAt(level, here)) {",
        "                discard;",
        "            }",
        "        }",
        "    }",
        "    float fogDistance = length(fromEye);",
        "    // The surface: its image near the join, fading to its flat color, which is the image's mean.",
        "    vec3 albedo = faceAlbedo;",
        "    // A side face carries the material at its top, whatever it crosses below: only its top",
        "    // block shows that image, or a cliff would repeat its turf all the way down.",
        "    if (faceLayer >= 0.0 && fogDistance < detailEnd && faceBelow <= 1.0) {",
        "        float shown = 1.0 - smoothstep(detailStart, detailEnd, fogDistance);",
        "        albedo = mix(faceAlbedo, texture(images, vec3(faceTexel, faceLayer)).rgb * faceTint, shown);",
        "    }",
        "    // The last step, which is the forward path's or another's: see FORWARD_OUTPUT.",
        "    finish(albedo, fogDistance);",
        "}");

    /** The fragment stage that writes the targets: the forward one with its last step exchanged. */
    private static final String FRAGMENT_TARGETS = FRAGMENT.replace(FORWARD_OUTPUT, TARGETS_OUTPUT);

    /** The texture unit the materials' images are bound to: above any the game or near terrain uses. */
    static final int IMAGES_UNIT = 7;
    /** The unit for near terrain's coverage map. */
    static final int COVER_UNIT = 6;

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
    private int imageLayersLocation;
    private int imagedLocation;
    private int imagesLocation;
    private int detailStartLocation;
    private int detailEndLocation;
    private int coverLocation;
    private int coverSideLocation;
    private int coverMinimumLocation;
    private int holesLocation;
    private int bandWidthLocation;
    private int levelCountLocation;
    private int crossfadeLocation;
    private int shownMaskLocation;
    private int maskWordsLocation;
    private int acrossTilesLocation;
    private int squareOriginLocation;
    private int joinBandLocation;
    private int joinOriginLocation;
    private int targetsProgram;
    /**
     * The frame the fades between levels and of arriving tiles take their pattern from: 0 on the
     * forward path, near terrain's count of world passes on the shaded path, where a later stage
     * accumulates over frames. The near join's band takes 0 on both, as near terrain's half does.
     */
    private int fadeFrame;
    private int fadeFrameLocation;
    /** Whether the draw under way writes the targets for shading and not the frame. */
    private boolean targets;
    private int vertexArray;
    private int commandBuffer;
    private int recordBuffer;
    private final float[] viewProjection = new float[16];

    /** The forward draw's layers: all three in order, the opaque two, or the water alone. */
    static final int WATER_WITH = 0;
    static final int WATER_LEFT = 1;
    static final int WATER_ALONE = 2;

    private int water = WATER_WITH;

    /**
     * Which layers the next forward draws hold. With the shaded path on, the opaque layers are
     * drawn first and the water in a draw of its own after the targets are written, without
     * writing depth; otherwise all three in one draw, as ever.
     */
    void water(int mode) {
        water = mode;
    }

    /**
     * Draws a frame's list: its three layers in turn, each with one indirect draw for each page that
     * has commands in it.
     *
     * @param modelView  the view's matrices, column-major, as the driver holds them
     * @param nearBox    the box of loaded cubes, less the view's origin: minimum then maximum
     * @param daylight   how bright the sky is, 0 to 1
     * @param cover      near terrain's coverage map, whose side is 0 when the box is to be used
     * @param coverX     the map's lowest x and z, less the view's origin
     * @param fade       where level 0's images begin to fade and where they are gone, from the eye
     * @param holes      for each level, its hole from the view's origin, four floats; null for no crossfade
     * @param bandWidths for each level, the width of the band inside its hole
     * @return how many draws were issued
     */
    /**
     * Draws the opaque layers again into near terrain's targets for shading, which must be bound
     * with their viewport: the same faces by the same rules, with the last step writing the targets.
     * Not the water, and not a debug view's second draw.
     *
     * @param table near terrain's material table: the name of its storage buffer, eight bytes a state
     * @param pass  near terrain's count of world passes, which moves the fades' pattern from frame to frame
     */
    int drawTargets(TileDraws list, DistantArena arena, DistantMaterials materials, float[] modelView,
        float[] projection, float[] nearBox, float daylight, DistantCover cover, float coverX, float coverZ,
        float[] fade, float[] holes, float[] bandWidths, int levels, int[] shownMasks, float[] squareOrigins,
        int across, float[] joinBand, int table, int pass) {
        if (program == 0) {
            create();
        }
        int[] forward = locations();
        int forwardProgram = program;
        locate(targetsProgram);
        program = targetsProgram;
        targets = true;
        fadeFrame = pass;
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SHADED_BINDING, table);
        try {
            return draw(list, arena, materials, modelView, projection, nearBox, daylight, cover, coverX, coverZ, fade,
                holes, bandWidths, levels, shownMasks, squareOrigins, across, joinBand);
        } finally {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SHADED_BINDING, 0);
            targets = false;
            fadeFrame = 0;
            program = forwardProgram;
            locations(forward);
        }
    }

    int draw(TileDraws list, DistantArena arena, DistantMaterials materials, float[] modelView, float[] projection,
        float[] nearBox, float daylight, DistantCover cover, float coverX, float coverZ, float[] fade,
        float[] holes, float[] bandWidths, int levels, int[] shownMasks, float[] squareOrigins, int across, float[] joinBand) {
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
        GL20.glUniform1i(imageLayersLocation, DistantSettings.DETAIL ? materials.imageLayers() : 0);
        GL20.glUniform1i(imagesLocation, IMAGES_UNIT);
        GL20.glUniform1f(detailStartLocation, fade[0]);
        GL20.glUniform1f(detailEndLocation, fade[1]);
        GL20.glUniform1i(coverLocation, COVER_UNIT);
        GL20.glUniform1i(coverSideLocation, cover.side());
        GL20.glUniform2f(coverMinimumLocation, coverX, coverZ);
        GL20.glUniform1i(crossfadeLocation, holes == null ? 0 : 1);
        GL20.glUniform1i(levelCountLocation, levels);
        GL20.glUniform1i(fadeFrameLocation, fadeFrame);
        GL30.glUniform1uiv(shownMaskLocation, shownMasks);
        GL20.glUniform1i(maskWordsLocation, cc.orthrus.quadrature.core.lod.Cover.maskWords(across));
        GL20.glUniform1i(acrossTilesLocation, across);
        GL20.glUniform2fv(squareOriginLocation, squareOrigins);
        GL20.glUniform2f(joinBandLocation, joinBand == null ? 0f : joinBand[0], joinBand == null ? 0f : joinBand[1]);
        GL20.glUniform2f(joinOriginLocation, joinBand == null ? 0f : joinBand[2], joinBand == null ? 0f : joinBand[3]);
        if (holes != null) {
            GL20.glUniform4fv(holesLocation, holes);
            GL20.glUniform1fv(bandWidthLocation, bandWidths);
        }
        if (cover.side() > 0) {
            GL45.glBindTextureUnit(COVER_UNIT, cover.texture());
        }
        // Bound to a unit of its own and left there: no texture unit the game draws with is touched.
        GL45.glBindTextureUnit(IMAGES_UNIT, materials.images());
        GL30.glBindVertexArray(vertexArray);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, TILES_BINDING, recordBuffer);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, MATERIALS_BINDING, materials.materialBuffer());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BIOMES_BINDING, materials.biomeBuffer());
        GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        GL20.glUniform1i(overlayLocation, 0);
        boolean waterAlone = !targets && water == WATER_ALONE;
        int draws;
        if (waterAlone) {
            // Over the opaque layers already drawn, and out of the depth: what is under water
            // stays the bed for whatever reads the depth afterwards.
            boolean wrote = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            GL11.glDepthMask(false);
            draws = layers(list, arena, false, true);
            GL11.glDepthMask(wrote);
        } else {
            draws = layers(list, arena, true, !targets && water == WATER_WITH);
        }
        if (DistantSettings.DEBUG == DistantSettings.DEBUG_BOX && !targets && !waterAlone) {
            // The box view: the faces the box holds, in magenta, a little deeper than they lie.
            GL20.glUniform1i(overlayLocation, 1);
            GL20.glUniformMatrix4fv(projectionLocation, false, projection);
            // Not the water: its faces lie over the sea bed, and near water, which writes no depth,
            // cannot hide them. Without them, magenta seen through near water is a missing sea bed.
            draws += layers(list, arena, true, false);
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
    private int layers(TileDraws list, DistantArena arena, boolean opaque, boolean water) {
        // The opaque layers are drawn unblended whatever the game left set. Water is drawn last, over
        // them, blended by its image's alpha as the game blends near water over its bed. The blend
        // state is put back as it was found.
        boolean wasBlending = GL11.glIsEnabled(GL11.GL_BLEND);
        if (wasBlending) {
            GL11.glDisable(GL11.GL_BLEND);
        }
        int draws = 0;
        for (int layer = 0; layer < TileDraws.LAYERS; layer++) {
            if (layer == TileDraws.WATER ? !water : !opaque) {
                continue;
            }
            boolean blended = layer == TileDraws.WATER;
            int sourceColor = 0;
            int targetColor = 0;
            int sourceAlpha = 0;
            int targetAlpha = 0;
            if (blended) {
                sourceColor = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
                targetColor = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
                sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
                targetAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
                GL11.glEnable(GL11.GL_BLEND);
                GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA);
            }
            for (int page = 0; page < list.pages(); page++) {
                int count = list.runCount(layer, page);
                if (count == 0) {
                    continue;
                }
                GL20.glUniform1f(topDropLocation, NearCover.topDrop(layer));
                GL20.glUniform1i(imagedLocation, layer == TileDraws.WATER ? 0 : 1);
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, PAGE_BINDING, arena.buffer(page));
                GL43.glMultiDrawArraysIndirect(GL11.GL_TRIANGLES, list.runOffset(layer, page), count,
                    TileDraws.COMMAND_BYTES);
                draws++;
            }
            if (blended) {
                GL14.glBlendFuncSeparate(sourceColor, targetColor, sourceAlpha, targetAlpha);
                GL11.glDisable(GL11.GL_BLEND);
            }
        }
        if (wasBlending) {
            GL11.glEnable(GL11.GL_BLEND);
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
        GL20.glDeleteShader(fragment);
        int targetsFragment = compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT_TARGETS);
        targetsProgram = GL20.glCreateProgram();
        GL20.glAttachShader(targetsProgram, vertex);
        GL20.glAttachShader(targetsProgram, targetsFragment);
        GL20.glLinkProgram(targetsProgram);
        if (GL20.glGetProgrami(targetsProgram, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Distant terrain targets program: " + GL20.glGetProgramInfoLog(targetsProgram));
        }
        GL20.glDeleteShader(targetsFragment);
        GL20.glDeleteShader(vertex);
        locate(program);
        // No vertex attributes: every vertex comes from the page.
        vertexArray = GL45.glCreateVertexArrays();
        commandBuffer = GL45.glCreateBuffers();
        recordBuffer = GL45.glCreateBuffers();
    }

    /** Every uniform's place in a program: the two programs name the same uniforms. */
    private void locate(int program) {
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
        imageLayersLocation = GL20.glGetUniformLocation(program, "imageLayers");
        imagedLocation = GL20.glGetUniformLocation(program, "imaged");
        imagesLocation = GL20.glGetUniformLocation(program, "images");
        detailStartLocation = GL20.glGetUniformLocation(program, "detailStart");
        detailEndLocation = GL20.glGetUniformLocation(program, "detailEnd");
        coverLocation = GL20.glGetUniformLocation(program, "cover");
        coverSideLocation = GL20.glGetUniformLocation(program, "coverSide");
        coverMinimumLocation = GL20.glGetUniformLocation(program, "coverMinimum");
        holesLocation = GL20.glGetUniformLocation(program, "holes");
        bandWidthLocation = GL20.glGetUniformLocation(program, "bandWidth");
        levelCountLocation = GL20.glGetUniformLocation(program, "levelCount");
        crossfadeLocation = GL20.glGetUniformLocation(program, "crossfade");
        shownMaskLocation = GL20.glGetUniformLocation(program, "shownMask");
        maskWordsLocation = GL20.glGetUniformLocation(program, "maskWords");
        acrossTilesLocation = GL20.glGetUniformLocation(program, "acrossTiles");
        squareOriginLocation = GL20.glGetUniformLocation(program, "squareOrigin");
        joinBandLocation = GL20.glGetUniformLocation(program, "joinBand");
        joinOriginLocation = GL20.glGetUniformLocation(program, "joinOrigin");
        fadeFrameLocation = GL20.glGetUniformLocation(program, "fadeFrame");
    }

    /** The uniforms' places as they stand, to put back after the other program has drawn. */
    private int[] locations() {
        return new int[] {viewProjectionLocation, daylightLocation, nearMinimumLocation, nearMaximumLocation,
            modelViewLocation, topDropLocation, fogModeLocation, debugModeLocation, overlayLocation, projectionLocation,
            imageLayersLocation, imagedLocation, imagesLocation, detailStartLocation, detailEndLocation, coverLocation,
            coverSideLocation, coverMinimumLocation, holesLocation, bandWidthLocation, levelCountLocation,
            crossfadeLocation, shownMaskLocation, maskWordsLocation, acrossTilesLocation, squareOriginLocation,
            joinBandLocation, joinOriginLocation, fadeFrameLocation};
    }

    private void locations(int[] kept) {
        int i = 0;
        viewProjectionLocation = kept[i++];
        daylightLocation = kept[i++];
        nearMinimumLocation = kept[i++];
        nearMaximumLocation = kept[i++];
        modelViewLocation = kept[i++];
        topDropLocation = kept[i++];
        fogModeLocation = kept[i++];
        debugModeLocation = kept[i++];
        overlayLocation = kept[i++];
        projectionLocation = kept[i++];
        imageLayersLocation = kept[i++];
        imagedLocation = kept[i++];
        imagesLocation = kept[i++];
        detailStartLocation = kept[i++];
        detailEndLocation = kept[i++];
        coverLocation = kept[i++];
        coverSideLocation = kept[i++];
        coverMinimumLocation = kept[i++];
        holesLocation = kept[i++];
        bandWidthLocation = kept[i++];
        levelCountLocation = kept[i++];
        crossfadeLocation = kept[i++];
        shownMaskLocation = kept[i++];
        maskWordsLocation = kept[i++];
        acrossTilesLocation = kept[i++];
        squareOriginLocation = kept[i++];
        joinBandLocation = kept[i++];
        joinOriginLocation = kept[i++];
        fadeFrameLocation = kept[i];
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
