package cc.orthrus.quadrature.terrain;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;

/**
 * One cube's geometry: for each layer a run of quads in the game's block vertex format, positions
 * relative to the cube's lowest corner. The runs lie either in a page of the arena, one after another
 * in one block, or in buffers of the mesh's own.
 */
final class CubeMesh {

    /** Bytes per vertex: position, color, texture coordinate, light map coordinate. */
    static final int STRIDE = 28;
    static final int LAYERS = 3;

    final int x;
    final int y;
    final int z;
    private final int[] buffers = new int[LAYERS];
    private final int[] firsts = new int[LAYERS];
    private final int[] counts = new int[LAYERS];
    /** Whether the buffers are the mesh's own, to delete with it. */
    private boolean owned;
    /** The mesh's block in the arena, or -1 when it has none. */
    long block = -1;
    /** The size the block was asked for with. */
    int blockBytes;
    /** The mesh's block of face records in the arena that holds those, or -1 when it has none. */
    long faceBlock = -1;
    int faceBytes;
    /** For each layer, the first record in its page and how many there are. */
    final int[] faceFirsts = new int[LAYERS];
    final int[] faceCounts = new int[LAYERS];
    /** The terrain's mesh version when the mesh was put in place: a diagnostic. */
    long placedAt = -1;
    /** What the cube's shape was built from, as {@link BuiltCube#geometry} gives it. */
    long geometry = BuiltCube.NO_GEOMETRY;
    /** What the mesh draws, hashed ({@link BuiltCube#content}), where the build causes are counted; 0 otherwise. */
    long content;

    CubeMesh(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** A mesh in buffers of its own, from a built cube's vertices. Client thread. */
    static CubeMesh owned(int x, int y, int z, BuiltCube built) {
        CubeMesh mesh = new CubeMesh(x, y, z);
        mesh.owned = true;
        for (int layer = 0; layer < LAYERS; layer++) {
            int count = built.vertexCount(layer);
            if (count > 0) {
                byte[] bytes = built.vertices(layer);
                ByteBuffer data = ByteBuffer.allocateDirect(bytes.length).order(java.nio.ByteOrder.nativeOrder());
                data.put(bytes).flip();
                mesh.set(layer, data, count);
            }
        }
        return mesh;
    }

    /** Stores a layer's vertices in a buffer of the mesh's own, through direct state access. */
    private void set(int layer, ByteBuffer data, int vertexCount) {
        buffers[layer] = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(buffers[layer], data, 0);
        counts[layer] = vertexCount;
    }

    /** Records where a layer's vertices lie in a buffer that is not the mesh's own. */
    void place(int layer, int buffer, int first, int vertexCount) {
        buffers[layer] = buffer;
        firsts[layer] = first;
        counts[layer] = vertexCount;
    }

    /** The buffer a layer's vertices are in. */
    int buffer(int layer) {
        return buffers[layer];
    }

    /** The layer's first vertex in that buffer. */
    int first(int layer) {
        return firsts[layer];
    }

    int count(int layer) {
        return counts[layer];
    }

    boolean isEmpty() {
        return vertices() == 0;
    }

    int vertices() {
        return counts[0] + counts[1] + counts[2] + 4 * (faceCounts[0] + faceCounts[1] + faceCounts[2]);
    }

    /** The mesh's vertices kept as the game wrote them. */
    int keptVertices() {
        return counts[0] + counts[1] + counts[2];
    }

    long bytes() {
        return (long) keptVertices() * STRIDE + faceBytes;
    }

    /** Whether the mesh lies in the arena. */
    boolean inArena() {
        return block >= 0;
    }

    /** Deletes the mesh's own buffers. A mesh in the arena is freed through the arena instead. */
    void delete() {
        for (int layer = 0; layer < LAYERS; layer++) {
            if (owned && buffers[layer] != 0) {
                GL15.glDeleteBuffers(buffers[layer]);
            }
            buffers[layer] = 0;
            counts[layer] = 0;
        }
    }
}
