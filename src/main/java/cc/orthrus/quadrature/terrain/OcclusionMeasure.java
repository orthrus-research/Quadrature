package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.terrain.CubeKey;
import cc.orthrus.quadrature.core.terrain.CubeList;
import cc.orthrus.quadrature.core.terrain.CubeSlots;
import cc.orthrus.quadrature.core.terrain.DepthPyramid;
import cc.orthrus.quadrature.core.view.View;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

/**
 * What testing cubes against a depth pyramid on the GPU would leave out, measured and nothing more: behind
 * {@code -Dquadrature.terrain.occlusion=measure}. Nothing is left out of any draw.
 *
 * <ul>
 * <li><b>The player's view</b>, every frame once its opaque layers are drawn: the frame's depth is copied, a
 * pyramid built from it, and every cube the search found that draws anything is tested against it as
 * {@link DepthPyramid} tests a box. The depth is this frame's own terrain, which the tested cubes drew; a cube
 * cannot hide itself (its box's nearest point is nearer than any of its faces), so what is counted is terrain
 * that drew no pixel and lies wholly behind other terrain: the most a test against this frame's depth could
 * save. A two-phase test against the cubes drawn first can only save less.</li>
 * <li><b>The light's wide box</b>, every {@link #LIGHT_FRAMES} frames once it is drawn whole: its published
 * depth (conventional, 0 nearest the light) is built into a pyramid the same way, and every drawable cube the
 * box holds is tested against it: what is hidden from the light behind other terrain, such as caves under
 * the ground.</li>
 * </ul>
 *
 * <p>Counts are kept a frame a slot and read through a staging copy a window later, so reading never waits
 * for the GPU and nothing overflows; the measure's own GPU time is taken by timestamps. The player's view is
 * measured under reversed depth only: with the conventional depth the measure says so once and skips it.
 */
final class OcclusionMeasure {

    static final boolean ON = "measure".equals(System.getProperty("quadrature.terrain.occlusion"));
    /** Frames between measures of the light's wide box. */
    static final int LIGHT_FRAMES = 600;

    /** Frames a window of the player's log covers. */
    private static final int WINDOW = 300;
    private static final int TESTED = 0;
    private static final int HIDDEN = 1;
    private static final int OUTSIDE = 2;
    private static final int AT_EYE = 3;
    private static final int VERTICES = 4;
    private static final int VERTICES_HIDDEN = 5;
    private static final int FACE_VERTICES = 6;
    private static final int FACE_VERTICES_HIDDEN = 7;
    private static final int COUNTS = 8;
    private static final int SLOT_BYTES = COUNTS * 4;
    private static final int CUBE_BYTES = 32;
    private static final int RING = 3;
    private static final int TIMER_RING = 4;
    /** The texture unit the depth and the pyramid are read on: one the game does not use. */
    private static final int UNIT = 15;

    // Level 0 of the pyramid from the depth, as reversed depth: a conventional depth is turned over.
    private static final String COPY = String.join("\n",
        "#version 450",
        "layout(local_size_x = 8, local_size_y = 8) in;",
        "layout(binding = " + UNIT + ") uniform sampler2D depth;",
        "layout(r32f, binding = 0) writeonly uniform image2D level0;",
        "uniform ivec2 size;",
        "uniform bool conventional;",
        "void main() {",
        "    ivec2 p = ivec2(gl_GlobalInvocationID.xy);",
        "    if (any(greaterThanEqual(p, size))) {",
        "        return;",
        "    }",
        "    float d = texelFetch(depth, p, 0).r;",
        "    imageStore(level0, p, vec4(conventional ? 1.0 - d : d));",
        "}");

    // Each texel the farthest (least) of the texels below it: two by two, three where the level below is odd
    // and the texel is the last of its row or column (DepthPyramid.reduce).
    private static final String REDUCE = String.join("\n",
        "#version 450",
        "layout(local_size_x = 8, local_size_y = 8) in;",
        "layout(r32f, binding = 0) readonly uniform image2D below;",
        "layout(r32f, binding = 1) writeonly uniform image2D above;",
        "uniform ivec2 belowSize;",
        "uniform ivec2 aboveSize;",
        "void main() {",
        "    ivec2 p = ivec2(gl_GlobalInvocationID.xy);",
        "    if (any(greaterThanEqual(p, aboveSize))) {",
        "        return;",
        "    }",
        "    ivec2 first = min(2 * p, belowSize - 1);",
        "    ivec2 last = ivec2(p.x == aboveSize.x - 1 ? belowSize.x - 1 : 2 * p.x + 1,",
        "                       p.y == aboveSize.y - 1 ? belowSize.y - 1 : 2 * p.y + 1);",
        "    float farthest = 3.0e38;",
        "    for (int y = first.y; y <= last.y; y++) {",
        "        for (int x = first.x; x <= last.x; x++) {",
        "            farthest = min(farthest, imageLoad(below, ivec2(x, y)).r);",
        "        }",
        "    }",
        "    imageStore(above, p, vec4(farthest));",
        "}");

    // DepthPyramid.test, a cube a thread: its box of 16 blocks from its origin relative to the view.
    private static final String TEST = String.join("\n",
        "#version 450",
        "layout(local_size_x = 64) in;",
        "struct Cube { vec4 origin; uvec4 counts; };",
        "layout(std430, binding = 0) readonly buffer Cubes { Cube cubes[]; };",
        "layout(std430, binding = 1) buffer Counts { uint counts[]; };",
        "layout(binding = " + UNIT + ") uniform sampler2D pyramid;",
        "uniform mat4 viewProjection;",
        "uniform ivec4 viewport;",
        "uniform int levels;",
        "uniform uint cubeCount;",
        "uniform uint slot;",
        "uniform bool conventional;",
        "int under(int p, int level, int size) {",
        "    return min(p >> level, size - 1);",
        "}",
        "void main() {",
        "    uint i = gl_GlobalInvocationID.x;",
        "    if (i >= cubeCount) {",
        "        return;",
        "    }",
        "    Cube cube = cubes[i];",
        "    uint base = slot * " + COUNTS + "u;",
        "    atomicAdd(counts[base + " + TESTED + "u], 1u);",
        "    atomicAdd(counts[base + " + VERTICES + "u], cube.counts.x);",
        "    atomicAdd(counts[base + " + FACE_VERTICES + "u], cube.counts.y);",
        "    vec2 lo = vec2(3.0e38);",
        "    vec2 hi = vec2(-3.0e38);",
        "    float nearest = -3.0e38;",
        "    for (int k = 0; k < 8; k++) {",
        "        vec3 corner = cube.origin.xyz + vec3((k & 1) != 0 ? 16.0 : 0.0, (k & 2) != 0 ? 16.0 : 0.0,",
        "                                             (k & 4) != 0 ? 16.0 : 0.0);",
        "        vec4 clip = viewProjection * vec4(corner, 1.0);",
        "        if (clip.w <= " + DepthPyramid.EYE_PLANE + ") {",
        "            atomicAdd(counts[base + " + AT_EYE + "u], 1u);",
        "            return;",
        "        }",
        "        vec2 screen = vec2(viewport.xy) + (clip.xy / clip.w * 0.5 + 0.5) * vec2(viewport.zw);",
        "        lo = min(lo, screen);",
        "        hi = max(hi, screen);",
        "        nearest = max(nearest, conventional ? 0.5 - 0.5 * clip.z / clip.w : clip.z / clip.w);",
        "    }",
        "    ivec2 size0 = textureSize(pyramid, 0);",
        "    ivec2 a = max(viewport.xy, ivec2(0));",
        "    ivec2 b = min(viewport.xy + viewport.zw, size0) - 1;",
        "    if (hi.x < float(a.x) || lo.x > float(b.x + 1) || hi.y < float(a.y) || lo.y > float(b.y + 1)) {",
        "        atomicAdd(counts[base + " + OUTSIDE + "u], 1u);",
        "        return;",
        "    }",
        "    ivec2 pmin = max(a, ivec2(floor(lo)));",
        "    ivec2 pmax = min(b, ivec2(floor(hi)));",
        "    int level = 0;",
        "    ivec2 size = size0;",
        "    while (level < levels - 1 && (under(pmax.x, level, size.x) - under(pmin.x, level, size.x) > 1",
        "                                  || under(pmax.y, level, size.y) - under(pmin.y, level, size.y) > 1)) {",
        "        level++;",
        "        size = textureSize(pyramid, level);",
        "    }",
        "    float farthest = 3.0e38;",
        "    for (int y = under(pmin.y, level, size.y); y <= under(pmax.y, level, size.y); y++) {",
        "        for (int x = under(pmin.x, level, size.x); x <= under(pmax.x, level, size.x); x++) {",
        "            farthest = min(farthest, texelFetch(pyramid, ivec2(x, y), level).r);",
        "        }",
        "    }",
        "    if (nearest < farthest) {",
        "        atomicAdd(counts[base + " + HIDDEN + "u], 1u);",
        "        atomicAdd(counts[base + " + VERTICES_HIDDEN + "u], cube.counts.x);",
        "        atomicAdd(counts[base + " + FACE_VERTICES_HIDDEN + "u], cube.counts.y);",
        "    }",
        "}");

    private boolean refused;
    private boolean failed;
    private int copyProgram;
    private int reduceProgram;
    private int testProgram;
    private final Pyramid main = new Pyramid("player's view");
    private final Pyramid light = new Pyramid("light's wide box");
    private final int[] cubeBuffers = new int[RING + 1];
    private final int[] cubeCapacity = new int[RING + 1];
    private int counters;
    private final int[] staging = new int[2];
    private int lightCounters;
    private final int[] lightStaging = new int[2];
    private final int[][] timestamps = new int[TIMER_RING][3];
    private final boolean[] timed = new boolean[TIMER_RING];
    private ByteBuffer cubes = ByteBuffer.allocateDirect(4096 * CUBE_BYTES).order(ByteOrder.nativeOrder());
    private final ByteBuffer slotRead = ByteBuffer.allocateDirect(WINDOW * SLOT_BYTES).order(ByteOrder.nativeOrder());
    private final float[] viewProjection = new float[16];
    private final int[] viewport = new int[4];

    private int frame;
    private int inWindow;
    private int windows;
    private int pendingStaging = -1;
    private String pendingWhere = "";
    private long pyramidNanos;
    private long testNanos;
    private int timedFrames;
    private int lightMeasures;
    private int lightPending = -1;
    private String lightPendingWhere = "";
    private int lightQuery;
    private int lightQueryEnd;
    private boolean lightTimed;

    // State put back after each measure.
    private int savedProgram;
    private int savedActive;
    private int savedTexture;
    private final int[] savedImage = new int[2];
    private final int[] savedImageLevel = new int[2];
    private final boolean[] savedImageLayered = new boolean[2];
    private final int[] savedImageLayer = new int[2];
    private final int[] savedImageAccess = new int[2];
    private final int[] savedImageFormat = new int[2];
    private final int[] savedStorage = new int[2];
    private final long[] savedStorageStart = new long[2];
    private final long[] savedStorageSize = new long[2];
    private int savedGeneralStorage;

    /** A depth copied into a texture of its own, and the pyramid over it. */
    private final class Pyramid {
        final String name;
        int depthTexture;
        int texture;
        int format;
        int width;
        int height;
        int levels;

        Pyramid(String name) {
            this.name = name;
        }

        void ensure(int newFormat, int newWidth, int newHeight) {
            if (newWidth == width && newHeight == height && newFormat == format) {
                return;
            }
            if (depthTexture != 0) {
                GL11.glDeleteTextures(depthTexture);
                GL11.glDeleteTextures(texture);
            }
            width = newWidth;
            height = newHeight;
            format = newFormat;
            levels = DepthPyramid.levels(width, height);
            depthTexture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(depthTexture, 1, format, width, height);
            GL45.glTextureParameteri(depthTexture, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(depthTexture, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(depthTexture, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE, GL11.GL_DEPTH_COMPONENT);
            GL45.glTextureParameteri(depthTexture, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
            texture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(texture, levels, GL30.GL_R32F, width, height);
            GL45.glTextureParameteri(texture, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
            GL45.glTextureParameteri(texture, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            Quadrature.LOGGER.info("Occlusion measure, {}: depth {} by {} (format 0x{}), a pyramid of {} levels.", name,
                width, height, Integer.toHexString(format), levels);
        }

        /** Copies the depth from {@code source} (a renderbuffer, a 2D texture or a layer of an array) and builds. */
        void build(int source, int sourceTarget, int sourceLayer, boolean conventional) {
            GL43.glCopyImageSubData(source, sourceTarget, 0, 0, 0, sourceLayer, depthTexture, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                width, height, 1);
            GL20.glUseProgram(copyProgram);
            bindTexture(depthTexture);
            GL42.glBindImageTexture(0, texture, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
            GL20.glUniform2i(GL20.glGetUniformLocation(copyProgram, "size"), width, height);
            GL20.glUniform1i(GL20.glGetUniformLocation(copyProgram, "conventional"), conventional ? 1 : 0);
            GL43.glDispatchCompute((width + 7) / 8, (height + 7) / 8, 1);
            GL20.glUseProgram(reduceProgram);
            int belowWidth = width;
            int belowHeight = height;
            for (int level = 1; level < levels; level++) {
                GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                int aboveWidth = DepthPyramid.next(belowWidth);
                int aboveHeight = DepthPyramid.next(belowHeight);
                GL42.glBindImageTexture(0, texture, level - 1, false, 0, GL15.GL_READ_ONLY, GL30.GL_R32F);
                GL42.glBindImageTexture(1, texture, level, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
                GL20.glUniform2i(GL20.glGetUniformLocation(reduceProgram, "belowSize"), belowWidth, belowHeight);
                GL20.glUniform2i(GL20.glGetUniformLocation(reduceProgram, "aboveSize"), aboveWidth, aboveHeight);
                GL43.glDispatchCompute((aboveWidth + 7) / 8, (aboveHeight + 7) / 8, 1);
                belowWidth = aboveWidth;
                belowHeight = aboveHeight;
            }
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        }

        /** Tests the cubes uploaded to {@code buffer} against this pyramid, counting into a slot of {@code into}. */
        void test(int buffer, int count, int into, int slot, boolean conventional) {
            GL45.glClearNamedBufferSubData(into, GL30.GL_R32UI, (long) slot * SLOT_BYTES, SLOT_BYTES, GL30.GL_RED_INTEGER,
                GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
            if (count == 0) {
                return;
            }
            GL20.glUseProgram(testProgram);
            bindTexture(texture);
            GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0, buffer, 0, (long) count * CUBE_BYTES);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, into);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(testProgram, "viewProjection"), false, viewProjection);
            GL20.glUniform4i(GL20.glGetUniformLocation(testProgram, "viewport"), viewport[0], viewport[1], viewport[2],
                viewport[3]);
            GL20.glUniform1i(GL20.glGetUniformLocation(testProgram, "levels"), levels);
            GL30.glUniform1ui(GL20.glGetUniformLocation(testProgram, "cubeCount"), count);
            GL30.glUniform1ui(GL20.glGetUniformLocation(testProgram, "slot"), slot);
            GL20.glUniform1i(GL20.glGetUniformLocation(testProgram, "conventional"), conventional ? 1 : 0);
            GL43.glDispatchCompute((count + 63) / 64, 1, 1);
        }
    }

    /**
     * Measures the player's view in one frame, once its opaque layers are drawn into the bound framebuffer.
     *
     * @param faceSlots the face records' slots, or null where faces are not drawn from records
     */
    void measure(View view, CubeList visible, CubeSlots slots, CubeSlots faceSlots) {
        if (failed || !programs()) {
            return;
        }
        if (view.depth() != View.Depth.REVERSED) {
            if (!refused) {
                refused = true;
                Quadrature.LOGGER.warn("The occlusion measure needs reversed depth for the player's view, which is off: "
                    + "the player's view is not measured.");
            }
            return;
        }
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (framebuffer == 0) {
            return;
        }
        int type = GL45.glGetNamedFramebufferAttachmentParameteri(framebuffer, GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int name = GL45.glGetNamedFramebufferAttachmentParameteri(framebuffer, GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int target;
        if (type == GL30.GL_RENDERBUFFER) {
            target = GL30.GL_RENDERBUFFER;
            main.ensure(GL45.glGetNamedRenderbufferParameteri(name, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT),
                GL45.glGetNamedRenderbufferParameteri(name, GL30.GL_RENDERBUFFER_WIDTH),
                GL45.glGetNamedRenderbufferParameteri(name, GL30.GL_RENDERBUFFER_HEIGHT));
        } else if (type == GL11.GL_TEXTURE) {
            target = GL11.GL_TEXTURE_2D;
            main.ensure(GL45.glGetTextureLevelParameteri(name, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT),
                GL45.glGetTextureLevelParameteri(name, 0, GL11.GL_TEXTURE_WIDTH),
                GL45.glGetTextureLevelParameteri(name, 0, GL11.GL_TEXTURE_HEIGHT));
        } else {
            return;
        }
        int count = fill(view, visible, slots, faceSlots);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int ring = frame % RING;
        int timer = frame % TIMER_RING;
        readTimes(timer);
        save();
        try {
            GL33.glQueryCounter(timestamps[timer][0], GL33.GL_TIMESTAMP);
            main.build(name, target, 0, false);
            GL33.glQueryCounter(timestamps[timer][1], GL33.GL_TIMESTAMP);
            upload(ring, count);
            main.test(cubeBuffers[ring], count, counters, inWindow, false);
            GL33.glQueryCounter(timestamps[timer][2], GL33.GL_TIMESTAMP);
            timed[timer] = true;
        } finally {
            restore();
        }
        frame++;
        if (++inWindow == WINDOW) {
            if (pendingStaging >= 0) {
                report("Occlusion measure, window " + (++windows) + " (" + WINDOW + " frames, ending at cube " + pendingWhere
                    + ")", staging[pendingStaging], WINDOW, String.format("; GPU a frame: pyramid %.3f ms, test %.3f ms",
                    pyramidNanos / Math.max(1.0, timedFrames) / 1e6, testNanos / Math.max(1.0, timedFrames) / 1e6));
                pyramidNanos = 0;
                testNanos = 0;
                timedFrames = 0;
            }
            int next = (pendingStaging + 1) % staging.length;
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL45.glCopyNamedBufferSubData(counters, staging[next], 0, 0, (long) WINDOW * SLOT_BYTES);
            pendingStaging = next;
            pendingWhere = where(view);
            inWindow = 0;
        }
    }

    /**
     * Measures the light's wide box, drawn whole into layer {@code layer} of {@code texture} (a 2D array of
     * conventional depth), against every drawable cube it holds.
     */
    void measureLight(View view, CubeList held, CubeSlots slots, CubeSlots faceSlots, int texture, int layer, int side) {
        if (failed || !programs()) {
            return;
        }
        light.ensure(GL45.glGetTextureLevelParameteri(texture, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT), side, side);
        int count = fill(view, held, slots, faceSlots);
        viewport[0] = 0;
        viewport[1] = 0;
        viewport[2] = side;
        viewport[3] = side;
        if (lightPending >= 0) {
            String time = "";
            if (lightTimed && GL15.glGetQueryObjecti(lightQueryEnd, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long start = GL33.glGetQueryObjecti64(lightQuery, GL15.GL_QUERY_RESULT);
                long end = GL33.glGetQueryObjecti64(lightQueryEnd, GL15.GL_QUERY_RESULT);
                time = String.format("; GPU %.3f ms for the pyramid and the test", (end - start) / 1e6);
            }
            report("Occlusion measure, the light's wide box, measure " + (++lightMeasures) + " (at cube " + lightPendingWhere
                + ")", lightStaging[lightPending], 1, time);
        }
        save();
        try {
            GL33.glQueryCounter(lightQuery, GL33.GL_TIMESTAMP);
            light.build(texture, GL30.GL_TEXTURE_2D_ARRAY, layer, true);
            upload(RING, count);
            light.test(cubeBuffers[RING], count, lightCounters, 0, true);
            GL33.glQueryCounter(lightQueryEnd, GL33.GL_TIMESTAMP);
            lightTimed = true;
        } finally {
            restore();
        }
        int next = (lightPending + 1) % lightStaging.length;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL45.glCopyNamedBufferSubData(lightCounters, lightStaging[next], 0, 0, SLOT_BYTES);
        lightPending = next;
        lightPendingWhere = where(view);
    }

    private static String where(View view) {
        return String.format("%d %d %d", view.originCubeX(), view.originCubeY(), view.originCubeZ());
    }

    /** The cubes that draw anything, relative to the view's origin, with what they draw; the view's matrix. */
    private int fill(View view, CubeList list, CubeSlots slots, CubeSlots faceSlots) {
        int count = 0;
        cubes.clear();
        for (int i = 0, seen = list.count(); i < seen; i++) {
            long key = list.key(i);
            int vertices = 0;
            int slot = slots.find(key);
            if (slot >= 0 && slots.block(slot) >= 0) {
                for (int layer = 0; layer < CubeSlots.LAYERS; layer++) {
                    vertices += slots.count(slot, layer);
                }
            }
            int faceVertices = 0;
            int faces = faceSlots == null ? -1 : faceSlots.find(key);
            if (faces >= 0 && faceSlots.block(faces) >= 0) {
                for (int layer = 0; layer < CubeSlots.LAYERS; layer++) {
                    faceVertices += faceSlots.count(faces, layer);
                }
            }
            if (vertices == 0 && faceVertices == 0) {
                continue;
            }
            if (cubes.remaining() < CUBE_BYTES) {
                ByteBuffer larger = ByteBuffer.allocateDirect(cubes.capacity() * 2).order(ByteOrder.nativeOrder());
                cubes.flip();
                larger.put(cubes);
                cubes = larger;
            }
            cubes.putFloat((float) ((CubeKey.x(key) << 4) - view.originX()));
            cubes.putFloat((float) ((CubeKey.y(key) << 4) - view.originY()));
            cubes.putFloat((float) ((CubeKey.z(key) << 4) - view.originZ()));
            cubes.putFloat(0f);
            cubes.putInt(vertices).putInt(faceVertices).putInt(0).putInt(0);
            count++;
        }
        cubes.flip();
        multiply(view.projection(), view.modelView(), viewProjection);
        return count;
    }

    private static void multiply(float[] a, float[] b, float[] out) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[column * 4 + k];
                }
                out[column * 4 + row] = sum;
            }
        }
    }

    private void upload(int ring, int count) {
        if (count == 0) {
            return;
        }
        long bytes = (long) count * CUBE_BYTES;
        if (cubeCapacity[ring] < bytes) {
            if (cubeBuffers[ring] != 0) {
                GL15.glDeleteBuffers(cubeBuffers[ring]);
            }
            cubeCapacity[ring] = (int) Math.max(bytes * 2, 64 * 1024);
            cubeBuffers[ring] = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(cubeBuffers[ring], cubeCapacity[ring], GL44.GL_DYNAMIC_STORAGE_BIT);
        }
        GL45.glNamedBufferSubData(cubeBuffers[ring], 0, cubes);
    }

    /** The programs, counters and queries, made the first time; false where a program failed. */
    private boolean programs() {
        if (copyProgram != 0) {
            return true;
        }
        copyProgram = link("copy", COPY);
        reduceProgram = link("reduce", REDUCE);
        testProgram = link("test", TEST);
        if (copyProgram == 0 || reduceProgram == 0 || testProgram == 0) {
            failed = true;
            return false;
        }
        counters = storage((long) WINDOW * SLOT_BYTES);
        for (int i = 0; i < staging.length; i++) {
            staging[i] = storage((long) WINDOW * SLOT_BYTES);
        }
        lightCounters = storage(SLOT_BYTES);
        for (int i = 0; i < lightStaging.length; i++) {
            lightStaging[i] = storage(SLOT_BYTES);
        }
        for (int[] row : timestamps) {
            for (int i = 0; i < row.length; i++) {
                row[i] = GL15.glGenQueries();
            }
        }
        lightQuery = GL15.glGenQueries();
        lightQueryEnd = GL15.glGenQueries();
        return true;
    }

    private static int storage(long bytes) {
        int buffer = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(buffer, bytes, GL44.GL_DYNAMIC_STORAGE_BIT);
        return buffer;
    }

    private static void bindTexture(int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    /** Takes the timestamps of a frame measured {@link #TIMER_RING} frames ago, where the driver has them. */
    private void readTimes(int timer) {
        if (!timed[timer]) {
            return;
        }
        timed[timer] = false;
        int[] row = timestamps[timer];
        if (GL15.glGetQueryObjecti(row[2], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
            return;
        }
        long start = GL33.glGetQueryObjecti64(row[0], GL15.GL_QUERY_RESULT);
        long built = GL33.glGetQueryObjecti64(row[1], GL15.GL_QUERY_RESULT);
        long tested = GL33.glGetQueryObjecti64(row[2], GL15.GL_QUERY_RESULT);
        pyramidNanos += built - start;
        testNanos += tested - built;
        timedFrames++;
    }

    /** Reads a staging copy of {@code frames} slots, copied at least a window ago, and logs their sums. */
    private void report(String what, int buffer, int frames, String tail) {
        slotRead.clear().limit(frames * SLOT_BYTES);
        GL45.glGetNamedBufferSubData(buffer, 0, slotRead);
        long[] sum = new long[COUNTS];
        for (int slot = 0; slot < frames; slot++) {
            for (int i = 0; i < COUNTS; i++) {
                sum[i] += Integer.toUnsignedLong(slotRead.getInt((slot * COUNTS + i) * 4));
            }
        }
        double n = frames;
        Quadrature.LOGGER.info(String.format("%s: a frame tests %.0f cubes; behind the frame's terrain %.1f%% of cubes, "
                + "%.1f%% of vertices (%.2f M a frame), %.1f%% of face-record vertices (%.2f M a frame); outside the viewport "
                + "%.1f%%, at the eye %.1f%%%s", what, sum[TESTED] / n, percent(sum[HIDDEN], sum[TESTED]),
            percent(sum[VERTICES_HIDDEN], sum[VERTICES]), sum[VERTICES] / n / 1e6,
            percent(sum[FACE_VERTICES_HIDDEN], sum[FACE_VERTICES]), sum[FACE_VERTICES] / n / 1e6,
            percent(sum[OUTSIDE], sum[TESTED]), percent(sum[AT_EYE], sum[TESTED]), tail));
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0 : 100.0 * part / whole;
    }

    private void save() {
        savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        savedActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        savedTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(savedActive);
        for (int unit = 0; unit < 2; unit++) {
            savedImage[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, unit);
            savedImageLevel[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL, unit);
            savedImageLayered[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED, unit) != 0;
            savedImageLayer[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER, unit);
            savedImageAccess[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, unit);
            savedImageFormat[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT, unit);
            savedStorage[unit] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, unit);
            savedStorageStart[unit] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, unit);
            savedStorageSize[unit] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, unit);
        }
        savedGeneralStorage = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
    }

    private void restore() {
        GL20.glUseProgram(savedProgram);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTexture);
        GL13.glActiveTexture(savedActive);
        for (int unit = 0; unit < 2; unit++) {
            GL42.glBindImageTexture(unit, savedImage[unit], savedImageLevel[unit], savedImageLayered[unit],
                savedImageLayer[unit], savedImageAccess[unit], savedImageFormat[unit] == 0 ? GL30.GL_R32F : savedImageFormat[unit]);
            if (savedStorage[unit] != 0 && savedStorageSize[unit] != 0) {
                GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, unit, savedStorage[unit], savedStorageStart[unit],
                    savedStorageSize[unit]);
            } else {
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, unit, savedStorage[unit]);
            }
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, savedGeneralStorage);
    }

    private static int link(String name, String source) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            Quadrature.LOGGER.error("Occlusion program {} did not compile: {}", name, GL20.glGetShaderInfoLog(shader, 8192));
            GL20.glDeleteShader(shader);
            return 0;
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDetachShader(program, shader);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            Quadrature.LOGGER.error("Occlusion program {} did not link: {}", name, GL20.glGetProgramInfoLog(program, 8192));
            GL20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }
}
