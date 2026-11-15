package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.terrain.ArenaAllocator;

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

    private final ArenaAllocator allocator = new ArenaAllocator(PAGE_BYTES, GRANULE_BYTES, MAX_PAGES);
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
    CubeMesh place(int x, int y, int z, CubeMesher mesher) {
        int bytes = 0;
        for (int layer = 0; layer < CubeMesh.LAYERS; layer++) {
            bytes += mesher.count(layer) * CubeMesh.STRIDE;
        }
        long block = allocator.allocate(bytes);
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
            int count = mesher.count(layer);
            if (count == 0) {
                continue;
            }
            ByteBuffer data = mesher.data(layer);
            if (UPLOAD) {
                ByteBuffer part = data.duplicate();
                part.limit(data.position() + count * CubeMesh.STRIDE);
                GL45.glNamedBufferSubData(buffers[page], at, part);
            } else {
                memory[page].put(at, data, data.position(), count * CubeMesh.STRIDE);
            }
            mesh.place(layer, buffers[page], at / CubeMesh.STRIDE, count);
            at += count * CubeMesh.STRIDE;
        }
        return mesh;
    }

    /** Makes the next page's buffer. The driver's time for it is kept apart from placing meshes. */
    private void create() {
        long start = System.nanoTime();
        buffers[pages] = GL45.glCreateBuffers();
        if (UPLOAD) {
            GL45.glNamedBufferStorage(buffers[pages], PAGE_BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
        } else {
            GL45.glNamedBufferStorage(buffers[pages], PAGE_BYTES, ACCESS);
            memory[pages] = GL45.glMapNamedBufferRange(buffers[pages], 0, PAGE_BYTES, ACCESS);
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
        return pages * PAGE_BYTES;
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

    int openFences() {
        return fences.size();
    }
}
