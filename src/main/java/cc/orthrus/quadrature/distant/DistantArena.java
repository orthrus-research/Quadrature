package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.lod.BuiltTile;
import cc.orthrus.quadrature.core.terrain.ArenaAllocator;

import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * The memory distant tiles lie in: a second arena, apart from near terrain's, of the same kind.
 * Pages of 64 MiB, each an immutable buffer written through the driver and never mapped, since the
 * driver keeps storage that stays mapped in system memory and draws from it several times slower
 * (measured for near terrain). A tile takes one block: its face records, then its tints.
 *
 * <p>Eight pages are the cap, 512 MiB. A block is a whole number of 4 KiB granules, so it starts on
 * a whole face record and a whole 32-bit word of its page.
 *
 * <p>A freed block is not reused until a fence placed after that frame's draws has signalled.
 */
final class DistantArena {

    static final int GRANULE_BYTES = 4096;
    static final long PAGE_BYTES = 64L << 20;
    static final int MAX_PAGES = 8;

    private final ArenaAllocator allocator = new ArenaAllocator(PAGE_BYTES, GRANULE_BYTES, MAX_PAGES);
    private final int[] buffers = new int[MAX_PAGES];
    private int pages;
    /** Fences not yet signalled, oldest first: the sync object and the frame it closed. */
    private final ArrayDeque<long[]> fences = new ArrayDeque<>();
    private long createNanos;

    /**
     * Copies a built tile into the arena.
     *
     * @return its block, or -1 when the arena has no room for it
     */
    long place(BuiltTile tile) {
        long block = allocator.allocate(tile.bytes());
        if (block < 0) {
            return -1;
        }
        while (pages < allocator.pages()) {
            create();
        }
        ByteBuffer data = tile.data().duplicate();
        GL45.glNamedBufferSubData(buffers[ArenaAllocator.page(block)], allocator.offset(block), data);
        return block;
    }

    private void create() {
        long start = System.nanoTime();
        buffers[pages] = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(buffers[pages], PAGE_BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
        long nanos = System.nanoTime() - start;
        createNanos += nanos;
        Quadrature.LOGGER.info("Distant arena: page {} is buffer {}, made in {} ms", pages, buffers[pages],
            String.format("%.3f", nanos / 1e6));
        pages++;
    }

    /** Gives a block back; it is reused once the frame it was freed in has been drawn. */
    void free(long block, int bytes, long frame) {
        allocator.free(block, bytes, frame);
    }

    /** Releases what was freed in frames the driver has finished. Call before a frame places tiles. */
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

    /** The page a block lies in. */
    static int page(long block) {
        return ArenaAllocator.page(block);
    }

    /** Where a block starts in its page, in bytes. */
    long offset(long block) {
        return allocator.offset(block);
    }

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

    long pendingBytes() {
        return allocator.pendingBytes();
    }

    double createMillis() {
        return createNanos / 1e6;
    }
}
