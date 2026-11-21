package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.terrain.DepthPyramid;
import cc.orthrus.quadrature.core.terrain.DrawCommands;
import cc.orthrus.quadrature.core.view.View;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

/**
 * Culling of the player's opaque terrain against a depth pyramid on the GPU, in two phases, behind
 * {@code -Dquadrature.terrain.cull=gpu} ({@code alternate}: culled or drawn whole frame by frame, in an order
 * taken from a hash of the frame's number, each frame's opaque terrain timed, so the two are compared over the
 * same moments and power states; turns of fixed length were found to lock onto a tour's own timing).
 *
 * <ol>
 * <li>At the solid layer, from the commands and records the client thread wrote for the frame, a compute stage
 * keeps the commands of cubes that were visible the frame before (phase 1). Each layer draws them.</li>
 * <li>After the last opaque layer, the frame's depth is copied and a pyramid built from half resolution up,
 * four levels a dispatch ({@link DepthPyramid}'s levels). Every record is tested against it; the commands of
 * cubes now visible that phase 1 did not draw are kept (phase 2) and drawn, each layer under the state the game
 * gave it; each cube's bit for the next frame is set from the test.</li>
 * </ol>
 *
 * <p>Every cube the client thread listed is drawn in phase 1 or tested in phase 2, so the bits decide only how
 * much is drawn early, never what is drawn; a cube's record carries its tag (its slot) to find its bit, and a
 * tag that moves only costs a test. Counts are kept a frame a slot and read through a staging copy a window
 * later; times are taken by timestamps. Reversed depth only.
 */
final class GpuCulling {

    static final String MODE = System.getProperty("quadrature.terrain.cull", "off");
    static final boolean ON = "gpu".equals(MODE) || "alternate".equals(MODE);
    /** A frame's number mixed (splitmix64's finalizer): its bits choose the conditions an alternating run compares. */
    static long mix(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    /**
     * {@code -Dquadrature.terrain.cull.check=true}: every {@link #CHECK_FRAMES} culled frames the depth and the
     * pyramid are copied out, and a few frames later compared with {@link DepthPyramid} built on the CPU from the
     * same depth, every texel of every level.
     */
    static final boolean CHECK = Boolean.getBoolean("quadrature.terrain.cull.check");
    /**
     * {@code -Dquadrature.terrain.cull.depth=copy|blit|alternate}: how the frame's depth reaches a texture the
     * pyramid is built from. {@code blit}, the default, blits its depth alone into a 32-bit float depth texture
     * (equal to the copy bit for bit, and 0.02 to 0.05 ms cheaper at 2560 by 1440 on an RTX 3060); {@code copy}
     * copies the depth attachment whole ({@code glCopyImageSubData}, depth and stencil); {@code alternate} takes
     * one or the other culled frame by frame, as {@link #mix} chooses, and times both. A blit the driver refuses
     * falls back to the copy.
     */
    static final String DEPTH = System.getProperty("quadrature.terrain.cull.depth", "blit");
    private static final int CHECK_FRAMES = 600;

    static final int VERTICES = 0;
    static final int FACES = 1;

    private static final int WINDOW = 300;
    // A frame's counts: for each list, commands and vertices in all, in phase 1 and in phase 2; then records
    // tested, hidden, outside the viewport and at the eye.
    private static final int STATS = 2 * 6 + 4;
    private static final int STAT_RECORDS = 12;
    private static final int SLOT_BYTES = STATS * 4;
    private static final int TIMES = 6;
    private static final int TIMER_RING = 4;
    private static final int DEPTH_UNIT = 14;
    private static final int PYRAMID_UNIT = 15;

    // Four levels of the pyramid a dispatch, from level `first`; level 0 is the depth, level L lies at texture
    // level L - 1. Each texel is the farthest of the texels below it (DepthPyramid.reduce): a texel whose
    // footprint may leave the group's tile (the last of its row or column) reads its whole footprint at level
    // first - 1, every other one its four children in shared memory.
    private static final String BUILD = String.join("\n",
        "#version 450",
        "layout(local_size_x = 16, local_size_y = 16) in;",
        "layout(binding = " + DEPTH_UNIT + ") uniform sampler2D depth;",
        "layout(binding = " + PYRAMID_UNIT + ") uniform sampler2D pyramid;",
        "layout(r32f, binding = 0) writeonly uniform image2D out0;",
        "layout(r32f, binding = 1) writeonly uniform image2D out1;",
        "layout(r32f, binding = 2) writeonly uniform image2D out2;",
        "layout(r32f, binding = 3) writeonly uniform image2D out3;",
        "uniform int first;",
        "uniform int count;",
        "uniform ivec2 sizes[16];",
        "shared float t0[16][16];",
        "shared float t1[8][8];",
        "shared float t2[4][4];",
        "float source(ivec2 p) {",
        "    return first == 1 ? texelFetch(depth, p, 0).r : texelFetch(pyramid, p, first - 2).r;",
        "}",
        "float footprint(ivec2 p, int k) {",
        "    ivec2 src = sizes[first - 1];",
        "    ivec2 dst = sizes[first + k];",
        "    int shift = k + 1;",
        "    ivec2 lo = p << shift;",
        "    ivec2 hi = ivec2(p.x == dst.x - 1 ? src.x - 1 : ((p.x + 1) << shift) - 1,",
        "                     p.y == dst.y - 1 ? src.y - 1 : ((p.y + 1) << shift) - 1);",
        "    float f = 3.0e38;",
        "    for (int y = lo.y; y <= hi.y; y++) {",
        "        for (int x = lo.x; x <= hi.x; x++) {",
        "            f = min(f, source(ivec2(x, y)));",
        "        }",
        "    }",
        "    return f;",
        "}",
        "bool edge(ivec2 p, ivec2 s) {",
        "    return p.x == s.x - 1 || p.y == s.y - 1;",
        "}",
        "void main() {",
        "    ivec2 l = ivec2(gl_LocalInvocationID.xy);",
        "    ivec2 g = ivec2(gl_WorkGroupID.xy);",
        "    ivec2 p = g * 16 + l;",
        "    ivec2 s = sizes[first];",
        "    float v = 3.0e38;",
        "    if (all(lessThan(p, s))) {",
        "        v = footprint(p, 0);",
        "        imageStore(out0, p, vec4(v));",
        "    }",
        "    t0[l.y][l.x] = v;",
        "    memoryBarrierShared();",
        "    barrier();",
        "    if (count > 1 && all(lessThan(l, ivec2(8)))) {",
        "        p = g * 8 + l;",
        "        s = sizes[first + 1];",
        "        v = 3.0e38;",
        "        if (all(lessThan(p, s))) {",
        "            v = edge(p, s) ? footprint(p, 1) : min(min(t0[2 * l.y][2 * l.x], t0[2 * l.y][2 * l.x + 1]),",
        "                                                   min(t0[2 * l.y + 1][2 * l.x], t0[2 * l.y + 1][2 * l.x + 1]));",
        "            imageStore(out1, p, vec4(v));",
        "        }",
        "        t1[l.y][l.x] = v;",
        "    }",
        "    memoryBarrierShared();",
        "    barrier();",
        "    if (count > 2 && all(lessThan(l, ivec2(4)))) {",
        "        p = g * 4 + l;",
        "        s = sizes[first + 2];",
        "        v = 3.0e38;",
        "        if (all(lessThan(p, s))) {",
        "            v = edge(p, s) ? footprint(p, 2) : min(min(t1[2 * l.y][2 * l.x], t1[2 * l.y][2 * l.x + 1]),",
        "                                                   min(t1[2 * l.y + 1][2 * l.x], t1[2 * l.y + 1][2 * l.x + 1]));",
        "            imageStore(out2, p, vec4(v));",
        "        }",
        "        t2[l.y][l.x] = v;",
        "    }",
        "    memoryBarrierShared();",
        "    barrier();",
        "    if (count > 3 && all(lessThan(l, ivec2(2)))) {",
        "        p = g * 2 + l;",
        "        s = sizes[first + 3];",
        "        if (all(lessThan(p, s))) {",
        "            v = edge(p, s) ? footprint(p, 3) : min(min(t2[2 * l.y][2 * l.x], t2[2 * l.y][2 * l.x + 1]),",
        "                                                   min(t2[2 * l.y + 1][2 * l.x], t2[2 * l.y + 1][2 * l.x + 1]));",
        "            imageStore(out3, p, vec4(v));",
        "        }",
        "    }",
        "}");

    // DepthPyramid.test from level 1 up, a record a thread: its cube's box of 16 blocks in the cube's own
    // coordinates, through the record's matrix. Writes 1 where the cube may show.
    private static final String TEST = String.join("\n",
        "#version 450",
        "layout(local_size_x = 64) in;",
        "struct Record { mat4 modelView; mat4 previous; };",
        "layout(std430, binding = 0) readonly buffer Records { Record records[]; };",
        "layout(std430, binding = 1) writeonly buffer Visible { uint visible[]; };",
        "layout(std430, binding = 2) buffer Stats { uint stats[]; };",
        "layout(binding = " + PYRAMID_UNIT + ") uniform sampler2D pyramid;",
        "uniform mat4 projection;",
        "uniform ivec4 viewport;",
        "uniform ivec2 depthSize;",
        "uniform int levels;",
        "uniform uint recordCount;",
        "uniform uint slot;",
        "int under(int p, int level, int size) {",
        "    return min(p >> level, size - 1);",
        "}",
        "void main() {",
        "    uint i = gl_GlobalInvocationID.x;",
        "    if (i >= recordCount) {",
        "        return;",
        "    }",
        "    uint base = slot * " + STATS + "u;",
        "    atomicAdd(stats[base + " + STAT_RECORDS + "u], 1u);",
        "    mat4 m = projection * records[i].modelView;",
        "    vec2 lo = vec2(3.0e38);",
        "    vec2 hi = vec2(-3.0e38);",
        "    float nearest = -3.0e38;",
        "    for (int k = 0; k < 8; k++) {",
        "        vec4 clip = m * vec4((k & 1) != 0 ? 16.0 : 0.0, (k & 2) != 0 ? 16.0 : 0.0, (k & 4) != 0 ? 16.0 : 0.0, 1.0);",
        "        if (clip.w <= " + DepthPyramid.EYE_PLANE + ") {",
        "            atomicAdd(stats[base + " + (STAT_RECORDS + 3) + "u], 1u);",
        "            visible[i] = 1u;",
        "            return;",
        "        }",
        "        vec2 screen = vec2(viewport.xy) + (clip.xy / clip.w * 0.5 + 0.5) * vec2(viewport.zw);",
        "        lo = min(lo, screen);",
        "        hi = max(hi, screen);",
        "        nearest = max(nearest, clip.z / clip.w);",
        "    }",
        "    ivec2 a = max(viewport.xy, ivec2(0));",
        "    ivec2 b = min(viewport.xy + viewport.zw, depthSize) - 1;",
        "    if (hi.x < float(a.x) || lo.x > float(b.x + 1) || hi.y < float(a.y) || lo.y > float(b.y + 1)) {",
        "        atomicAdd(stats[base + " + (STAT_RECORDS + 2) + "u], 1u);",
        "        visible[i] = 0u;",
        "        return;",
        "    }",
        "    ivec2 pmin = max(a, ivec2(floor(lo)));",
        "    ivec2 pmax = min(b, ivec2(floor(hi)));",
        "    int level = 1;",
        "    ivec2 size = textureSize(pyramid, 0);",
        "    while (level < levels - 1 && (under(pmax.x, level, size.x) - under(pmin.x, level, size.x) > 1",
        "                                  || under(pmax.y, level, size.y) - under(pmin.y, level, size.y) > 1)) {",
        "        level++;",
        "        size = textureSize(pyramid, level - 1);",
        "    }",
        "    float farthest = 3.0e38;",
        "    for (int y = under(pmin.y, level, size.y); y <= under(pmax.y, level, size.y); y++) {",
        "        for (int x = under(pmin.x, level, size.x); x <= under(pmax.x, level, size.x); x++) {",
        "            farthest = min(farthest, texelFetch(pyramid, ivec2(x, y), level - 1).r);",
        "        }",
        "    }",
        "    bool hidden = nearest < farthest;",
        "    if (hidden) {",
        "        atomicAdd(stats[base + " + (STAT_RECORDS + 1) + "u], 1u);",
        "    }",
        "    visible[i] = hidden ? 0u : 1u;",
        "}");

    // Keeps a list's commands for a phase: 1, those whose cube was visible the frame before; 2, those whose cube
    // is visible now and was not then. Each kept command goes to its run's next place.
    private static final String SELECT = String.join("\n",
        "#version 450",
        "layout(local_size_x = 64) in;",
        "struct Command { uint count; uint instances; uint first; uint base; };",
        "layout(std430, binding = 0) readonly buffer Commands { Command commands[]; };",
        "layout(std430, binding = 1) readonly buffer Layout { uint layout_[]; };",
        "layout(std430, binding = 2) readonly buffer Tags { uint tags[]; };",
        "layout(std430, binding = 3) readonly buffer Flags { uint flags[]; };",
        "layout(std430, binding = 4) readonly buffer Visible { uint visible[]; };",
        "layout(std430, binding = 5) writeonly buffer Kept { Command kept[]; };",
        "layout(std430, binding = 6) buffer Counts { uint counts[]; };",
        "layout(std430, binding = 7) buffer Stats { uint stats[]; };",
        "uniform uint commandCount;",
        "uniform uint startsAt;",
        "uniform int phase;",
        "uniform uint statBase;",
        "uniform uint flagWords;",
        "void main() {",
        "    uint j = gl_GlobalInvocationID.x;",
        "    if (j >= commandCount) {",
        "        return;",
        "    }",
        "    Command c = commands[j];",
        "    uint tag = tags[c.base];",
        "    bool before = tag != 0xFFFFFFFFu && (tag >> 5) < flagWords && (flags[tag >> 5] & (1u << (tag & 31u))) != 0u;",
        "    if (phase == 1) {",
        "        atomicAdd(stats[statBase], 1u);",
        "        atomicAdd(stats[statBase + 1u], c.count);",
        "    }",
        "    bool take = phase == 1 ? before : (!before && visible[c.base] != 0u);",
        "    if (!take) {",
        "        return;",
        "    }",
        "    uint run = layout_[j];",
        "    uint at = layout_[startsAt + run] + atomicAdd(counts[run], 1u);",
        "    kept[at] = c;",
        "    atomicAdd(stats[statBase + uint(phase) * 2u], 1u);",
        "    atomicAdd(stats[statBase + uint(phase) * 2u + 1u], c.count);",
        "}");

    // Each record's cube bit, for the next frame, from the test.
    private static final String COMMIT = String.join("\n",
        "#version 450",
        "layout(local_size_x = 64) in;",
        "layout(std430, binding = 2) readonly buffer Tags { uint tags[]; };",
        "layout(std430, binding = 3) buffer Flags { uint flags[]; };",
        "layout(std430, binding = 4) readonly buffer Visible { uint visible[]; };",
        "uniform uint recordCount;",
        "uniform uint flagWords;",
        "void main() {",
        "    uint i = gl_GlobalInvocationID.x;",
        "    if (i >= recordCount) {",
        "        return;",
        "    }",
        "    uint tag = tags[i];",
        "    if (tag == 0xFFFFFFFFu || (tag >> 5) >= flagWords) {",
        "        return;",
        "    }",
        "    uint bit = 1u << (tag & 31u);",
        "    if (visible[i] != 0u) {",
        "        atomicOr(flags[tag >> 5], bit);",
        "    } else {",
        "        atomicAnd(flags[tag >> 5], ~bit);",
        "    }",
        "}");

    /** A list's buffers: what the client thread wrote and what the phases keep of it. */
    private static final class ListBuffers {
        int layout;
        long layoutCapacity;
        int tags;
        long tagCapacity;
        int flags;
        int flagWords;
        int visible;
        long visibleCapacity;
        final int[] kept = new int[3];
        final long[] keptCapacity = new long[3];
        final int[] counts = new int[3];
        final long[] countCapacity = new long[3];
        int commands;
        int records;
        int runSlots;
        int commandSource;
        int recordSource;
    }

    private final ListBuffers[] lists = {new ListBuffers(), new ListBuffers()};
    private boolean failed;
    private boolean refused;
    private int buildProgram;
    private int testProgram;
    private int selectProgram;
    private int commitProgram;
    private int depthTexture;
    private int blitTexture;
    private int blitFramebuffer;
    private boolean blitRefused;
    private boolean blitTried;
    private boolean blitNow = "blit".equals(DEPTH);
    private int sourceName;
    private int sourceTarget;
    private int checkCopyBuffer;
    private boolean checkCompare;
    private int depthChecks;
    private int depthChecksFailed;
    private final int[] timedMethod = new int[TIMER_RING];
    private final long[] methodTotal = new long[2];
    private final long[] methodTransfer = new long[2];
    private final int[] methodFrames = new int[2];
    private int pyramid;
    private int depthFormat;
    private int width;
    private int height;
    private int levels;
    private final int[] sizes = new int[32];
    private int stats;
    private final int[] staging = new int[2];
    private final ByteBuffer slotRead = ByteBuffer.allocateDirect(WINDOW * SLOT_BYTES).order(ByteOrder.nativeOrder());
    private final int[][] timestamps = new int[TIMER_RING][TIMES];
    private final boolean[] timed = new boolean[TIMER_RING];
    private final boolean[] timedOn = new boolean[TIMER_RING];
    private final float[] projection = new float[16];
    private final int[] viewport = new int[4];
    private ByteBuffer layoutBytes = ByteBuffer.allocateDirect(16 * 1024).order(ByteOrder.nativeOrder());

    private int checkDepthBuffer;
    private int checkPyramidBuffer;
    private long checkPyramidCapacity;
    private int checkDue = -1;
    private int checkWidth;
    private int checkHeight;
    private int checkLevels;
    private int culledFrames;
    private int checks;
    private int checksFailed;

    private int frame;
    private int slot;
    private int windows;
    private int pending = -1;
    private int pendingFrames;
    private boolean active;
    private boolean turnOn = true;
    private final long[] onNanos = new long[TIMES];
    private long offNanos;
    private int onTimed;
    private int offTimed;

    // State put back around each compute stage.
    private int savedProgram;
    private int savedActive;
    private final int[] savedTextures = new int[2];
    private final int[] savedImage = new int[4];
    private final int[] savedImageLevel = new int[4];
    private final boolean[] savedImageLayered = new boolean[4];
    private final int[] savedImageLayer = new int[4];
    private final int[] savedImageAccess = new int[4];
    private final int[] savedImageFormat = new int[4];
    private final int[] savedStorage = new int[8];
    private final long[] savedStorageStart = new long[8];
    private final long[] savedStorageSize = new long[8];
    private int savedGeneralStorage;

    /**
     * The state a layer is drawn in that differs between the game's opaque layers: the alpha test and its
     * function, and the block texture's filters. Phase 2 draws the earlier layers at the last layer, so each
     * is taken where the game draws it and set again for its phase 2, and the last layer's put back after.
     */
    static final class LayerState {
        private boolean alpha;
        private int function;
        private float reference;
        private int texture;
        private int minFilter;
        private int magFilter;

        void capture() {
            alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            function = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
            reference = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
            int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(active);
            if (texture != 0) {
                minFilter = GL45.glGetTextureParameteri(texture, GL11.GL_TEXTURE_MIN_FILTER);
                magFilter = GL45.glGetTextureParameteri(texture, GL11.GL_TEXTURE_MAG_FILTER);
            }
        }

        void apply() {
            if (alpha) {
                GL11.glEnable(GL11.GL_ALPHA_TEST);
            } else {
                GL11.glDisable(GL11.GL_ALPHA_TEST);
            }
            GL11.glAlphaFunc(function, reference);
            if (texture != 0) {
                GL45.glTextureParameteri(texture, GL11.GL_TEXTURE_MIN_FILTER, minFilter);
                GL45.glTextureParameteri(texture, GL11.GL_TEXTURE_MAG_FILTER, magFilter);
            }
        }
    }

    /** Whether this frame's opaque terrain is culled; fixed at the solid layer. */
    boolean active() {
        return active;
    }

    /** The buffer of a list's commands kept for a phase (1 or 2). */
    int kept(int list, int phase) {
        return lists[list].kept[phase];
    }

    /** The buffer of a list's counts for a phase (1 or 2), one per run. */
    int counts(int list, int phase) {
        return lists[list].counts[phase];
    }

    /**
     * At the solid layer: decides whether this frame is culled, and where it is, keeps phase 1's commands of
     * both lists. The lists' commands and records must already be in {@code commandSource} and
     * {@code recordSource} of each.
     */
    void begin(View view, DrawCommands vertices, int vertexCommands, int vertexRecords, DrawCommands faces,
               int faceCommands, int faceRecords) {
        int timer = frame % TIMER_RING;
        readTimes(timer);
        long draw = mix(frame);
        if ("alternate".equals(MODE)) {
            turnOn = (draw & 1) == 0;
        }
        boolean ready = !failed && programs();
        if (!ready) {
            active = false;
            return;
        }
        active = turnOn && view.depth() == View.Depth.REVERSED;
        if (!failed && view.depth() != View.Depth.REVERSED && !refused) {
            refused = true;
            Quadrature.LOGGER.warn("Culling on the GPU needs reversed depth, which is off: terrain is drawn whole.");
        }
        timedOn[timer] = active;
        if (active && "alternate".equals(DEPTH)) {
            blitNow = (draw & 2) != 0;
        }
        timedMethod[timer] = blitNow && !blitRefused ? 1 : 0;
        GL33.glQueryCounter(timestamps[timer][0], GL33.GL_TIMESTAMP);
        if (!active) {
            return;
        }
        System.arraycopy(view.projection(), 0, projection, 0, 16);
        GL45.glClearNamedBufferSubData(stats, GL30.GL_R32UI, (long) slot * SLOT_BYTES, SLOT_BYTES, GL30.GL_RED_INTEGER,
            GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
        prepare(lists[VERTICES], vertices, vertexCommands, vertexRecords);
        if (faces != null) {
            prepare(lists[FACES], faces, faceCommands, faceRecords);
        } else {
            lists[FACES].commands = 0;
            lists[FACES].records = 0;
        }
        save();
        try {
            for (int list = 0; list < 2; list++) {
                select(list, 1);
            }
        } finally {
            restore();
        }
        GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    /** Uploads a list's layout and tags and readies its kept commands and counts. */
    private void prepare(ListBuffers b, DrawCommands list, int commandSource, int recordSource) {
        b.commands = list.commandCount();
        b.records = list.recordCount();
        b.runSlots = list.runSlots();
        b.commandSource = commandSource;
        b.recordSource = recordSource;
        int layoutInts = b.commands + b.runSlots;
        if (layoutBytes.capacity() < layoutInts * 4) {
            layoutBytes = ByteBuffer.allocateDirect(Math.max(layoutInts * 4, layoutBytes.capacity() * 2)).order(ByteOrder.nativeOrder());
        }
        layoutBytes.clear();
        layoutBytes.put(list.runs().duplicate());
        layoutBytes.put(list.starts().duplicate());
        layoutBytes.flip();
        b.layout = ensure(b.layout, b.layoutCapacity, layoutInts * 4L);
        b.layoutCapacity = Math.max(b.layoutCapacity, layoutInts * 4L);
        if (layoutInts > 0) {
            GL45.glNamedBufferSubData(b.layout, 0, layoutBytes);
        }
        b.tags = ensure(b.tags, b.tagCapacity, b.records * 4L);
        b.tagCapacity = Math.max(b.tagCapacity, b.records * 4L);
        int most = -1;
        ByteBuffer tags = list.tags();
        for (int i = 0; i < b.records; i++) {
            most = Math.max(most, tags.getInt(i * 4));
        }
        if (b.records > 0) {
            GL45.glNamedBufferSubData(b.tags, 0, tags.duplicate());
        }
        int words = Math.max((most >> 5) + 1, 1);
        if (b.flags == 0 || words > b.flagWords) {
            // A larger table starts with every cube unseen: each is tested once more, nothing else changes.
            int grown = Math.max(words * 2, 256);
            if (b.flags != 0) {
                GL15.glDeleteBuffers(b.flags);
            }
            b.flags = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(b.flags, grown * 4L, GL44.GL_DYNAMIC_STORAGE_BIT);
            GL45.glClearNamedBufferData(b.flags, GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, (ByteBuffer) null);
            b.flagWords = grown;
        }
        b.visible = ensure(b.visible, b.visibleCapacity, b.records * 4L);
        b.visibleCapacity = Math.max(b.visibleCapacity, b.records * 4L);
        for (int phase = 1; phase <= 2; phase++) {
            b.kept[phase] = ensure(b.kept[phase], b.keptCapacity[phase], b.commands * (long) DrawCommands.COMMAND_BYTES);
            b.keptCapacity[phase] = Math.max(b.keptCapacity[phase], b.commands * (long) DrawCommands.COMMAND_BYTES);
            b.counts[phase] = ensure(b.counts[phase], b.countCapacity[phase], b.runSlots * 4L);
            b.countCapacity[phase] = Math.max(b.countCapacity[phase], b.runSlots * 4L);
            GL45.glClearNamedBufferData(b.counts[phase], GL30.GL_R32UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT,
                (ByteBuffer) null);
        }
    }

    /** A buffer of at least {@code bytes}: the one given where it is large enough, else a new one. */
    private static int ensure(int buffer, long capacity, long bytes) {
        if (buffer != 0 && capacity >= bytes) {
            return buffer;
        }
        if (buffer != 0) {
            GL15.glDeleteBuffers(buffer);
        }
        int made = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(made, Math.max(bytes * 2, 4096), GL44.GL_DYNAMIC_STORAGE_BIT);
        return made;
    }

    private void select(int list, int phase) {
        ListBuffers b = lists[list];
        if (b.commands == 0) {
            return;
        }
        GL20.glUseProgram(selectProgram);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, b.commandSource);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, b.layout);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, b.tags);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, b.flags);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, b.visible);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, b.kept[phase]);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 6, b.counts[phase]);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 7, stats, (long) slot * SLOT_BYTES, SLOT_BYTES);
        GL30.glUniform1ui(GL20.glGetUniformLocation(selectProgram, "commandCount"), b.commands);
        GL30.glUniform1ui(GL20.glGetUniformLocation(selectProgram, "startsAt"), b.commands);
        GL20.glUniform1i(GL20.glGetUniformLocation(selectProgram, "phase"), phase);
        GL30.glUniform1ui(GL20.glGetUniformLocation(selectProgram, "statBase"), list * 6);
        GL30.glUniform1ui(GL20.glGetUniformLocation(selectProgram, "flagWords"), b.flagWords);
        GL43.glDispatchCompute((b.commands + 63) / 64, 1, 1);
    }

    /**
     * After the last opaque layer's phase 1 is drawn into the bound framebuffer: the pyramid, the test, phase 2's
     * commands and the bits for the next frame. Phase 2 is then drawn by the caller.
     */
    void second() {
        if (!active) {
            return;
        }
        int timer = frame % TIMER_RING;
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL33.glQueryCounter(timestamps[timer][1], GL33.GL_TIMESTAMP);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        save();
        try {
            if (framebuffer == 0 || !transferDepth(framebuffer)) {
                // Nothing to test against: every cube counts as visible.
                markAllVisible();
                GL33.glQueryCounter(timestamps[timer][5], GL33.GL_TIMESTAMP);
                GL33.glQueryCounter(timestamps[timer][2], GL33.GL_TIMESTAMP);
            } else {
                GL33.glQueryCounter(timestamps[timer][5], GL33.GL_TIMESTAMP);
                build();
                GL33.glQueryCounter(timestamps[timer][2], GL33.GL_TIMESTAMP);
                if (CHECK && checkDue < 0 && culledFrames % CHECK_FRAMES == CHECK_FRAMES / 2) {
                    copyForCheck();
                }
                for (int list = 0; list < 2; list++) {
                    test(list);
                }
            }
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            for (int list = 0; list < 2; list++) {
                select(list, 2);
            }
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
            for (int list = 0; list < 2; list++) {
                commit(list);
            }
        } finally {
            restore();
        }
        GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL33.glQueryCounter(timestamps[timer][3], GL33.GL_TIMESTAMP);
    }

    private void markAllVisible() {
        for (ListBuffers b : lists) {
            if (b.records > 0) {
                GL45.glClearNamedBufferSubData(b.visible, GL30.GL_R32UI, 0, b.records * 4L, GL30.GL_RED_INTEGER,
                    GL11.GL_UNSIGNED_INT, ones());
            }
        }
    }

    private static ByteBuffer ones;

    private static ByteBuffer ones() {
        if (ones == null) {
            ones = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            ones.putInt(0, 1);
        }
        return ones;
    }

    /** The end of the frame's opaque terrain, phase 2 drawn; also timed on frames that are not culled. */
    void end() {
        if (active) {
            culledFrames++;
        }
        if (checkDue >= 0 && frame >= checkDue) {
            check();
        }
        int timer = frame % TIMER_RING;
        GL33.glQueryCounter(timestamps[timer][4], GL33.GL_TIMESTAMP);
        timed[timer] = true;
        frame++;
        if (!active) {
            return;
        }
        if (++slot == WINDOW) {
            if (pending >= 0) {
                report();
            }
            int next = (pending + 1) % staging.length;
            GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
            GL45.glCopyNamedBufferSubData(stats, staging[next], 0, 0, (long) WINDOW * SLOT_BYTES);
            pending = next;
            pendingFrames = WINDOW;
            slot = 0;
        }
    }

    private void test(int list) {
        ListBuffers b = lists[list];
        if (b.records == 0) {
            return;
        }
        GL20.glUseProgram(testProgram);
        bind(PYRAMID_UNIT, pyramid);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, b.recordSource);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, b.visible);
        GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 2, stats, (long) slot * SLOT_BYTES, SLOT_BYTES);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(testProgram, "projection"), false, projection);
        GL20.glUniform4i(GL20.glGetUniformLocation(testProgram, "viewport"), viewport[0], viewport[1], viewport[2],
            viewport[3]);
        GL20.glUniform2i(GL20.glGetUniformLocation(testProgram, "depthSize"), width, height);
        GL20.glUniform1i(GL20.glGetUniformLocation(testProgram, "levels"), levels);
        GL30.glUniform1ui(GL20.glGetUniformLocation(testProgram, "recordCount"), b.records);
        GL30.glUniform1ui(GL20.glGetUniformLocation(testProgram, "slot"), 0);
        GL43.glDispatchCompute((b.records + 63) / 64, 1, 1);
    }

    private void commit(int list) {
        ListBuffers b = lists[list];
        if (b.records == 0) {
            return;
        }
        GL20.glUseProgram(commitProgram);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, b.tags);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, b.flags);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, b.visible);
        GL30.glUniform1ui(GL20.glGetUniformLocation(commitProgram, "recordCount"), b.records);
        GL30.glUniform1ui(GL20.glGetUniformLocation(commitProgram, "flagWords"), b.flagWords);
        GL43.glDispatchCompute((b.records + 63) / 64, 1, 1);
    }

    /** The texture the pyramid is built from this frame: the blit's where it blitted, else the copy's. */
    private int currentDepth() {
        return blitNow && !blitRefused ? blitTexture : depthTexture;
    }

    /**
     * Brings the framebuffer's depth into a texture of its own, by copy or by blit; false where it has none.
     */
    private boolean transferDepth(int framebuffer) {
        int type = GL45.glGetNamedFramebufferAttachmentParameteri(framebuffer, GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int name = GL45.glGetNamedFramebufferAttachmentParameteri(framebuffer, GL30.GL_DEPTH_ATTACHMENT,
            GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int format;
        int w;
        int h;
        int target;
        if (type == GL30.GL_RENDERBUFFER) {
            target = GL30.GL_RENDERBUFFER;
            format = GL45.glGetNamedRenderbufferParameteri(name, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
            w = GL45.glGetNamedRenderbufferParameteri(name, GL30.GL_RENDERBUFFER_WIDTH);
            h = GL45.glGetNamedRenderbufferParameteri(name, GL30.GL_RENDERBUFFER_HEIGHT);
        } else if (type == GL11.GL_TEXTURE) {
            target = GL11.GL_TEXTURE_2D;
            format = GL45.glGetTextureLevelParameteri(name, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
            w = GL45.glGetTextureLevelParameteri(name, 0, GL11.GL_TEXTURE_WIDTH);
            h = GL45.glGetTextureLevelParameteri(name, 0, GL11.GL_TEXTURE_HEIGHT);
        } else {
            return false;
        }
        if (w < 2 || h < 2) {
            return false;
        }
        if (w != width || h != height || format != depthFormat) {
            if (depthTexture != 0) {
                GL11.glDeleteTextures(depthTexture);
                GL11.glDeleteTextures(pyramid);
            }
            width = w;
            height = h;
            depthFormat = format;
            levels = DepthPyramid.levels(w, h);
            sizes[0] = w;
            sizes[1] = h;
            for (int level = 1; level < levels; level++) {
                sizes[level * 2] = DepthPyramid.next(sizes[level * 2 - 2]);
                sizes[level * 2 + 1] = DepthPyramid.next(sizes[level * 2 - 1]);
            }
            depthTexture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(depthTexture, 1, format, w, h);
            GL45.glTextureParameteri(depthTexture, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(depthTexture, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(depthTexture, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE, GL11.GL_DEPTH_COMPONENT);
            GL45.glTextureParameteri(depthTexture, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
            pyramid = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(pyramid, levels - 1, GL30.GL_R32F, sizes[2], sizes[3]);
            GL45.glTextureParameteri(pyramid, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
            GL45.glTextureParameteri(pyramid, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            Quadrature.LOGGER.info("Culling on the GPU: depth {} by {} (format 0x{}), a pyramid of {} levels from {} by {}.",
                w, h, Integer.toHexString(format), levels - 1, sizes[2], sizes[3]);
        }
        sourceName = name;
        sourceTarget = target;
        if (blitNow && !blitRefused) {
            blit(framebuffer, w, h);
        }
        if (!blitNow || blitRefused) {
            GL43.glCopyImageSubData(name, target, 0, 0, 0, 0, depthTexture, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
        }
        return true;
    }

    /**
     * Blits the depth alone into a 32-bit float depth texture. The scissor test applies to a blit, so it is held
     * off around it. The first blit is checked for an error, and one refused leaves the copy in its place.
     */
    private void blit(int framebuffer, int w, int h) {
        if (blitTexture == 0 || GL45.glGetTextureLevelParameteri(blitTexture, 0, GL11.GL_TEXTURE_WIDTH) != w
            || GL45.glGetTextureLevelParameteri(blitTexture, 0, GL11.GL_TEXTURE_HEIGHT) != h) {
            if (blitTexture != 0) {
                GL11.glDeleteTextures(blitTexture);
                GL30.glDeleteFramebuffers(blitFramebuffer);
            }
            blitTexture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(blitTexture, 1, GL30.GL_DEPTH_COMPONENT32F, w, h);
            GL45.glTextureParameteri(blitTexture, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(blitTexture, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(blitTexture, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
            blitFramebuffer = GL45.glCreateFramebuffers();
            GL45.glNamedFramebufferTexture(blitFramebuffer, GL30.GL_DEPTH_ATTACHMENT, blitTexture, 0);
            GL45.glNamedFramebufferDrawBuffer(blitFramebuffer, GL11.GL_NONE);
            GL45.glNamedFramebufferReadBuffer(blitFramebuffer, GL11.GL_NONE);
            int status = GL45.glCheckNamedFramebufferStatus(blitFramebuffer, GL30.GL_DRAW_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                blitRefused = true;
                Quadrature.LOGGER.warn("Culling on the GPU: the depth blit's framebuffer is incomplete (0x{}); the depth is copied.",
                    Integer.toHexString(status));
                return;
            }
        }
        boolean first = !blitTried;
        if (first) {
            // Errors already set belong to whatever set them; they are logged here so that they are not taken for the blit's.
            for (int error = GL11.glGetError(), n = 0; error != GL11.GL_NO_ERROR && n < 16; error = GL11.glGetError(), n++) {
                Quadrature.LOGGER.info("Culling on the GPU: an earlier GL error 0x{} was pending before the first depth blit.",
                    Integer.toHexString(error));
            }
        }
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL45.glBlitNamedFramebuffer(framebuffer, blitFramebuffer, 0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT,
            GL11.GL_NEAREST);
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
        if (first) {
            blitTried = true;
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                blitRefused = true;
                Quadrature.LOGGER.warn("Culling on the GPU: the driver refused the depth blit (GL error 0x{}); the depth is copied.",
                    Integer.toHexString(error));
            } else {
                Quadrature.LOGGER.info("Culling on the GPU: the depth is blitted into a 32-bit float depth texture.");
            }
        }
    }

    /** Builds pyramid levels 1 and up, four a dispatch. */
    private void build() {
        GL20.glUseProgram(buildProgram);
        bind(DEPTH_UNIT, currentDepth());
        bind(PYRAMID_UNIT, pyramid);
        GL20.glUniform2iv(GL20.glGetUniformLocation(buildProgram, "sizes"), sizes);
        for (int first = 1; first < levels; first += 4) {
            int count = Math.min(4, levels - first);
            for (int k = 0; k < 4; k++) {
                int textureLevel = Math.min(first - 1 + k, levels - 2);
                GL42.glBindImageTexture(k, pyramid, textureLevel, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
            }
            GL20.glUniform1i(GL20.glGetUniformLocation(buildProgram, "first"), first);
            GL20.glUniform1i(GL20.glGetUniformLocation(buildProgram, "count"), count);
            int w = sizes[first * 2];
            int h = sizes[first * 2 + 1];
            GL43.glDispatchCompute((w + 15) / 16, (h + 15) / 16, 1);
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        }
    }

    /** Copies the depth and every pyramid level into buffers of their own, read a few frames later. */
    private void copyForCheck() {
        long depthBytes = (long) width * height * 4;
        long pyramidBytes = 0;
        for (int level = 1; level < levels; level++) {
            pyramidBytes += (long) sizes[level * 2] * sizes[level * 2 + 1] * 4;
        }
        if (checkDepthBuffer == 0 || checkPyramidCapacity < Math.max(depthBytes, pyramidBytes)) {
            if (checkDepthBuffer != 0) {
                GL15.glDeleteBuffers(checkDepthBuffer);
                GL15.glDeleteBuffers(checkPyramidBuffer);
            }
            checkPyramidCapacity = Math.max(depthBytes, pyramidBytes);
            checkDepthBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(checkDepthBuffer, checkPyramidCapacity, 0);
            checkPyramidBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(checkPyramidBuffer, checkPyramidCapacity, 0);
        }
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, checkDepthBuffer);
        GL45.glGetTextureImage(currentDepth(), 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (int) depthBytes, 0L);
        checkCompare = blitNow && !blitRefused;
        if (checkCompare) {
            // The same frame's depth copied whole as well, to compare with the blit's texel by texel.
            if (checkCopyBuffer == 0 || GL45.glGetNamedBufferParameteri(checkCopyBuffer, GL15.GL_BUFFER_SIZE) < depthBytes) {
                if (checkCopyBuffer != 0) {
                    GL15.glDeleteBuffers(checkCopyBuffer);
                }
                checkCopyBuffer = GL45.glCreateBuffers();
                GL45.glNamedBufferStorage(checkCopyBuffer, depthBytes, 0);
            }
            GL43.glCopyImageSubData(sourceName, sourceTarget, 0, 0, 0, 0, depthTexture, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, width,
                height, 1);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, checkCopyBuffer);
            GL45.glGetTextureImage(depthTexture, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (int) depthBytes, 0L);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, checkPyramidBuffer);
        long offset = 0;
        for (int level = 1; level < levels; level++) {
            int bytes = sizes[level * 2] * sizes[level * 2 + 1] * 4;
            GL45.glGetTextureImage(pyramid, level - 1, GL30.GL_RED, GL11.GL_FLOAT, bytes, offset);
            offset += bytes;
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
        checkWidth = width;
        checkHeight = height;
        checkLevels = levels;
        checkDue = frame + TIMER_RING;
    }

    /** Compares the copied pyramid with one built on the CPU from the copied depth. */
    private void check() {
        checkDue = -1;
        ByteBuffer depthBytes = ByteBuffer.allocateDirect(checkWidth * checkHeight * 4).order(ByteOrder.nativeOrder());
        GL45.glGetNamedBufferSubData(checkDepthBuffer, 0, depthBytes);
        float[] depth = new float[checkWidth * checkHeight];
        depthBytes.asFloatBuffer().get(depth);
        DepthPyramid reference = new DepthPyramid(depth, checkWidth, checkHeight);
        if (checkCompare) {
            ByteBuffer copiedBytes = ByteBuffer.allocateDirect(checkWidth * checkHeight * 4).order(ByteOrder.nativeOrder());
            GL45.glGetNamedBufferSubData(checkCopyBuffer, 0, copiedBytes);
            long differ = 0;
            String where = null;
            for (int i = 0, n = checkWidth * checkHeight; i < n; i++) {
                int blitted = Float.floatToRawIntBits(depth[i]);
                int copied = copiedBytes.getInt(i * 4);
                if (blitted != copied) {
                    differ++;
                    if (where == null) {
                        where = String.format("at %d, %d: blit %s, copy %s", i % checkWidth, i / checkWidth, depth[i],
                            Float.intBitsToFloat(copied));
                    }
                }
            }
            depthChecks++;
            if (differ == 0) {
                Quadrature.LOGGER.info("Culling depth check {}: the blitted depth is the copied one in all {} texels.", depthChecks,
                    checkWidth * checkHeight);
            } else {
                depthChecksFailed++;
                Quadrature.LOGGER.error("CULLING DEPTH CHECK FAILED ({} of {} checks): {} of {} texels differ; first {}",
                    depthChecksFailed, depthChecks, differ, checkWidth * checkHeight, where);
            }
        }
        long offset = 0;
        long differing = 0;
        long texels = 0;
        String first = null;
        for (int level = 1; level < checkLevels; level++) {
            int w = reference.width(level);
            int h = reference.height(level);
            ByteBuffer levelBytes = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            GL45.glGetNamedBufferSubData(checkPyramidBuffer, offset, levelBytes);
            offset += (long) w * h * 4;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float gpu = levelBytes.getFloat((y * w + x) * 4);
                    float cpu = reference.at(level, x, y);
                    texels++;
                    if (Float.floatToIntBits(gpu) != Float.floatToIntBits(cpu)) {
                        differing++;
                        if (first == null) {
                            first = String.format("level %d at %d, %d: GPU %s, CPU %s", level, x, y, gpu, cpu);
                        }
                    }
                }
            }
        }
        checks++;
        if (differing == 0) {
            Quadrature.LOGGER.info("Culling pyramid check {}: {} texels of {} levels over {} by {} the same as the CPU's.",
                checks, texels, checkLevels - 1, checkWidth, checkHeight);
        } else {
            checksFailed++;
            Quadrature.LOGGER.error("CULLING PYRAMID CHECK FAILED ({} of {} checks): {} of {} texels differ; first: {}",
                checksFailed, checks, differing, texels, first);
        }
    }

    private void readTimes(int timer) {
        if (!timed[timer]) {
            return;
        }
        timed[timer] = false;
        int[] row = timestamps[timer];
        if (GL15.glGetQueryObjecti(row[4], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
            return;
        }
        long start = GL33.glGetQueryObjecti64(row[0], GL15.GL_QUERY_RESULT);
        long end = GL33.glGetQueryObjecti64(row[4], GL15.GL_QUERY_RESULT);
        if (!timedOn[timer]) {
            offNanos += end - start;
            offTimed++;
            return;
        }
        long first = GL33.glGetQueryObjecti64(row[1], GL15.GL_QUERY_RESULT);
        long copied = GL33.glGetQueryObjecti64(row[5], GL15.GL_QUERY_RESULT);
        long built = GL33.glGetQueryObjecti64(row[2], GL15.GL_QUERY_RESULT);
        long tested = GL33.glGetQueryObjecti64(row[3], GL15.GL_QUERY_RESULT);
        onNanos[0] += end - start;
        onNanos[1] += first - start;
        onNanos[2] += built - first;
        onNanos[5] += copied - first;
        onNanos[3] += tested - built;
        onNanos[4] += end - tested;
        onTimed++;
        int method = timedMethod[timer];
        methodTotal[method] += end - start;
        methodTransfer[method] += copied - first;
        methodFrames[method]++;
    }

    private void report() {
        slotRead.clear().limit(pendingFrames * SLOT_BYTES);
        GL45.glGetNamedBufferSubData(staging[pending], 0, slotRead);
        long[] sum = new long[STATS];
        for (int s = 0; s < pendingFrames; s++) {
            for (int i = 0; i < STATS; i++) {
                sum[i] += Integer.toUnsignedLong(slotRead.getInt((s * STATS + i) * 4));
            }
        }
        windows++;
        double n = pendingFrames;
        long allVertices = sum[1] + sum[7];
        long firstVertices = sum[3] + sum[9];
        long secondVertices = sum[5] + sum[11];
        double on = Math.max(1, onTimed);
        String turns = "alternate".equals(MODE) ? String.format("; opaque terrain GPU a frame, culled %.3f ms over %d frames, "
            + "whole %.3f ms over %d frames", onNanos[0] / on / 1e6, onTimed, offNanos / Math.max(1.0, offTimed) / 1e6,
            offTimed) : String.format("; opaque terrain GPU a frame %.3f ms", onNanos[0] / on / 1e6);
        String methods = "";
        if (methodFrames[0] > 0 || methodFrames[1] > 0) {
            methods = String.format("; depth by copy: transfer %.3f ms, opaque terrain %.3f ms over %d frames; by blit: transfer "
                    + "%.3f ms, opaque terrain %.3f ms over %d frames",
                methodTransfer[0] / Math.max(1.0, methodFrames[0]) / 1e6, methodTotal[0] / Math.max(1.0, methodFrames[0]) / 1e6,
                methodFrames[0], methodTransfer[1] / Math.max(1.0, methodFrames[1]) / 1e6,
                methodTotal[1] / Math.max(1.0, methodFrames[1]) / 1e6, methodFrames[1]);
        }
        turns += methods;
        java.util.Arrays.fill(methodTotal, 0);
        java.util.Arrays.fill(methodTransfer, 0);
        java.util.Arrays.fill(methodFrames, 0);
        Quadrature.LOGGER.info(String.format("Culling on the GPU, window %d (%d frames culled): a frame lists %.0f commands "
                + "(%.2f M vertices) of %.0f records; drawn in phase 1 %.1f%% of vertices, in phase 2 %.1f%%, left out %.1f%%; "
                + "records hidden %.1f%%, outside %.1f%%, at the eye %.1f%%; GPU a frame: phase 1 %.3f ms, pyramid %.3f ms (depth %.3f ms), "
                + "test and selection %.3f ms, phase 2 %.3f ms%s",
            windows, pendingFrames, (sum[0] + sum[6]) / n, allVertices / n / 1e6, sum[STAT_RECORDS] / n,
            percent(firstVertices, allVertices), percent(secondVertices, allVertices),
            percent(allVertices - firstVertices - secondVertices, allVertices), percent(sum[STAT_RECORDS + 1], sum[STAT_RECORDS]),
            percent(sum[STAT_RECORDS + 2], sum[STAT_RECORDS]), percent(sum[STAT_RECORDS + 3], sum[STAT_RECORDS]),
            onNanos[1] / on / 1e6, onNanos[2] / on / 1e6, onNanos[5] / on / 1e6, onNanos[3] / on / 1e6, onNanos[4] / on / 1e6, turns));
        java.util.Arrays.fill(onNanos, 0);
        onTimed = 0;
        offNanos = 0;
        offTimed = 0;
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0 : 100.0 * part / whole;
    }

    private boolean programs() {
        if (buildProgram != 0) {
            return true;
        }
        buildProgram = link("build", BUILD);
        testProgram = link("test", TEST);
        selectProgram = link("select", SELECT);
        commitProgram = link("commit", COMMIT);
        if (buildProgram == 0 || testProgram == 0 || selectProgram == 0 || commitProgram == 0) {
            failed = true;
            Quadrature.LOGGER.error("Culling on the GPU is off: a program did not build.");
            return false;
        }
        stats = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(stats, (long) WINDOW * SLOT_BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
        for (int i = 0; i < staging.length; i++) {
            staging[i] = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(staging[i], (long) WINDOW * SLOT_BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
        }
        for (int[] row : timestamps) {
            for (int i = 0; i < row.length; i++) {
                row[i] = GL15.glGenQueries();
            }
        }
        Quadrature.LOGGER.info("Culling on the GPU is on ({}).", MODE);
        return true;
    }

    private static void bind(int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    private void save() {
        savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        savedActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
        savedTextures[0] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + PYRAMID_UNIT);
        savedTextures[1] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(savedActive);
        for (int unit = 0; unit < savedImage.length; unit++) {
            savedImage[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME, unit);
            savedImageLevel[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL, unit);
            savedImageLayered[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED, unit) != 0;
            savedImageLayer[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER, unit);
            savedImageAccess[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_ACCESS, unit);
            savedImageFormat[unit] = GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT, unit);
        }
        for (int point = 0; point < savedStorage.length; point++) {
            savedStorage[point] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, point);
            savedStorageStart[point] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, point);
            savedStorageSize[point] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, point);
        }
        savedGeneralStorage = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
    }

    private void restore() {
        GL20.glUseProgram(savedProgram);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + DEPTH_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTextures[0]);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + PYRAMID_UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTextures[1]);
        GL13.glActiveTexture(savedActive);
        for (int unit = 0; unit < savedImage.length; unit++) {
            GL42.glBindImageTexture(unit, savedImage[unit], savedImageLevel[unit], savedImageLayered[unit],
                savedImageLayer[unit], savedImageAccess[unit], savedImageFormat[unit] == 0 ? GL30.GL_R32F : savedImageFormat[unit]);
        }
        for (int point = 0; point < savedStorage.length; point++) {
            if (savedStorage[point] != 0 && savedStorageSize[point] != 0) {
                GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, point, savedStorage[point], savedStorageStart[point],
                    savedStorageSize[point]);
            } else {
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, point, savedStorage[point]);
            }
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, savedGeneralStorage);
    }

    private static int link(String name, String source) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            Quadrature.LOGGER.error("Culling program {} did not compile: {}", name, GL20.glGetShaderInfoLog(shader, 8192));
            GL20.glDeleteShader(shader);
            return 0;
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDetachShader(program, shader);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            Quadrature.LOGGER.error("Culling program {} did not link: {}", name, GL20.glGetProgramInfoLog(program, 8192));
            GL20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }
}
