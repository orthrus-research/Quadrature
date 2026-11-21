package cc.orthrus.quadrature.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL45;

/**
 * One cube's quads in a layer another mod adds to the game's list of layers, after the translucent one (PLAN item 12g;
 * GTCEu: Reloaded's bloom layer): as the game's dispatcher wrote them, in the game's block vertex format in the cube's
 * own coordinates, in a buffer of its own, written once when the cube is placed. Not sorted: the game draws such a
 * layer's render chunks as it draws a cutout layer's. Client thread.
 */
final class AddedLayerCube {

    final int x;
    final int y;
    final int z;
    private final int vertexCount;
    private int buffer;
    /** The mesh a draw takes: this cube's buffer as its first layer. */
    final CubeMesh mesh;

    AddedLayerCube(int x, int y, int z, byte[] vertices, int vertexCount) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.vertexCount = vertexCount;
        this.mesh = new CubeMesh(x, y, z);
        int bytes = vertexCount * CubeMesh.STRIDE;
        ByteBuffer upload = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        upload.put(vertices, 0, bytes).flip();
        buffer = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(buffer, upload, 0);
        mesh.place(0, buffer, 0, vertexCount);
    }

    int vertexCount() {
        return vertexCount;
    }

    /** Lets go of the cube's buffer. */
    void delete() {
        if (buffer != 0) {
            GL15.glDeleteBuffers(buffer);
            buffer = 0;
        }
    }
}
