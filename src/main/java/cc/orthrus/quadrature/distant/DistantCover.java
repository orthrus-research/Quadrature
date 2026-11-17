package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.core.terrain.CoverageMap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;

/**
 * Near terrain's coverage map as the distant draw reads it: one byte a chunk column, the column's
 * state, in a small texture written again whenever the map's generation moves. A fragment looks its
 * column up and gives way only where near terrain says the column's surface is meshed.
 *
 * <p>Client thread only.
 */
final class DistantCover {

    private int texture;
    private int side;
    private long generation = Long.MIN_VALUE;
    private long firstBlockX;
    private long firstBlockZ;
    private ByteBuffer bytes;

    /**
     * Follows the map: makes the texture for its size and writes it if it has changed.
     *
     * @return whether there is a map to read; false leaves the box rule in force
     */
    boolean update(CoverageMap map) {
        if (map == null || map.side() <= 0) {
            side = 0;
            return false;
        }
        if (map.side() != side || texture == 0) {
            if (texture != 0) {
                GL11.glDeleteTextures(texture);
            }
            side = map.side();
            texture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(texture, 1, GL30.GL_R8UI, side, side);
            GL45.glTextureParameteri(texture, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(texture, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            bytes = ByteBuffer.allocateDirect(side * side);
            generation = Long.MIN_VALUE;
        }
        // The map's first column is its box's lowest x and z: the origin cube less its reach.
        firstBlockX = (map.originCubeX() - (side - 1) / 2) * 16L;
        firstBlockZ = (map.originCubeZ() - (side - 1) / 2) * 16L;
        if (map.generation() != generation) {
            generation = map.generation();
            bytes.clear();
            bytes.put(map.states(), 0, side * side);
            bytes.flip();
            // Rows of an odd number of bytes: no padding between them.
            int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL45.glTextureSubImage2D(texture, 0, 0, 0, side, side, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, bytes);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
        }
        return true;
    }

    int texture() {
        return texture;
    }

    /** Columns along a side of the map, or 0 when there is none. */
    int side() {
        return side;
    }

    /** The block at the map's lowest x. */
    long firstBlockX() {
        return firstBlockX;
    }

    long firstBlockZ() {
        return firstBlockZ;
    }
}
