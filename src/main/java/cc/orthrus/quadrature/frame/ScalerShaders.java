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

    /** The side of a work group, in pixels. */
    static final int GROUP = 8;

    /**
     * Motion for everything that stands still, from depth: where the point each pixel shows lay in
     * the previous frame, minus where it lies now, in pixels of the drawn rectangle, both without the
     * offset. A pixel whose point stood behind the previous frame's eye gets a motion past any picture,
     * so the temporal pass keeps none of its history.
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
        "vec3 toYCoCg(vec3 c) {",
        "    return vec3(dot(c, vec3(0.25, 0.5, 0.25)), dot(c, vec3(0.5, 0.0, -0.5)), dot(c, vec3(-0.25, 0.5, -0.25)));",
        "}",
        "vec3 fromYCoCg(vec3 c) {",
        "    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);",
        "}",
        "// The history at a place, by a Catmull-Rom spline through the 4 by 4 texels around it: along each",
        "// axis the two middle weights are one bilinear read between their texels, so nine reads in all.",
        "vec3 historyAt(vec2 uv) {",
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
        "    vec3 sum = texture(history, vec2(t0.x, t0.y)).rgb * (w0.x * w0.y)",
        "        + texture(history, vec2(t12.x, t0.y)).rgb * (w12.x * w0.y)",
        "        + texture(history, vec2(t3.x, t0.y)).rgb * (w3.x * w0.y)",
        "        + texture(history, vec2(t0.x, t12.y)).rgb * (w0.x * w12.y)",
        "        + texture(history, vec2(t12.x, t12.y)).rgb * (w12.x * w12.y)",
        "        + texture(history, vec2(t3.x, t12.y)).rgb * (w3.x * w12.y)",
        "        + texture(history, vec2(t0.x, t3.y)).rgb * (w0.x * w3.y)",
        "        + texture(history, vec2(t12.x, t3.y)).rgb * (w12.x * w3.y)",
        "        + texture(history, vec2(t3.x, t3.y)).rgb * (w3.x * w3.y);",
        "    return max(sum, vec3(0.0));",
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
        "            float z = texelFetch(depthNow, q, 0).r;",
        "            if (nearerIsGreater != 0 ? z > nearest : z < nearest) {",
        "                nearest = z;",
        "                nearestAt = q;",
        "            }",
        "        }",
        "    }",
        "    vec3 now = filtered / weights;",
        "    vec3 result = now;",
        "    vec2 m = texelFetch(motion, nearestAt, 0).rg;",
        "    vec2 uv = (vec2(p) + 0.5 + m) / size;",
        "    bool inside = uv.x >= 0.0 && uv.y >= 0.0 && uv.x <= 1.0 && uv.y <= 1.0;",
        "    if (kept != 0 && inside) {",
        "        vec3 before = intoBox(toYCoCg(historyAt(uv)), low, high);",
        "        // Resampled every frame under motion the history softens: this frame weighs more as it moves.",
        "        float alpha = mix(least, max(least, 1.0 / 3.0), clamp(length(m) * moving, 0.0, 1.0));",
        "        if (masked != 0 && texelFetch(depthNow, p, 0).r != texelFetch(depthTerrain, p, 0).r) {",
        "            alpha = max(alpha, reactive);",
        "        }",
        "        float wNow = alpha / (1.0 + now.x);",
        "        float wBefore = (1.0 - alpha) / (1.0 + before.x);",
        "        result = (now * wNow + before * wBefore) / (wNow + wBefore);",
        "    }",
        "    imageStore(historyOut, p, vec4(max(fromYCoCg(result), vec3(0.0)), 1.0));",
        "}");

    /**
     * The spatial scaler, at the output's size. Each output pixel is made from the 4 by 4 source
     * texels around where its center falls. Two kernels of Keys' cubic are summed over them: one apart
     * along x and y, and one round but lengthened along the edge the four central texels show, found from
     * the structure tensor of their luma gradients. The second takes over as the edge grows sure and
     * strong, so stairs along an edge are smoothed along it while it stays sharp across. A small boost of
     * the difference from a bilinear read sharpens, and the result is held inside the four central
     * texels' range, which keeps the cubic's negative lobe from ringing.
     *
     * <p>A work group's 8 by 8 output pixels reach at most 11 source texels each way when the output is
     * larger than the source; the group reads them once into shared memory, with their luma, and every
     * pixel takes its 16 taps from there. The separable kernel's weights are four along each axis.
     * Where the edge's weight is under a 256th the round kernel is not summed.
     */
    static final String SCALE_COMPUTE = scale(true);

    /**
     * The plain scaler, behind the same interface: the separable Keys cubic alone, with the same
     * sharpening and the same hold to the central texels' range, and no edge steering.
     * {@code -Dquadrature.scale.kernel=plain} selects it.
     */
    static final String SCALE_PLAIN_COMPUTE = scale(false);

    private static String scale(boolean steering) {
        return String.join("\n",
            "#version 460 core",
            "#define STEERING " + (steering ? 1 : 0),
            "#define TILE 12",
            "layout(local_size_x = 8, local_size_y = 8) in;",
            "layout(binding = 4) uniform sampler2D source;",
            "layout(rgba8, binding = 0) uniform writeonly image2D picture;",
            "layout(location = 1) uniform vec2 sourceSize;",
            "layout(location = 2) uniform vec2 outputSize;",
            "layout(location = 3) uniform float sharpness;",
            "#if STEERING",
            "layout(location = 4) uniform float stretch;",
            "layout(location = 5) uniform float edgeScale;",
            "#endif",
            "// The source texels the group's pixels reach, clamped to the source, each with its luma in w.",
            "shared vec4 tile[TILE * TILE];",
            "float luma(vec3 c) {",
            "    return dot(c, vec3(0.299, 0.587, 0.114));",
            "}",
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
            "        vec3 v = clamp(texelFetch(source, clamp(first + ivec2(i % TILE, i / TILE), ivec2(0), last), 0).rgb, 0.0, 1.0);",
            "        tile[i] = vec4(v, luma(v));",
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
            "    float l[16];",
            "    for (int y = 0; y < 4; y++) {",
            "        for (int x = 0; x < 4; x++) {",
            "            vec4 v = tile[(at.y + y) * TILE + at.x + x];",
            "            c[y * 4 + x] = v.rgb;",
            "            l[y * 4 + x] = v.w;",
            "        }",
            "    }",
            "    vec4 wx = vec4(keys(-1.0 - f.x), keys(-f.x), keys(1.0 - f.x), keys(2.0 - f.x));",
            "    vec4 wy = vec4(keys(-1.0 - f.y), keys(-f.y), keys(1.0 - f.y), keys(2.0 - f.y));",
            "    vec3 square = vec3(0.0);",
            "    for (int y = 0; y < 4; y++) {",
            "        square += wy[y] * (wx.x * c[y * 4] + wx.y * c[y * 4 + 1] + wx.z * c[y * 4 + 2] + wx.w * c[y * 4 + 3]);",
            "    }",
            "    vec3 result = square / (dot(wx, vec4(1.0)) * dot(wy, vec4(1.0)));",
            "#if STEERING",
            "    // The structure tensor at the point: central differences at the four central texels, weighted",
            "    // as a bilinear read would weigh them.",
            "    vec3 tensor = vec3(0.0);",
            "    for (int y = 1; y <= 2; y++) {",
            "        for (int x = 1; x <= 2; x++) {",
            "            float gx = 0.5 * (l[y * 4 + x + 1] - l[y * 4 + x - 1]);",
            "            float gy = 0.5 * (l[(y + 1) * 4 + x] - l[(y - 1) * 4 + x]);",
            "            float w = (x == 1 ? 1.0 - f.x : f.x) * (y == 1 ? 1.0 - f.y : f.y);",
            "            tensor += w * vec3(gx * gx, gx * gy, gy * gy);",
            "        }",
            "    }",
            "    float trace = tensor.x + tensor.z;",
            "    float root = sqrt((tensor.x - tensor.z) * (tensor.x - tensor.z) + 4.0 * tensor.y * tensor.y);",
            "    // How sure the edge is (0 alike in every direction, 1 a clean edge) times how strong it is.",
            "    float coherence = trace > 1e-10 ? root / trace : 0.0;",
            "    float edge = coherence * clamp(sqrt(0.5 * (trace + root)) * edgeScale, 0.0, 1.0);",
            "    if (edge > 1.0 / 256.0) {",
            "        // The larger eigenvalue's vector, the gradient's direction, from whichever form is better defined.",
            "        vec2 e1 = vec2(2.0 * tensor.y, tensor.z - tensor.x + root);",
            "        vec2 e2 = vec2(tensor.x - tensor.z + root, 2.0 * tensor.y);",
            "        vec2 across = dot(e1, e1) > dot(e2, e2) ? e1 : e2;",
            "        float span = length(across);",
            "        across = span > 1e-12 ? across / span : vec2(1.0, 0.0);",
            "        vec2 along = vec2(-across.y, across.x);",
            "        vec3 directed = vec3(0.0);",
            "        float directedWeights = 0.0;",
            "        for (int y = 0; y < 4; y++) {",
            "            for (int x = 0; x < 4; x++) {",
            "                vec2 d = vec2(x - 1, y - 1) - f;",
            "                float v = keys(length(vec2(dot(d, along) / (1.0 + stretch), dot(d, across))));",
            "                directed += c[y * 4 + x] * v;",
            "                directedWeights += v;",
            "            }",
            "        }",
            "        directed = abs(directedWeights) > 1e-4 ? directed / directedWeights : result;",
            "        result = mix(result, directed, edge);",
            "    }",
            "#endif",
            "    vec3 low = min(min(c[5], c[6]), min(c[9], c[10]));",
            "    vec3 high = max(max(c[5], c[6]), max(c[9], c[10]));",
            "    vec3 bilinear = mix(mix(c[5], c[6], f.x), mix(c[9], c[10], f.x), f.y);",
            "    result = clamp(result + sharpness * (result - bilinear), low, high);",
            "    imageStore(picture, q, vec4(result, 1.0));",
            "}");
    }

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
