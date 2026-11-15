package cc.orthrus.quadrature.core.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A meshed tile on its way from the thread that built it to the thread that stores it: its face
 * records, then one byte for each of its 64 by 64 columns' tints, in one buffer in the machine's
 * byte order, with where each group lies and how high the faces reach.
 *
 * <p>The tints are kept to a byte each, which is what a biome's number needs; four lie in a 32-bit
 * word, the first in its low byte.
 */
public final class BuiltTile {

    /** Bytes of tints after the faces. */
    public static final int TINT_BYTES = RunTile.CELLS * RunTile.CELLS;

    private ByteBuffer data = ByteBuffer.allocateDirect(64 << 10).order(ByteOrder.nativeOrder());
    private final int[] groupOffset = new int[TileMesh.GROUPS];
    private final int[] groupCount = new int[TileMesh.GROUPS];
    private long key;
    private long generation;
    private int faces;
    private int minimumY;
    private int maximumY;
    private long sampleNanos;
    private long meshNanos;

    /** Takes a mesh's faces and tints, replacing what this held. */
    public void set(long key, long generation, TileMesh mesh, long sampleNanos, long meshNanos) {
        this.key = key;
        this.generation = generation;
        this.sampleNanos = sampleNanos;
        this.meshNanos = meshNanos;
        faces = mesh.faces();
        minimumY = mesh.minimumY();
        maximumY = mesh.maximumY();
        int bytes = mesh.bytes() + TINT_BYTES;
        if (data.capacity() < bytes) {
            data = ByteBuffer.allocateDirect(Math.max(bytes, data.capacity() * 2)).order(ByteOrder.nativeOrder());
        }
        data.clear();
        mesh.write(data);
        for (int z = 0; z < RunTile.CELLS; z++) {
            for (int x = 0; x < RunTile.CELLS; x++) {
                data.put((byte) mesh.tint(x, z));
            }
        }
        data.flip();
        for (int group = 0; group < TileMesh.GROUPS; group++) {
            groupOffset[group] = mesh.offset(group);
            groupCount[group] = mesh.count(group);
        }
    }

    public long key() {
        return key;
    }

    public long generation() {
        return generation;
    }

    /** The faces and tints, from position 0 to the limit. */
    public ByteBuffer data() {
        return data;
    }

    /** Bytes of {@link #data}. */
    public int bytes() {
        return faces * FaceRecord.BYTES + TINT_BYTES;
    }

    public int faces() {
        return faces;
    }

    /** Where a group's first face lies, in faces from the start of the data. */
    public int groupOffset(int group) {
        return groupOffset[group];
    }

    public int groupCount(int group) {
        return groupCount[group];
    }

    public int minimumY() {
        return minimumY;
    }

    public int maximumY() {
        return maximumY;
    }

    public long sampleNanos() {
        return sampleNanos;
    }

    public long meshNanos() {
        return meshNanos;
    }
}
