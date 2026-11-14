package cc.orthrus.quadrature.terrain;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;

/**
 * One cube's geometry: a buffer of quads per layer, in the game's block vertex format, positions
 * relative to the cube's lowest corner.
 */
final class CubeMesh {

    /** Bytes per vertex: position, color, texture coordinate, light map coordinate. */
    static final int STRIDE = 28;
    static final int LAYERS = 3;

    final int x;
    final int y;
    final int z;
    private final int[] buffers = new int[LAYERS];
    private final int[] counts = new int[LAYERS];

    CubeMesh(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** Stores a layer's vertices; created through direct state access, so no binding changes. */
    void set(int layer, ByteBuffer data, int vertexCount) {
        buffers[layer] = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(buffers[layer], data, 0);
        counts[layer] = vertexCount;
    }

    int buffer(int layer) {
        return buffers[layer];
    }

    int count(int layer) {
        return counts[layer];
    }

    boolean isEmpty() {
        return vertices() == 0;
    }

    int vertices() {
        return counts[0] + counts[1] + counts[2];
    }

    long bytes() {
        return (long) vertices() * STRIDE;
    }

    void delete() {
        for (int layer = 0; layer < LAYERS; layer++) {
            if (buffers[layer] != 0) {
                GL15.glDeleteBuffers(buffers[layer]);
                buffers[layer] = 0;
                counts[layer] = 0;
            }
        }
    }
}
