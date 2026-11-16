package cc.orthrus.quadrature.core.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A frame's draws of distant tiles for one view: a record for each tile and indirect commands, one
 * for each group of a tile's faces the view can see, gathered so that all the commands of one draw
 * layer and one page lie together and are issued with one call.
 *
 * <p>A face is six vertices, two triangles, that the vertex stage builds from the face's record: a
 * command's first vertex is six times its first face's index in the page, so the stage finds the
 * face from the vertex number, and its base instance names the tile's record.
 *
 * <p>A tile's record is 32 bytes: where its column (0, 0) and floor lie from the view's origin, as
 * three floats worked out in doubles; the width of a cell; the index, in 32-bit words of its page,
 * of its tints; its level; how much of it shows, 0 to 1; and four bytes unused.
 *
 * <p>Not safe for use from more than one thread. Allocates only when it grows.
 */
public final class TileDraws {

    /** Draw layers: opaque faces and the skirt; leaves; water. */
    public static final int OPAQUE = 0, CUTOUT = 1, WATER = 2, LAYERS = 3;

    /** Bytes of an indirect command for a draw without indices. */
    public static final int COMMAND_BYTES = 16;
    public static final int RECORD_BYTES = 32;
    public static final int VERTICES_A_FACE = 6;

    private int pages;
    private int count;
    private int[] run = new int[256];
    private int[] first = new int[256];
    private int[] vertices = new int[256];
    private int[] record = new int[256];
    private int[] runStart = new int[1];
    private int[] runCount = new int[1];
    private ByteBuffer commands = ByteBuffer.allocateDirect(256 * COMMAND_BYTES).order(ByteOrder.nativeOrder());
    private ByteBuffer records = ByteBuffer.allocateDirect(64 * RECORD_BYTES).order(ByteOrder.nativeOrder());
    private int recordCount;
    private long faces;

    /** The draw layer a mesh layer is drawn in: the skirt goes with the opaque faces. */
    public static int drawLayer(int meshLayer) {
        return meshLayer == TileMesh.SKIRT ? OPAQUE : meshLayer;
    }

    /** Starts a frame's list for an arena of so many pages. */
    public void begin(int pages) {
        this.pages = pages;
        count = 0;
        recordCount = 0;
        faces = 0;
        records.clear();
        if (runStart.length < LAYERS * pages) {
            runStart = new int[LAYERS * pages];
            runCount = new int[LAYERS * pages];
        }
    }

    /**
     * Adds a tile's record.
     *
     * @param originX where the tile's column (0, 0) lies, less the view's origin; so for y, the
     *                tile's floor, and z
     * @return the record's index, for {@link #add}
     */
    public int record(double originX, double originY, double originZ, int level, int tintWord, float shows) {
        return record(originX, originY, originZ, level, tintWord, shows, 0);
    }

    /** Over the band inside its own hole, this tile's four children are all stored. */
    public static final int FINER_STORED = 1;
    /** Over the band at its ring's edge, the coarser tile over this one is stored. */
    public static final int COARSER_STORED = 2;

    /**
     * Adds a tile's record, with which of its partners in a crossfade are stored:
     * {@link #FINER_STORED}, {@link #COARSER_STORED}. A tile fades against a partner only when the
     * partner is there to show in its place.
     */
    public int record(double originX, double originY, double originZ, int level, int tintWord, float shows,
            int partners) {
        if (records.remaining() < RECORD_BYTES) {
            ByteBuffer larger = ByteBuffer.allocateDirect(records.capacity() * 2).order(ByteOrder.nativeOrder());
            records.flip();
            larger.put(records);
            records = larger;
        }
        records.putFloat((float) originX).putFloat((float) originY).putFloat((float) originZ);
        records.putFloat(1 << level).putInt(tintWord).putInt(level).putFloat(shows).putInt(partners);
        return recordCount++;
    }

    /** Adds one group of a tile's faces: {@code faces} of them from {@code firstFace} of the page. */
    public void add(int drawLayer, int page, int firstFace, int faces, int record) {
        if (faces == 0) {
            return;
        }
        if (count == run.length) {
            int size = count * 2;
            run = Arrays.copyOf(run, size);
            first = Arrays.copyOf(first, size);
            vertices = Arrays.copyOf(vertices, size);
            this.record = Arrays.copyOf(this.record, size);
        }
        run[count] = drawLayer * pages + page;
        first[count] = Math.multiplyExact(firstFace, VERTICES_A_FACE);
        vertices[count] = Math.multiplyExact(faces, VERTICES_A_FACE);
        this.record[count] = record;
        count++;
        this.faces += faces;
    }

    /** Orders the commands by draw layer and page. Call once, after the last {@link #add}. */
    public void finish() {
        int runs = LAYERS * pages;
        Arrays.fill(runCount, 0, runs, 0);
        for (int i = 0; i < count; i++) {
            runCount[run[i]]++;
        }
        int at = 0;
        for (int r = 0; r < runs; r++) {
            runStart[r] = at;
            at += runCount[r];
            runCount[r] = 0;
        }
        if (commands.capacity() < count * COMMAND_BYTES) {
            commands = ByteBuffer.allocateDirect(Math.max(commands.capacity() * 2, count * COMMAND_BYTES))
                .order(ByteOrder.nativeOrder());
        }
        commands.clear();
        for (int i = 0; i < count; i++) {
            int r = run[i];
            int offset = (runStart[r] + runCount[r]++) * COMMAND_BYTES;
            commands.putInt(offset, vertices[i]);
            commands.putInt(offset + 4, 1);
            commands.putInt(offset + 8, first[i]);
            commands.putInt(offset + 12, record[i]);
        }
        commands.limit(count * COMMAND_BYTES);
        records.flip();
    }

    /** The commands, ready to hand to the driver. */
    public ByteBuffer commands() {
        return commands;
    }

    /** The tiles' records, ready to hand to the driver. */
    public ByteBuffer records() {
        return records;
    }

    /** Where a draw layer's commands for a page start in the command buffer, in bytes. */
    public long runOffset(int drawLayer, int page) {
        return (long) runStart[drawLayer * pages + page] * COMMAND_BYTES;
    }

    /** How many commands a draw layer has for a page. */
    public int runCount(int drawLayer, int page) {
        return runCount[drawLayer * pages + page];
    }

    public int commandCount() {
        return count;
    }

    public int recordCount() {
        return recordCount;
    }

    /** Faces the commands draw. */
    public long faces() {
        return faces;
    }

    public int pages() {
        return pages;
    }
}
