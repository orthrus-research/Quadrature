package cc.orthrus.quadrature.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import cc.orthrus.quadrature.core.terrain.QuadOrder;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL45;

/**
 * One cube's translucent terrain (PLAN item 12e): its quads as the mesher wrote them, in the game's block vertex format
 * in the cube's own coordinates, and a buffer of its own holding them in the order last sorted, farthest from the eye
 * first, so that blending draws each over what lies behind it.
 *
 * <p>A cube's quads are ordered as the game orders a render chunk's: by the squared distance from the eye to the mean of
 * each quad's four corners, the farthest first, quads at the same distance in the order they were meshed. They are
 * sorted again once the eye has moved more than a block from where they were last sorted. Client thread.
 */
final class TranslucentCube {

    /**
     * Whether Quadrature meshes and draws translucent terrain, and the game's chunk rebuild leaves it out:
     * {@code -Dquadrature.terrain.translucent=own}.
     */
    static final boolean OWN = "own".equals(System.getProperty("quadrature.terrain.translucent"));

    private static final int INTS = CubeMesh.STRIDE / 4;
    private static final int QUAD_INTS = 4 * INTS;

    final int x;
    final int y;
    final int z;
    /** The quads as meshed, a quad's four vertices at a time. */
    private final int[] meshed;
    private final int quads;
    /** Each quad's corners' mean, in the cube's coordinates. */
    private final float[] centers;
    /** Shared by every cube, as the upload is. */
    private static final QuadOrder ORDER = new QuadOrder();
    private static int[] order = new int[0];
    /** What a sort writes and hands the driver, shared by every cube: the client thread sorts one at a time. */
    private static ByteBuffer upload = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());
    private int buffer;
    /** Where the eye stood, in the cube's coordinates, when the quads were last sorted; NaN before the first sort. */
    private double sortedX = Double.NaN;
    private double sortedY;
    private double sortedZ;
    /** The mesh a draw takes: this cube's buffer as its first layer. */
    final CubeMesh mesh;

    TranslucentCube(int x, int y, int z, byte[] vertices, int vertexCount) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.quads = vertexCount / 4;
        IntBuffer ints = ByteBuffer.wrap(vertices, 0, vertexCount * CubeMesh.STRIDE).order(ByteOrder.nativeOrder()).asIntBuffer();
        this.meshed = new int[quads * QUAD_INTS];
        ints.get(meshed);
        this.centers = new float[quads * 3];
        for (int q = 0; q < quads; q++) {
            float cx = 0;
            float cy = 0;
            float cz = 0;
            for (int v = 0; v < 4; v++) {
                int at = q * QUAD_INTS + v * INTS;
                cx += Float.intBitsToFloat(meshed[at]);
                cy += Float.intBitsToFloat(meshed[at + 1]);
                cz += Float.intBitsToFloat(meshed[at + 2]);
            }
            centers[q * 3] = cx * 0.25f;
            centers[q * 3 + 1] = cy * 0.25f;
            centers[q * 3 + 2] = cz * 0.25f;
        }
        this.mesh = new CubeMesh(x, y, z);
    }

    int vertexCount() {
        return quads * 4;
    }

    /** Whether the eye, in world coordinates, has moved more than a block from where the quads were last sorted. */
    boolean stale(double eyeX, double eyeY, double eyeZ) {
        if (Double.isNaN(sortedX)) {
            return true;
        }
        double dx = eyeX - (x << 4) - sortedX;
        double dy = eyeY - (y << 4) - sortedY;
        double dz = eyeZ - (z << 4) - sortedZ;
        return dx * dx + dy * dy + dz * dz > 1.0;
    }

    /** Sorts the quads for an eye, in world coordinates, and writes them to the cube's buffer in that order. */
    void sort(double eyeX, double eyeY, double eyeZ) {
        float ex = (float) (eyeX - (x << 4));
        float ey = (float) (eyeY - (y << 4));
        float ez = (float) (eyeZ - (z << 4));
        if (order.length < quads) {
            order = new int[quads];
        }
        ORDER.sort(centers, quads, ex, ey, ez, order);
        int bytes = meshed.length * 4;
        if (upload.capacity() < bytes) {
            upload = ByteBuffer.allocateDirect(Math.max(bytes, upload.capacity() * 2)).order(ByteOrder.nativeOrder());
        }
        upload.clear();
        IntBuffer into = upload.asIntBuffer();
        for (int i = 0; i < quads; i++) {
            into.put(meshed, order[i] * QUAD_INTS, QUAD_INTS);
        }
        upload.limit(bytes);
        if (buffer == 0) {
            buffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(buffer, upload, GL45.GL_DYNAMIC_STORAGE_BIT);
            mesh.place(0, buffer, 0, quads * 4);
        } else {
            GL45.glNamedBufferSubData(buffer, 0, upload);
        }
        sortedX = ex;
        sortedY = ey;
        sortedZ = ez;
    }

    /** Whether the cube has been sorted, and so has a buffer to draw. */
    boolean drawable() {
        return buffer != 0;
    }

    /** Lets go of the cube's buffer. */
    void delete() {
        if (buffer != 0) {
            GL15.glDeleteBuffers(buffer);
            buffer = 0;
        }
    }
}
