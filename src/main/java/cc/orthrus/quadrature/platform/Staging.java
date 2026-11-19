package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.Quadrature;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * A buffer the CPU reads back from, filled by copies on the GPU from buffers the GPU reads or writes,
 * so that the CPU never reads one of those itself: a buffer the CPU reads may be moved by the driver
 * into host memory, and a buffer read on the GPU from there is slow to read where reads miss the
 * cache. A buffer used only as a copy's source stays where it is. Its storage asks for client memory.
 * Grown, made anew, where a read needs more room.
 */
public final class Staging {

    private final String name;
    private int buffer;
    private long bytes;

    public Staging(String name) {
        this.name = name;
    }

    /** The buffer's GL name, 0 before the first read. */
    public int buffer() {
        return buffer;
    }

    private void room(long needed) {
        if (needed <= bytes) {
            return;
        }
        if (buffer != 0) {
            GL15.glDeleteBuffers(buffer);
        }
        buffer = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(buffer, needed, GL44.GL_CLIENT_STORAGE_BIT);
        bytes = needed;
        Quadrature.LOGGER.info("Staging buffer for {}: buffer {}, {} KiB, client storage.", name, buffer,
            needed >> 10);
    }

    /** Copies a range of a buffer here on the GPU and reads it back into {@code into}, from its position to its limit. */
    public void read(int source, long offset, ByteBuffer into) {
        long length = into.remaining();
        room(length);
        GL45.glCopyNamedBufferSubData(source, buffer, offset, 0, length);
        GL45.glGetNamedBufferSubData(buffer, 0, into);
    }

    public void read(int source, long offset, IntBuffer into) {
        long length = 4L * into.remaining();
        room(length);
        GL45.glCopyNamedBufferSubData(source, buffer, offset, 0, length);
        GL45.glGetNamedBufferSubData(buffer, 0, into);
    }

    public void read(int source, long offset, FloatBuffer into) {
        long length = 4L * into.remaining();
        room(length);
        GL45.glCopyNamedBufferSubData(source, buffer, offset, 0, length);
        GL45.glGetNamedBufferSubData(buffer, 0, into);
    }
}
