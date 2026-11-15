package cc.orthrus.quadrature.core.terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Hands out ranges of a set of equal pages of memory, and takes them back. It holds no memory of its
 * own: whoever owns the pages asks where a mesh may go.
 *
 * <p>A range is a whole number of granules. A request is met from the smallest free range that holds
 * it, the rest of that range staying free, else from a new page until the limit of pages is reached.
 * A range given back joins the free ranges beside it in its page, so that what was freed in pieces can
 * serve a larger request. Nothing is moved.
 *
 * <p>For measuring, an allocator can instead be made that neither splits nor joins: a request is then
 * met from a freed range of exactly its size, else from the end of a page never handed out, and a
 * freed range serves only its own size again.
 *
 * <p>A freed range is not reusable at once. Draws issued before it was freed may not have run, and
 * writing over what they read would show in the frame. Each free names the frame it happened in, and
 * {@link #release} makes reusable everything freed up to a frame known to have finished.
 */
public final class ArenaAllocator {

    private static final int PAGE_SHIFT = 48;
    private static final int OFFSET_SHIFT = 24;
    private static final long FIELD = (1L << 24) - 1;
    private static final int SIZE_SHIFT = 40;

    private final int granuleBytes;
    private final int pageGranules;
    private final int maxPages;
    /** How many granules of each page have ever been handed out; the rest is untouched. */
    private int[] touched = new int[4];
    private int pages;
    /** For each class, counted in granules, the freed ranges that may be used again. */
    private long[][] free = new long[64][];
    private int[] freeCount = new int[64];
    /** Freed ranges that draws may still read, oldest first, with the frame each was freed in. */
    private final ArrayDeque<long[]> pending = new ArrayDeque<>();
    private long usedBytes;
    private long requestedBytes;
    private long pendingBytes;
    private long reusableBytes;
    /** Whether free ranges are split and joined. */
    private final boolean joining;
    /** With joining: for each page, its free ranges by where they start, each with its length in granules. */
    private final ArrayList<TreeMap<Integer, Integer>> freeByStart = new ArrayList<>();
    /** With joining: every free range, ordered by length, then page, then start. */
    private final TreeSet<Long> freeBySize = new TreeSet<>();

    /**
     * An allocator that splits and joins free ranges.
     *
     * @param pageBytes    the size of every page; a whole number of granules
     * @param granuleBytes the step between sizes
     * @param maxPages     how many pages may exist
     */
    public ArenaAllocator(long pageBytes, int granuleBytes, int maxPages) {
        this(pageBytes, granuleBytes, maxPages, true);
    }

    /**
     * @param joining whether free ranges are split to fit a request and joined when given back
     */
    public ArenaAllocator(long pageBytes, int granuleBytes, int maxPages, boolean joining) {
        this.joining = joining;
        if (granuleBytes <= 0 || pageBytes <= 0 || pageBytes % granuleBytes != 0) {
            throw new IllegalArgumentException("A page is a whole number of granules");
        }
        if (pageBytes / granuleBytes > FIELD || maxPages <= 0 || maxPages > 0x7FFF) {
            throw new IllegalArgumentException("Too many granules to a page, or pages");
        }
        this.granuleBytes = granuleBytes;
        this.pageGranules = (int) (pageBytes / granuleBytes);
        this.maxPages = maxPages;
    }

    /**
     * @return a block to pass to {@link #page}, {@link #offset} and {@link #free}, or -1 when the
     *         request is larger than a page or no page has room and no more may be added
     */
    public long allocate(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("Nothing to allocate");
        }
        int granules = (bytes + granuleBytes - 1) / granuleBytes;
        if (granules > pageGranules) {
            return -1;
        }
        long block = -1;
        if (joining) {
            block = take(granules);
            if (block < 0) {
                if (pages == maxPages) {
                    return -1;
                }
                freeByStart.add(new TreeMap<>());
                insert(pages++, 0, pageGranules);
                reusableBytes += pageBytes();
                block = take(granules);
            }
            reusableBytes -= (long) granules * granuleBytes;
        } else if (granules < free.length && freeCount[granules] > 0) {
            block = free[granules][--freeCount[granules]];
            reusableBytes -= (long) granules * granuleBytes;
        } else {
            for (int page = 0; page < pages && block < 0; page++) {
                if (pageGranules - touched[page] >= granules) {
                    block = block(page, touched[page], granules);
                    touched[page] += granules;
                }
            }
            if (block < 0) {
                if (pages == maxPages) {
                    return -1;
                }
                if (pages == touched.length) {
                    touched = Arrays.copyOf(touched, pages * 2);
                }
                touched[pages] = granules;
                block = block(pages++, 0, granules);
            }
        }
        usedBytes += (long) granules * granuleBytes;
        requestedBytes += bytes;
        return block;
    }

    /**
     * Gives a block back. It stays out of use until {@link #release} is told a frame at or after
     * {@code frame} has finished.
     *
     * @param bytes the size the block was asked for with
     */
    public void free(long block, int bytes, long frame) {
        long size = (long) granules(block) * granuleBytes;
        usedBytes -= size;
        requestedBytes -= bytes;
        pendingBytes += size;
        pending.add(new long[] {frame, block});
    }

    /** Makes every block freed in a frame up to {@code finishedFrame} available again. */
    public void release(long finishedFrame) {
        while (!pending.isEmpty() && pending.peek()[0] <= finishedFrame) {
            long block = pending.poll()[1];
            int granules = granules(block);
            if (joining) {
                join(page(block), (int) (block >>> OFFSET_SHIFT & FIELD), granules);
                long size = (long) granules * granuleBytes;
                pendingBytes -= size;
                reusableBytes += size;
                continue;
            }
            if (granules >= free.length) {
                int size = Math.max(granules + 1, free.length * 2);
                free = Arrays.copyOf(free, size);
                freeCount = Arrays.copyOf(freeCount, size);
            }
            if (free[granules] == null) {
                free[granules] = new long[8];
            } else if (freeCount[granules] == free[granules].length) {
                free[granules] = Arrays.copyOf(free[granules], freeCount[granules] * 2);
            }
            free[granules][freeCount[granules]++] = block;
            long size = (long) granules * granuleBytes;
            pendingBytes -= size;
            reusableBytes += size;
        }
    }

    /** Takes the smallest free range that holds a request, and leaves what is over free. */
    private long take(int granules) {
        Long found = freeBySize.ceiling((long) granules << SIZE_SHIFT);
        if (found == null) {
            return -1;
        }
        int length = (int) (found >>> SIZE_SHIFT);
        int page = (int) (found >>> OFFSET_SHIFT & 0xFFFF);
        int start = (int) (found & FIELD);
        freeBySize.remove(found);
        freeByStart.get(page).remove(start);
        if (length > granules) {
            insert(page, start + granules, length - granules);
        }
        return block(page, start, granules);
    }

    /** Makes a range free, joined to the free ranges that touch it. */
    private void join(int page, int start, int length) {
        TreeMap<Integer, Integer> ranges = freeByStart.get(page);
        Map.Entry<Integer, Integer> before = ranges.lowerEntry(start);
        if (before != null && before.getKey() + before.getValue() == start) {
            remove(page, before.getKey(), before.getValue());
            start = before.getKey();
            length += before.getValue();
        }
        Integer after = ranges.get(start + length);
        if (after != null) {
            remove(page, start + length, after);
            length += after;
        }
        insert(page, start, length);
    }

    private void insert(int page, int start, int length) {
        freeByStart.get(page).put(start, length);
        freeBySize.add((long) length << SIZE_SHIFT | (long) page << OFFSET_SHIFT | start);
    }

    private void remove(int page, int start, int length) {
        freeByStart.get(page).remove(start);
        freeBySize.remove((long) length << SIZE_SHIFT | (long) page << OFFSET_SHIFT | start);
    }

    private static long block(int page, int offsetGranules, int granules) {
        return (long) page << PAGE_SHIFT | (long) offsetGranules << OFFSET_SHIFT | granules;
    }

    private static int granules(long block) {
        return (int) (block & FIELD);
    }

    /** The page a block lies in, counted from 0. */
    public static int page(long block) {
        return (int) (block >>> PAGE_SHIFT);
    }

    /** Where in its page a block starts, in bytes. */
    public long offset(long block) {
        return (block >>> OFFSET_SHIFT & FIELD) * granuleBytes;
    }

    /** The bytes a block occupies: its request rounded up to its class. */
    public long size(long block) {
        return (long) granules(block) * granuleBytes;
    }

    /**
     * The largest request that can be met without a new page: the largest free range, or without
     * joining the most space never handed out in a page. A caller that makes its pages ready ahead of
     * need adds one when this runs low.
     */
    public long largestRoomBytes() {
        if (joining) {
            return freeBySize.isEmpty() ? 0 : (freeBySize.last() >>> SIZE_SHIFT) * granuleBytes;
        }
        int most = 0;
        for (int page = 0; page < pages; page++) {
            most = Math.max(most, pageGranules - touched[page]);
        }
        return (long) most * granuleBytes;
    }

    /** With joining, how many free ranges there are: the fewer for the same free memory, the less it is scattered. */
    public int freeRanges() {
        return freeBySize.size();
    }

    /** Whether another page may be added. */
    public boolean mayGrow() {
        return pages < maxPages;
    }

    /** How many pages have been handed out from; a caller creates a page when this grows. */
    public int pages() {
        return pages;
    }

    public long pageBytes() {
        return (long) pageGranules * granuleBytes;
    }

    /** All the memory of the pages there are. */
    public long capacityBytes() {
        return pages * pageBytes();
    }

    /** The memory blocks in use occupy. */
    public long usedBytes() {
        return usedBytes;
    }

    /** The part of {@link #usedBytes} nothing asked for: what rounding sizes up to classes costs. */
    public long wastedBytes() {
        return usedBytes - requestedBytes;
    }

    /** Freed memory that draws may still read. */
    public long pendingBytes() {
        return pendingBytes;
    }

    /**
     * Memory that may be handed out: every free range, space never handed out included. Without joining,
     * only freed ranges, each of which serves its own size alone.
     */
    public long reusableBytes() {
        return reusableBytes;
    }
}
