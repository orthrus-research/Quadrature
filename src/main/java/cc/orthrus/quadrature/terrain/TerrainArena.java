package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.terrain.ArenaAllocator;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * The memory terrain meshes lie in: pages of one size, each an immutable buffer shared by the three
 * layers. A mesh takes one block, its layers one after another.
 *
 * <p>A page is 64 MiB, to the nearest granule. That is several hundred times an average mesh, so a
 * mesh always fits and little is lost at a page's end; it is small enough that memory is taken in
 * steps of a twenty-fourth of the near-terrain budget; and it keeps the pages few, which is how many
 * draws a layer needs, a page being drawn in one call.
 *
 * <p>A granule is 146 vertices, 4,088 bytes: sizes step by four kibibytes, rounded down so that every
 * block starts on a whole vertex of its page.
 *
 * <p>A page is not mapped: a mesh is written into it through the driver. The driver keeps storage that
 * stays mapped for writing in system memory and draws from it there, which was measured at several
 * times the cost of drawing from video memory; a page that is never mapped it keeps in video memory.
 * Mapped pages remain behind {@code quadrature.terrain.pages=mapped}, for measuring.
 *
 * <p>A freed block is not reused until a fence placed after that frame's draws has signalled. Mapped
 * pages need that, since writing to mapped memory is not ordered against draws already issued, and a
 * fence is exact where a count of frames would only be a guess at how far behind the driver may run.
 */final class TerrainArena {

    static final int GRANULE_BYTES = 146 * CubeMesh.STRIDE;
    static final long PAGE_BYTES = 16_416L * GRANULE_BYTES;
    /** 24 pages are the near-terrain budget of 1.5 GiB. */
    static final int MAX_PAGES = 24;

    private static final int ACCESS = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;

    /** Whether pages are written through the driver and never mapped: so unless mapped pages are asked for. */
    static final boolean UPLOAD = !"mapped".equals(System.getProperty("quadrature.terrain.pages"));

    /**
     * A diagnostic, off unless {@code quadrature.terrain.blocks=classes}: a freed block serves only a
     * mesh of its own size and is neither split nor joined, to set beside joining.
     */
    static final boolean JOINING = !"classes".equals(System.getProperty("quadrature.terrain.blocks"));

    /**
     * A diagnostic, off unless {@code quadrature.terrain.write=copy}: a frame's meshes are staged in one
     * buffer and copied into their pages on the GPU, to set beside writing each into its page.
     */
    static final boolean COPY = "copy".equals(System.getProperty("quadrature.terrain.write"));

    /** A diagnostic, off unless {@code quadrature.terrain.touch=true}: a page is written once when it is made. */
    static final boolean TOUCH = Boolean.getBoolean("quadrature.terrain.touch");

    private static final long SLOW_WRITE_NANOS = 500_000L;
    private static final int SLOW_WRITE_LINES = 400;

    /** The granule of pages that hold face records: 128 records of 32 bytes. */
    static final int RECORD_GRANULE_BYTES = 4096;
    static final long RECORD_PAGE_BYTES = 16_384L * RECORD_GRANULE_BYTES;

    private final ArenaAllocator allocator;
    private final long pageBytes;
    private final int stride;

    /** An arena of pages for the game's vertices. */
    TerrainArena() {
        this(PAGE_BYTES, GRANULE_BYTES);
    }

    /** An arena of pages of another size and granule, as for face records. */
    TerrainArena(long pageBytes, int granuleBytes) {
        this(pageBytes, granuleBytes, CubeMesh.STRIDE);
    }

    /** The granule of pages that hold vertices followed by their quad's header: 114 vertices of 36 bytes. */
    static final int WIDE_GRANULE_BYTES = 114 * 36;
    static final long WIDE_PAGE_BYTES = 16_352L * WIDE_GRANULE_BYTES;

    /** An arena whose vertices are of another size than the game's. */
    TerrainArena(long pageBytes, int granuleBytes, int stride) {
        this.pageBytes = pageBytes;
        this.stride = stride;
        allocator = new ArenaAllocator(pageBytes, granuleBytes, MAX_PAGES, JOINING);
    }

    /** The bytes of a vertex in this arena's pages. */
    int stride() {
        return stride;
    }

    /**
     * Finds room for a number of bytes and writes them there, through the driver.
     *
     * @param data  what to write, from its position
     * @return the block, to free and to ask {@link #pageOf} and {@link #offsetOf} about, or -1 when there is no room
     */
    long placeBytes(java.nio.ByteBuffer data, int bytes, long frame) {
        long block = allocator.allocate(bytes);
        if (block < 0) {
            return -1;
        }
        while (pages < allocator.pages()) {
            create();
            pagesInTheWay++;
        }
        int page = ArenaAllocator.page(block);
        java.nio.ByteBuffer part = data.duplicate();
        part.limit(data.position() + bytes);
        long start = System.nanoTime();
        GL45.glNamedBufferSubData(buffers[page], allocator.offset(block), part);
        slow(System.nanoTime() - start, page, (int) allocator.offset(block), bytes, frame);
        written[page] = true;
        return block;
    }

    int pageOf(long block) {
        return ArenaAllocator.page(block);
    }

    long offsetOf(long block) {
        return allocator.offset(block);
    }

    /** Gives back a block got from {@link #placeBytes}. */
    void freeBytes(long block, int bytes, long frame) {
        allocator.free(block, bytes, frame);
    }
    private final boolean[] written = new boolean[MAX_PAGES];
    private final long[] drawnFrame = new long[MAX_PAGES];
    private int slowWrites;
    private long lastAllocateNanos;
    private long lastWriteNanos;
    private int staging;
    private ByteBuffer staged = ByteBuffer.allocateDirect(1 << 20);
    private int copies;
    private int[] copyPage = new int[64];
    private int[] copyFrom = new int[64];
    private int[] copyTo = new int[64];
    private int[] copyBytes = new int[64];
    private final int[] buffers = new int[MAX_PAGES];
    private final ByteBuffer[] memory = new ByteBuffer[MAX_PAGES];
    private int pages;
    /** A page is made ahead when less than this is left of space never handed out: a few frames' meshes. */
    private static final long AHEAD_BYTES = 8L << 20;
    private int pagesAhead;
    private int pagesInTheWay;
    private long createNanos;
    private long createNanosThisFrame;
    private long mostCreateNanos;
    /** Fences not yet signalled, oldest first: the sync object and the frame it closed. */
    private final ArrayDeque<long[]> fences = new ArrayDeque<>();

    /**
     * Copies what the mesher last built into the arena.
     *
     * @return the mesh, or null when the arena has no room for it
     */
    CubeMesh place(int x, int y, int z, CubeMesher mesher, long frame) {
        for (int layer = 0; layer < CubeMesh.LAYERS; layer++) {
            layerData[layer] = mesher.count(layer) == 0 ? null : mesher.data(layer);
            layerCounts[layer] = mesher.count(layer);
        }
        return place(x, y, z, layerData, layerCounts, frame);
    }

    private final ByteBuffer[] layerData = new ByteBuffer[CubeMesh.LAYERS];
    private final int[] layerCounts = new int[CubeMesh.LAYERS];

    /**
     * Copies three layers of vertices into the arena.
     *
     * @param data   each layer's vertices from its buffer's position, or null where the layer has none
     * @param counts how many vertices each layer has
     * @return the mesh, or null when the arena has no room for it
     */
    CubeMesh place(int x, int y, int z, ByteBuffer[] data, int[] counts, long frame) {
        int bytes = 0;
        for (int layer = 0; layer < CubeMesh.LAYERS; layer++) {
            bytes += counts[layer] * stride;
        }
        long began = System.nanoTime();
        long block = allocator.allocate(bytes);
        lastAllocateNanos = System.nanoTime() - began;
        lastWriteNanos = 0;
        if (block < 0) {
            return null;
        }
        while (pages < allocator.pages()) {
            // The allocator opened a page that was not made ahead: it is made here, in the way of a mesh.
            create();
            pagesInTheWay++;
        }
        int page = ArenaAllocator.page(block);
        int at = (int) allocator.offset(block);
        CubeMesh mesh = new CubeMesh(x, y, z);
        mesh.block = block;
        mesh.blockBytes = bytes;
        for (int layer = 0; layer < CubeMesh.LAYERS; layer++) {
            int count = counts[layer];
            if (count == 0) {
                continue;
            }
            ByteBuffer source = data[layer];
            if (UPLOAD && COPY) {
                stage(page, at, source, count * stride);
            } else if (UPLOAD) {
                ByteBuffer part = source.duplicate();
                part.limit(source.position() + count * stride);
                long start = System.nanoTime();
                GL45.glNamedBufferSubData(buffers[page], at, part);
                long wrote = System.nanoTime() - start;
                lastWriteNanos += wrote;
                slow(wrote, page, at, count * stride, frame);
                written[page] = true;
            } else {
                memory[page].put(at, source, source.position(), count * stride);
            }
            mesh.place(layer, buffers[page], at / stride, count);
            at += count * stride;
        }
        return mesh;
    }

    /** The time the last placement spent finding room. */
    long lastAllocateNanos() {
        return lastAllocateNanos;
    }

    /** The time the last placement spent in the driver's writes. */
    long lastWriteNanos() {
        return lastWriteNanos;
    }

    /** Logs a write the driver took long over, with what is known of the page it went to. */
    private void slow(long nanos, int page, int at, int bytes, long frame) {
        if (nanos >= SLOW_WRITE_NANOS && slowWrites++ < SLOW_WRITE_LINES) {
            Quadrature.LOGGER.info("Terrain arena: slow write, {} ms: page {}, offset {}, {} bytes, {}, {}, frame {}",
                String.format("%.3f", nanos / 1e6), page, at, bytes,
                written[page] ? "page written before" : "first write into the page",
                drawnFrame[page] == frame - 1 ? "page drawn from in the frame before"
                    : drawnFrame[page] == 0 ? "page never drawn from" : "page not drawn from in the frame before",
                frame);
        }
    }

    /** Notes that a page is drawn from in a frame, for the account of slow writes. */
    void drawn(int page, long frame) {
        drawnFrame[page] = frame;
    }

    /** Keeps a layer's bytes to be copied into its page when the frame's meshes are all built. */
    private void stage(int page, int at, ByteBuffer data, int bytes) {
        if (staged.remaining() < bytes) {
            ByteBuffer larger = ByteBuffer.allocateDirect(Math.max(staged.capacity() * 2, staged.position() + bytes));
            staged.flip();
            larger.put(staged);
            staged = larger;
        }
        int from = staged.position();
        staged.put(from, data, data.position(), bytes);
        staged.position(from + bytes);
        if (copies == copyPage.length) {
            copyPage = java.util.Arrays.copyOf(copyPage, copies * 2);
            copyFrom = java.util.Arrays.copyOf(copyFrom, copies * 2);
            copyTo = java.util.Arrays.copyOf(copyTo, copies * 2);
            copyBytes = java.util.Arrays.copyOf(copyBytes, copies * 2);
        }
        copyPage[copies] = page;
        copyFrom[copies] = from;
        copyTo[copies] = at;
        copyBytes[copies++] = bytes;
    }

    /**
     * Sends what was staged this frame: the bytes to a buffer whose storage is replaced each frame, so the
     * driver need not wait for anything that reads the last frame's, then one copy for each layer from
     * that buffer into its page. A copy runs in order with the draws, on the GPU. Call after the frame's
     * meshes are built and before anything is drawn.
     */
    void flush() {
        if (copies == 0) {
            return;
        }
        if (staging == 0) {
            staging = GL45.glCreateBuffers();
        }
        staged.flip();
        GL45.glNamedBufferData(staging, staged, GL15.GL_STREAM_DRAW);
        for (int i = 0; i < copies; i++) {
            GL45.glCopyNamedBufferSubData(staging, buffers[copyPage[i]], copyFrom[i], copyTo[i], copyBytes[i]);
        }
        staged.clear();
        copies = 0;
    }

    /** Makes the next page's buffer. The driver's time for it is kept apart from placing meshes. */
    private void create() {
        long start = System.nanoTime();
        buffers[pages] = GL45.glCreateBuffers();
        if (UPLOAD) {
            GL45.glNamedBufferStorage(buffers[pages], pageBytes, GL44.GL_DYNAMIC_STORAGE_BIT);
        } else {
            GL45.glNamedBufferStorage(buffers[pages], pageBytes, ACCESS);
            memory[pages] = GL45.glMapNamedBufferRange(buffers[pages], 0, pageBytes, ACCESS);
        }
        if (TOUCH && UPLOAD) {
            // Writes the whole page once, so that whatever the driver puts off until a page is first
            // written happens here and not when a mesh is placed.
            GL45.glClearNamedBufferData(buffers[pages], GL30.GL_R8, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
            written[pages] = true;
        }
        long nanos = System.nanoTime() - start;
        createNanos += nanos;
        createNanosThisFrame += nanos;
        mostCreateNanos = Math.max(mostCreateNanos, nanos);
        Quadrature.LOGGER.info("Terrain arena: page {} is buffer {}, {}, made in {} ms", pages, buffers[pages],
            UPLOAD ? "written through the driver" : "mapped", String.format("%.3f", nanos / 1e6));
        pages++;
    }

    /**
     * Makes a page ahead of need, when the space never handed out has run low and none is waiting. Called
     * once a frame after the terrain is drawn, so that making a page is not in the way of placing a mesh.
     */
    void prepare() {
        if (pages == allocator.pages() && pages < MAX_PAGES && allocator.mayGrow()
            && allocator.largestRoomBytes() < AHEAD_BYTES) {
            create();
            pagesAhead++;
        }
    }

    /** The time spent making pages since this was last called. */
    long takeCreateNanos() {
        long nanos = createNanosThisFrame;
        createNanosThisFrame = 0;
        return nanos;
    }

    int pagesAhead() {
        return pagesAhead;
    }

    int pagesInTheWay() {
        return pagesInTheWay;
    }

    double createMillisEach() {
        return pages == 0 ? 0.0 : createNanos / 1e6 / pages;
    }

    double createMillisMost() {
        return mostCreateNanos / 1e6;
    }

    /** Gives a mesh's block back; it is reused once the frame it was freed in has been drawn. */
    void free(CubeMesh mesh, long frame) {
        allocator.free(mesh.block, mesh.blockBytes, frame);
        mesh.block = -1;
    }

    /** Releases what was freed in frames the driver has finished. Call before a frame's meshing. */
    void beginFrame() {
        while (!fences.isEmpty()) {
            long[] fence = fences.peek();
            int state = GL32.glClientWaitSync(fence[0], 0, 0);
            if (state != GL32.GL_ALREADY_SIGNALED && state != GL32.GL_CONDITION_SATISFIED) {
                break;
            }
            GL32.glDeleteSync(fence[0]);
            allocator.release(fence[1]);
            fences.poll();
        }
    }

    /** Marks the end of a frame's draws. */
    void endFrame(long frame) {
        fences.add(new long[] {GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0), frame});
    }

    /** The buffer of a page. */
    int buffer(int page) {
        return buffers[page];
    }

    int pages() {
        return pages;
    }

    long capacityBytes() {
        return pages * pageBytes;
    }

    long usedBytes() {
        return allocator.usedBytes();
    }

    long wastedBytes() {
        return allocator.wastedBytes();
    }

    long pendingBytes() {
        return allocator.pendingBytes();
    }

    long reusableBytes() {
        return allocator.reusableBytes();
    }

    /** The largest mesh that fits without a new page. */
    long largestRoomBytes() {
        return allocator.largestRoomBytes();
    }

    int freeRanges() {
        return allocator.freeRanges();
    }

    /** The pages the allocator has handed out from; one more may have been made ahead. */
    int pagesInUse() {
        return allocator.pages();
    }

    int openFences() {
        return fences.size();
    }
}
