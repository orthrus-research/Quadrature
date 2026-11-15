package cc.orthrus.quadrature.core.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * What {@link RunMesher} makes of one tile: its faces as {@link FaceRecord}s in groups, one for
 * each layer and direction, with the tile's height bounds and the tint of each of its 64 by 64
 * columns. A view that cannot see a direction skips that group; a layer is one draw.
 *
 * <p>The records are held here until {@link #write} copies them, group after group, into a buffer
 * the caller owns. Reused from tile to tile; allocates only when it grows.
 */
public final class TileMesh {

    /**
     * Layers. Opaque terrain; leaves, drawn with the cutout test; water tops, drawn after the
     * opaque scene; and the skirt, the part of the tile's outer sides its neighbors hide, which
     * closes the tile and matters only where a neighbor is missing or of another level.
     */
    public static final int OPAQUE = 0, CUTOUT = 1, WATER = 2, SKIRT = 3, LAYERS = 4;

    /** Groups: a layer's six directions in {@link FaceRecord} order. */
    public static final int GROUPS = LAYERS * FaceRecord.DIRECTIONS;

    private final long[][] records = new long[GROUPS][];
    private final int[] count = new int[GROUPS];
    private final int[] tint = new int[RunTile.CELLS * RunTile.CELLS];
    private int minimumY;
    private int maximumY;
    private int topCells;

    public TileMesh() {
        for (int group = 0; group < GROUPS; group++) {
            records[group] = new long[group < FaceRecord.DIRECTIONS ? 1024 : 64];
        }
    }

    /** The group of a layer and direction. */
    public static int group(int layer, int direction) {
        return layer * FaceRecord.DIRECTIONS + direction;
    }

    void clear() {
        Arrays.fill(count, 0);
        minimumY = Integer.MAX_VALUE;
        maximumY = Integer.MIN_VALUE;
        topCells = 0;
    }

    void add(int group, long record) {
        int held = count[group];
        if (held == records[group].length) {
            records[group] = Arrays.copyOf(records[group], held * 2);
        }
        records[group][held] = record;
        count[group] = held + 1;
    }

    void span(int low, int high) {
        minimumY = Math.min(minimumY, low);
        maximumY = Math.max(maximumY, high);
    }

    void topCell() {
        topCells++;
    }

    void tints(RunTile tile) {
        for (int z = 0; z < RunTile.CELLS; z++) {
            System.arraycopy(tile.tint, RunTile.column(0, z), tint, z * RunTile.CELLS, RunTile.CELLS);
        }
    }

    /** Faces in a group. */
    public int count(int group) {
        return count[group];
    }

    /** Faces in a layer. */
    public int layerCount(int layer) {
        int total = 0;
        for (int direction = 0; direction < FaceRecord.DIRECTIONS; direction++) {
            total += count[group(layer, direction)];
        }
        return total;
    }

    /** Faces in all. */
    public int faces() {
        int total = 0;
        for (int held : count) {
            total += held;
        }
        return total;
    }

    /** Bytes {@link #write} needs. */
    public int bytes() {
        return faces() * FaceRecord.BYTES;
    }

    /** Where a group's first face lies among the written records, in faces. */
    public int offset(int group) {
        int before = 0;
        for (int earlier = 0; earlier < group; earlier++) {
            before += count[earlier];
        }
        return before;
    }

    /** A face of a group. */
    public long record(int group, int index) {
        if (index < 0 || index >= count[group]) {
            throw new IndexOutOfBoundsException(index);
        }
        return records[group][index];
    }

    /** The lowest and highest block level any face reaches; meaningless for a tile with no face. */
    public int minimumY() {
        return minimumY;
    }

    public int maximumY() {
        return maximumY;
    }

    /** Top faces of every layer before merging: the columns' own tops. */
    public int topCells() {
        return topCells;
    }

    /** The tint of column (x, z), each from 0 to 63, as the tile was given it. */
    public int tint(int x, int z) {
        return tint[z * RunTile.CELLS + x];
    }

    /** Copies the 64 by 64 tints, row after row along z, to the buffer's position and advances it. */
    public void writeTints(ByteBuffer target) {
        requireNative(target);
        for (int value : tint) {
            target.putInt(value);
        }
    }

    /**
     * Copies the records to the buffer's position, group after group, and advances it by
     * {@link #bytes()}. The buffer is in the machine's byte order, so a shader reads each record
     * as two 32-bit words, low then high.
     */
    public void write(ByteBuffer target) {
        requireNative(target);
        if (target.remaining() < bytes()) {
            throw new IllegalArgumentException(bytes() + " bytes do not fit in " + target.remaining());
        }
        int at = target.position();
        for (int group = 0; group < GROUPS; group++) {
            long[] from = records[group];
            for (int index = 0, held = count[group]; index < held; index++) {
                target.putLong(at, from[index]);
                at += FaceRecord.BYTES;
            }
        }
        target.position(at);
    }

    private static void requireNative(ByteBuffer target) {
        if (target.order() != ByteOrder.nativeOrder()) {
            throw new IllegalArgumentException("The buffer must be in the machine's byte order");
        }
    }
}
