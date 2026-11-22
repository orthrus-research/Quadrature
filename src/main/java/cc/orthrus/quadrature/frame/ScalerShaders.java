package cc.orthrus.quadrature.frame;

/**
 * The programs of the temporal pass and the spatial scaler, as text. Every pass is a compute program:
 * it writes through images, so blending, the alpha test, culling, the viewport, the scissor and the
 * write masks the game and hosted code leave set do not touch it, and nothing of them is read or put
 * back. Uniforms sit at fixed locations and samplers and images at fixed units, so a pass is set up
 * without asking the driver anything.
 *
 * <p>Nothing here touches the graphics context: the class holds strings alone, so the programs can be
 * written out and compiled offline.
 */
final class ScalerShaders {

    private ScalerShaders() {
    }

    /** The units the passes read on: above those fixed-function drawing uses. */
    static final int UNIT_A = 4;
    static final int UNIT_B = 5;
    static final int UNIT_C = 6;
    static final int UNIT_D = 7;
    static final int UNIT_E = 8;
    /** The bloom weights the temporal pass resolves (D27); unit 9 holds the camera's motion under the measure. */
    static final int UNIT_WEIGHTS = 10;

    /** The side of a work group, in pixels. */
    static final int GROUP = 8;

    /**
     * Motion for everything that stands still, from depth: where the point each pixel shows lay in
     * the previous frame, minus where it lies now, in pixels of the drawn rectangle, both without the
     * offset. A pixel whose point stood behind the previous frame's eye gets a motion past any picture,
     * so the temporal pass keeps none of its history.
     *
     * <p>Where entities' motions are given (research R4, 7b.5), a pixel whose depth the entity stage and after
     * changed, and whose point lies in an entity's box, moves with that entity instead: its point, rebuilt from
     * depth, is taken to where the entity's rigid motion had it the frame before, then projected by that frame's
     * view. The first box holding the point decides.
     */
    static final String MOTION_COMPUTE = String.join("\n",
        "#version 460 core",
        "layout(local_size_x = 8, local_size_y = 8) in;",
        "layout(binding = 4) uniform sampler2D depthNow;",
        "layout(rg16f, binding = 0) uniform writeonly image2D motionOut;",
        "layout(location = 0) uniform mat4 toPrevious;",
        "layout(location = 1) uniform vec2 size;",
        "layout(location = 2) uniform vec2 offset;",
        "layout(location = 3) uniform int zeroToOne;",
        "layout(location = 4) uniform int reachable;",
        "layout(location = 5) uniform int entityCount;",
        "layout(location = 6) uniform mat4 currentInverse;",
        "layout(location = 7) uniform mat4 previousViewProjection;",
        "layout(binding = 5) uniform sampler2D depthTerrain;",
        "struct EntityRecord { vec4 low; vec4 high; mat4 toPrevious; };",
        "layout(std430, binding = 3) readonly buffer Entities { EntityRecord entities[]; };",
        "layout(location = 8) uniform int measure;",
        "layout(rg16f, binding = 1) uniform writeonly image2D cameraMotionOut;",
        "void main() {",
        "    ivec2 p = ivec2(gl_GlobalInvocationID.xy);",
        "    if (p.x >= int(size.x) || p.y >= int(size.y)) {",
        "        return;",
        "    }",
        "    if (reachable == 0) {",
        "        imageStore(motionOut, p, vec4(0.0));",
        "        return;",
        "    }",
        "    float depth = texelFetch(depthNow, p, 0).r;",
        "    // The depth at a pixel is that of the point the offset moved under its center.",
        "    vec2 at = vec2(p) + 0.5 - offset;",
        "    vec4 clip = vec4(at / size * 2.0 - 1.0, zeroToOne != 0 ? depth : depth * 2.0 - 1.0, 1.0);",
        "    vec4 before = toPrevious * clip;",
        "    if (measure != 0) {",
        "        // The camera's motion alone, for the temporal pass to weigh against the entities'.",
        "        imageStore(cameraMotionOut, p, vec4(before.w > 1e-6 ? (before.xy / before.w * 0.5 + 0.5) * size - at : vec2(60000.0), 0.0, 0.0));",
        "    }",
        "    if (entityCount > 0 && depth != texelFetch(depthTerrain, p, 0).r) {",
        "        vec4 w = currentInverse * clip;",
        "        vec3 q = w.xyz / w.w;",
        "        for (int i = 0; i < entityCount; i++) {",
        "            if (all(greaterThanEqual(q, entities[i].low.xyz)) && all(lessThanEqual(q, entities[i].high.xyz))) {",
        "                before = previousViewProjection * (entities[i].toPrevious * vec4(q, 1.0));",
        "                break;",
        "            }",
        "        }",
        "    }",
        "    vec2 motion = before.w > 1e-6 ? (before.xy / before.w * 0.5 + 0.5) * size - at : vec2(60000.0);",
        "    imageStore(motionOut, p, vec4(motion, 0.0, 0.0));",
        "}");

    /**
     * The temporal pass, at the drawn rectangle's size. This frame's color is filtered at each pixel's
     * center from its 3 by 3 neighborhood by how far each sample's offset put it; the history is read
     * where the nearest point of the neighborhood stood in the previous frame, with a Catmull-Rom spline
     * of nine bilinear reads, and drawn into the box of the neighborhood's minimum and maximum in YCoCg,
     * toward its center;
     * the two are blended with this frame's weight at least {@code least}, more under motion and where
     * something was drawn after opaque terrain. Both are weighted by the inverse of their luma, so a
     * bright sample that comes and goes does not flicker. History holds the result, not YCoCg.
     *
     * <p>Its alpha holds the bloom weight over black (D27): where the emissive stage drew weights this frame, they are
     * filtered and blended as the color is, the history's held to the neighborhood's least and greatest, so the glow
     * taken from the result does not pulse with the offset; else 0.
     */
    static final String TEMPORAL_COMPUTE = String.join("\n",
        "#version 460 core",
        "layout(local_size_x = 8, local_size_y = 8) in;",
        "layout(binding = 4) uniform sampler2D colorNow;",
        "layout(binding = 5) uniform sampler2D history;",
        "layout(binding = 6) uniform sampler2D motion;",
        "layout(binding = 7) uniform sampler2D depthNow;",
        "layout(binding = 8) uniform sampler2D depthTerrain;",
        "layout(rgba16f, binding = 0) uniform writeonly image2D historyOut;",
        "layout(location = 1) uniform vec2 size;",
        "layout(location = 2) uniform vec2 offset;",
        "layout(location = 3) uniform int nearerIsGreater;",
        "layout(location = 4) uniform int kept;",
        "layout(location = 5) uniform float least;",
        "layout(location = 6) uniform float reactive;",
        "layout(location = 7) uniform int masked;",
        "layout(location = 8) uniform float moving;",
        "layout(location = 9) uniform int measure;",
        "layout(binding = 9) uniform sampler2D cameraMotion;",
        "layout(binding = 10) uniform sampler2D weightNow;",
        "layout(location = 10) uniform int weighted;",
        "layout(std430, binding = 4) buffer MotionError { uint entityError; uint cameraError; uint measured; };",
        "vec3 toYCoCg(vec3 c) {",
        "    return vec3(dot(c, vec3(0.25, 0.5, 0.25)), dot(c, vec3(0.5, 0.0, -0.5)), dot(c, vec3(-0.25, 0.5, -0.25)));",
        "}",
        "vec3 fromYCoCg(vec3 c) {",
        "    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);",
        "}",
        "// The history at a place, by a Catmull-Rom spline through the 4 by 4 texels around it: along each",
        "// axis the two middle weights are one bilinear read between their texels, so nine reads in all.",
        "vec4 historyAt(vec2 uv) {",
        "    vec2 texel = uv * size - 0.5;",
        "    vec2 base = floor(texel);",
        "    vec2 f = texel - base;",
        "    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));",
        "    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);",
        "    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));",
        "    vec2 w3 = f * f * (-0.5 + 0.5 * f);",
        "    vec2 w12 = w1 + w2;",
        "    vec2 t0 = (base - 0.5) / size;",
        "    vec2 t12 = (base + 0.5 + w2 / w12) / size;",
        "    vec2 t3 = (base + 2.5) / size;",
        "    vec4 sum = texture(history, vec2(t0.x, t0.y)) * (w0.x * w0.y)",
        "        + texture(history, vec2(t12.x, t0.y)) * (w12.x * w0.y)",
        "        + texture(history, vec2(t3.x, t0.y)) * (w3.x * w0.y)",
        "        + texture(history, vec2(t0.x, t12.y)) * (w0.x * w12.y)",
        "        + texture(history, vec2(t12.x, t12.y)) * (w12.x * w12.y)",
        "        + texture(history, vec2(t3.x, t12.y)) * (w3.x * w12.y)",
        "        + texture(history, vec2(t0.x, t3.y)) * (w0.x * w3.y)",
        "        + texture(history, vec2(t12.x, t3.y)) * (w12.x * w3.y)",
        "        + texture(history, vec2(t3.x, t3.y)) * (w3.x * w3.y);",
        "    return max(sum, vec4(0.0));",
        "}",
        "// Moves a color along the line to the box's center until it lies in the box.",
        "vec3 intoBox(vec3 color, vec3 low, vec3 high) {",
        "    vec3 center = 0.5 * (low + high);",
        "    vec3 extent = 0.5 * (high - low) + 1e-4;",
        "    vec3 toward = color - center;",
        "    vec3 units = abs(toward / extent);",
        "    float most = max(units.x, max(units.y, units.z));",
        "    return most > 1.0 ? center + toward / most : color;",
        "}",
        "void main() {",
        "    ivec2 p = ivec2(gl_GlobalInvocationID.xy);",
        "    if (p.x >= int(size.x) || p.y >= int(size.y)) {",
        "        return;",
        "    }",
        "    ivec2 last = ivec2(size) - 1;",
        "    vec3 low = vec3(1e9);",
        "    vec3 high = vec3(-1e9);",
        "    vec3 filtered = vec3(0.0);",
        "    float weights = 0.0;",
        "    float lowWeight = 1.0;",
        "    float highWeight = 0.0;",
        "    float filteredWeight = 0.0;",
        "    float nearest = nearerIsGreater != 0 ? -1.0 : 2.0;",
        "    ivec2 nearestAt = p;",
        "    for (int y = -1; y <= 1; y++) {",
        "        for (int x = -1; x <= 1; x++) {",
        "            ivec2 q = clamp(p + ivec2(x, y), ivec2(0), last);",
        "            vec3 c = toYCoCg(texelFetch(colorNow, q, 0).rgb);",
        "            low = min(low, c);",
        "            high = max(high, c);",
        "            // The sample at q stands at q's center less the offset, so this far from p's center.",
        "            vec2 d = vec2(x, y) - offset;",
        "            float w = exp(-2.0 * dot(d, d));",
        "            filtered += c * w;",
        "            weights += w;",
        "            float bloomWeight = weighted != 0 ? texelFetch(weightNow, q, 0).r : 0.0;",
        "            lowWeight = min(lowWeight, bloomWeight);",
        "            highWeight = max(highWeight, bloomWeight);",
        "            filteredWeight += bloomWeight * w;",
        "            float z = texelFetch(depthNow, q, 0).r;",
        "            if (nearerIsGreater != 0 ? z > nearest : z < nearest) {",
        "                nearest = z;",
        "                nearestAt = q;",
        "            }",
        "        }",
        "    }",
        "    vec3 now = filtered / weights;",
        "    vec3 result = now;",
        "    float nowWeight = filteredWeight / weights;",
        "    float resultWeight = nowWeight;",
        "    vec2 m = texelFetch(motion, nearestAt, 0).rg;",
        "    vec2 uv = (vec2(p) + 0.5 + m) / size;",
        "    bool inside = uv.x >= 0.0 && uv.y >= 0.0 && uv.x <= 1.0 && uv.y <= 1.0;",
        "    if (kept != 0 && inside) {",
        "        if (measure != 0) {",
        "            // Where an entity's own motion moved the pixel, the history it reads against the history the",
        "            // camera's motion would have read, each as far in luma from this frame's value: the truer motion",
        "            // finds the nearer history. Neither is clipped, so the box does not hide the difference.",
        "            vec2 c = texelFetch(cameraMotion, nearestAt, 0).rg;",
        "            vec2 uvCamera = (vec2(p) + 0.5 + c) / size;",
        "            if (length(m - c) > 0.01 && all(greaterThanEqual(uvCamera, vec2(0.0))) && all(lessThanEqual(uvCamera, vec2(1.0)))) {",
        "                float y = now.x;",
        "                atomicAdd(entityError, uint(abs(toYCoCg(historyAt(uv).rgb).x - y) * 255.0 + 0.5));",
        "                atomicAdd(cameraError, uint(abs(toYCoCg(historyAt(uvCamera).rgb).x - y) * 255.0 + 0.5));",
        "                atomicAdd(measured, 1u);",
        "            }",
        "        }",
        "        vec4 read = historyAt(uv);",
        "        vec3 before = intoBox(toYCoCg(read.rgb), low, high);",
        "        float beforeWeight = clamp(read.a, lowWeight, highWeight);",
        "        // Resampled every frame under motion the history softens: this frame weighs more as it moves.",
        "        float alpha = mix(least, max(least, 1.0 / 3.0), clamp(length(m) * moving, 0.0, 1.0));",
        "        if (masked != 0 && texelFetch(depthNow, p, 0).r != texelFetch(depthTerrain, p, 0).r) {",
        "            alpha = max(alpha, reactive);",
        "        }",
        "        float wNow = alpha / (1.0 + now.x);",
        "        float wBefore = (1.0 - alpha) / (1.0 + before.x);",
        "        result = (now * wNow + before * wBefore) / (wNow + wBefore);",
        "        resultWeight = (nowWeight * wNow + beforeWeight * wBefore) / (wNow + wBefore);",
        "    }",
        "    imageStore(historyOut, p, vec4(max(fromYCoCg(result), vec3(0.0)), resultWeight));",
        "}");

    /**
     * The spatial scaler, at the output's size. Each output pixel is made from the 4 by 4 source
     * texels around where its center falls, under Keys' cubic apart along x and y. A small boost of
     * the difference from a bilinear read sharpens, and the result is held inside the four central
     * texels' range, which keeps the cubic's negative lobe from ringing.
     *
     * <p>A work group's 8 by 8 output pixels reach at most 11 source texels each way when the output is
     * larger than the source; the group reads them once into shared memory, and every pixel takes its
     * 16 taps from there. The kernel's weights are four along each axis.
     */
    static final String SCALE_COMPUTE = String.join("\n",
        "#version 460 core",
        "#define TILE 12",
        "layout(local_size_x = 8, local_size_y = 8) in;",
        "layout(binding = 4) uniform sampler2D source;",
        "layout(rgba8, binding = 0) uniform writeonly image2D picture;",
        "layout(location = 1) uniform vec2 sourceSize;",
        "layout(location = 2) uniform vec2 outputSize;",
        "layout(location = 3) uniform float sharpness;",
        "// The source texels the group's pixels reach, clamped to the source.",
        "shared vec3 tile[TILE * TILE];",
        "// Keys' cubic convolution kernel, a = -1/2.",
        "float keys(float x) {",
        "    x = abs(x);",
        "    return x < 1.0 ? (1.5 * x - 2.5) * x * x + 1.0 : x < 2.0 ? ((-0.5 * x + 2.5) * x - 4.0) * x + 2.0 : 0.0;",
        "}",
        "void main() {",
        "    vec2 ratio = sourceSize / outputSize;",
        "    ivec2 group = ivec2(gl_WorkGroupID.xy) * 8;",
        "    // The first pixel's lowest tap: one before the texel its center falls past.",
        "    ivec2 first = ivec2(floor((vec2(group) + 0.5) * ratio - 0.5)) - 1;",
        "    ivec2 last = ivec2(sourceSize) - 1;",
        "    for (int i = int(gl_LocalInvocationIndex); i < TILE * TILE; i += 64) {",
        "        tile[i] = clamp(texelFetch(source, clamp(first + ivec2(i % TILE, i / TILE), ivec2(0), last), 0).rgb, 0.0, 1.0);",
        "    }",
        "    barrier();",
        "    ivec2 q = group + ivec2(gl_LocalInvocationID.xy);",
        "    if (q.x >= int(outputSize.x) || q.y >= int(outputSize.y)) {",
        "        return;",
        "    }",
        "    vec2 s = (vec2(q) + 0.5) * ratio - 0.5;",
        "    vec2 base = floor(s);",
        "    vec2 f = s - base;",
        "    // This pixel's 4 by 4 taps start here in the tile.",
        "    ivec2 at = ivec2(base) - 1 - first;",
        "    vec3 c[16];",
        "    for (int y = 0; y < 4; y++) {",
        "        for (int x = 0; x < 4; x++) {",
        "            c[y * 4 + x] = tile[(at.y + y) * TILE + at.x + x];",
        "        }",
        "    }",
        "    vec4 wx = vec4(keys(-1.0 - f.x), keys(-f.x), keys(1.0 - f.x), keys(2.0 - f.x));",
        "    vec4 wy = vec4(keys(-1.0 - f.y), keys(-f.y), keys(1.0 - f.y), keys(2.0 - f.y));",
        "    vec3 square = vec3(0.0);",
        "    for (int y = 0; y < 4; y++) {",
        "        square += wy[y] * (wx.x * c[y * 4] + wx.y * c[y * 4 + 1] + wx.z * c[y * 4 + 2] + wx.w * c[y * 4 + 3]);",
        "    }",
        "    vec3 result = square / (dot(wx, vec4(1.0)) * dot(wy, vec4(1.0)));",
        "    vec3 low = min(min(c[5], c[6]), min(c[9], c[10]));",
        "    vec3 high = max(max(c[5], c[6]), max(c[9], c[10]));",
        "    vec3 bilinear = mix(mix(c[5], c[6], f.x), mix(c[9], c[10], f.x), f.y);",
        "    result = clamp(result + sharpness * (result - bilinear), low, high);",
        "    imageStore(picture, q, vec4(result, 1.0));",
        "}");

    /** At the output's own size: the temporal pass's result written into the game's picture as it is. */
    static final String COPY_COMPUTE = String.join("\n",
        "#version 460 core",
        "layout(local_size_x = 8, local_size_y = 8) in;",
        "layout(binding = 4) uniform sampler2D source;",
        "layout(rgba8, binding = 0) uniform writeonly image2D picture;",
        "layout(location = 1) uniform vec2 size;",
        "void main() {",
        "    ivec2 p = ivec2(gl_GlobalInvocationID.xy);",
        "    if (p.x >= int(size.x) || p.y >= int(size.y)) {",
        "        return;",
        "    }",
        "    imageStore(picture, p, vec4(clamp(texelFetch(source, p, 0).rgb, 0.0, 1.0), 1.0));",
        "}");
}
