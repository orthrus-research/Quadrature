package cc.orthrus.quadrature.core.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * What one view draws in one frame, written out for indirect drawing: a record for every cube drawn,
 * holding its matrix, and for every layer and every page of the arena a run of commands, each naming
 * a range of vertices in that page and the record to draw it with.
 *
 * <p>A command is the four numbers an indirect draw of arrays reads: vertex count, instance count,
 * first vertex, base instance. The base instance is the record's index. A record is two matrices: the
 * cube's, and room for the one it had the frame before, which nothing fills yet.
 *
 * <p>Filled in three steps: {@link #begin}, then {@link #record} and {@link #add} for each cube in the
 * order it should be drawn, then {@link #finish}, after which the buffers and the runs may be read.
 * One instance serves one view and allocates only when a frame needs more than any before it.
 *
 * <p>For culling on the GPU each record may carry a tag, a number that names its cube from frame to frame
 * ({@link #NO_TAG} where none was given), and the list also gives, after {@link #finish}, each command's run
 * and each run's first command, so that a compute stage can sort commands into runs of its own.
 */
public final class DrawCommands {

    /** The bytes one command takes. */
    public static final int COMMAND_BYTES = 16;
    /** The bytes one record takes. */
    public static final int RECORD_BYTES = 128;

    /** The tag of a record given none. */
    public static final int NO_TAG = -1;

    private static final int LAYERS = CubeSlots.LAYERS;

    private int pages;
    private int records;
    private int entries;
    // What was added, in the order it was added: layer and page, then first, count and record.
    private int[] group = new int[1024];
    private int[] first = new int[1024];
    private int[] count = new int[1024];
    private int[] record = new int[1024];
    private int[] runStart = new int[LAYERS * 4];
    private int[] runCount = new int[LAYERS * 4];
    private int[] cursor = new int[LAYERS * 4];
    private ByteBuffer commandBytes = buffer(1024 * COMMAND_BYTES);
    private ByteBuffer recordBytes = buffer(512 * RECORD_BYTES);
    private int[] tags = new int[512];
    private ByteBuffer tagBytes = buffer(512 * 4);
    private ByteBuffer runBytes = buffer(1024 * 4);
    private ByteBuffer startBytes = buffer(LAYERS * 4 * 4);

    private static ByteBuffer buffer(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    /** Starts a frame's list over an arena of {@code pages} pages. */
    public void begin(int pages) {
        this.pages = pages;
        records = 0;
        entries = 0;
        // The last frame's bounds no longer apply.
        commandBytes.clear();
        recordBytes.clear();
        if (runStart.length < LAYERS * pages) {
            runStart = new int[LAYERS * pages];
            runCount = new int[LAYERS * pages];
            cursor = new int[LAYERS * pages];
        }
        Arrays.fill(runCount, 0, LAYERS * pages, 0);
    }

    /**
     * Adds a cube's record.
     *
     * @param matrix the cube's matrix, column-major, from {@code at}
     * @return the record's index, to pass to {@link #add}
     */
    public int record(float[] matrix, int at) {
        return record(matrix, at, NO_TAG);
    }

    /**
     * Adds a cube's record with a tag.
     *
     * @param tag a number naming the cube from frame to frame, or {@link #NO_TAG}
     * @return the record's index, to pass to {@link #add}
     */
    public int record(float[] matrix, int at, int tag) {
        if (records == tags.length) {
            tags = Arrays.copyOf(tags, records * 2);
        }
        tags[records] = tag;
        if ((records + 1) * RECORD_BYTES > recordBytes.capacity()) {
            ByteBuffer larger = buffer(recordBytes.capacity() * 2);
            recordBytes.position(0).limit(records * RECORD_BYTES);
            larger.put(recordBytes);
            recordBytes = larger;
        }
        int offset = records * RECORD_BYTES;
        for (int i = 0; i < CubeMatrix.LENGTH; i++) {
            recordBytes.putFloat(offset + i * 4, matrix[at + i]);
        }
        return records++;
    }

    /** Adds one command: {@code count} vertices from {@code first} of a page, drawn in a layer with a record. */
    public void add(int layer, int page, int first, int count, int record) {
        if (entries == group.length) {
            int size = entries * 2;
            group = Arrays.copyOf(group, size);
            this.first = Arrays.copyOf(this.first, size);
            this.count = Arrays.copyOf(this.count, size);
            this.record = Arrays.copyOf(this.record, size);
        }
        int index = layer * pages + page;
        group[entries] = index;
        this.first[entries] = first;
        this.count[entries] = count;
        this.record[entries] = record;
        runCount[index]++;
        entries++;
    }

    /** Lays the commands out: each layer's and page's as one run, in the order they were added. */
    public void finish() {
        if (entries * COMMAND_BYTES > commandBytes.capacity()) {
            commandBytes = buffer(Math.max(entries * COMMAND_BYTES, commandBytes.capacity() * 2));
        }
        int next = 0;
        for (int index = 0; index < LAYERS * pages; index++) {
            runStart[index] = next;
            cursor[index] = next;
            next += runCount[index];
        }
        for (int i = 0; i < entries; i++) {
            int offset = cursor[group[i]]++ * COMMAND_BYTES;
            commandBytes.putInt(offset, count[i]);
            commandBytes.putInt(offset + 4, 1);
            commandBytes.putInt(offset + 8, first[i]);
            commandBytes.putInt(offset + 12, record[i]);
        }
        commandBytes.position(0).limit(entries * COMMAND_BYTES);
        recordBytes.position(0).limit(records * RECORD_BYTES);
        // Absolute writes are bounded by the limit, which the last frame left at its own size.
        tagBytes.clear();
        runBytes.clear();
        startBytes.clear();
        if (records * 4 > tagBytes.capacity()) {
            tagBytes = buffer(Math.max(records * 4, tagBytes.capacity() * 2));
        }
        for (int i = 0; i < records; i++) {
            tagBytes.putInt(i * 4, tags[i]);
        }
        tagBytes.position(0).limit(records * 4);
        if (entries * 4 > runBytes.capacity()) {
            runBytes = buffer(Math.max(entries * 4, runBytes.capacity() * 2));
        }
        if (LAYERS * pages * 4 > startBytes.capacity()) {
            startBytes = buffer(LAYERS * pages * 4);
        }
        for (int index = 0; index < LAYERS * pages; index++) {
            startBytes.putInt(index * 4, runStart[index]);
            for (int j = runStart[index], end = runStart[index] + runCount[index]; j < end; j++) {
                runBytes.putInt(j * 4, index);
            }
        }
        runBytes.position(0).limit(entries * 4);
        startBytes.position(0).limit(LAYERS * pages * 4);
    }

    /** Each record's tag, a 32-bit integer a record. */
    public ByteBuffer tags() {
        return tagBytes;
    }

    /** Each command's run ({@link #run}), a 32-bit integer a command, in the order of {@link #commands}. */
    public ByteBuffer runs() {
        return runBytes;
    }

    /** Each run's first command, a 32-bit integer a run, runs numbered by {@link #run}. */
    public ByteBuffer starts() {
        return startBytes;
    }

    /** The number of a layer's and page's run. */
    public int run(int layer, int page) {
        return layer * pages + page;
    }

    /** How many runs there are: a layer and a page each. */
    public int runSlots() {
        return LAYERS * pages;
    }

    /** Every command, run after run; its position and limit bound what was written. */
    public ByteBuffer commands() {
        return commandBytes;
    }

    /** Every record; its position and limit bound what was written. */
    public ByteBuffer records() {
        return recordBytes;
    }

    /** Where a layer's and page's run starts in {@link #commands}, in bytes. */
    public long runOffset(int layer, int page) {
        return (long) runStart[layer * pages + page] * COMMAND_BYTES;
    }

    /** How many commands a layer's and page's run holds. */
    public int runCount(int layer, int page) {
        return runCount[layer * pages + page];
    }

    /** How many commands there are in all. */
    public int commandCount() {
        return entries;
    }

    /** How many records there are. */
    public int recordCount() {
        return records;
    }

    public int pages() {
        return pages;
    }
}
