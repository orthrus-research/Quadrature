package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.core.terrain.FaceRecord;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Sorts a meshed cube's quads into the two streams. A quad the walk noted that is a whole face of its
 * cell showing one rectangle of the atlas becomes a record of 32 bytes; every other quad keeps the game's four
 * vertices as they are, with a header saying what is known of it.
 *
 * <p>A record must give back the game's vertices exactly. That is tried for every quad as it is made:
 * the record is expanded and set beside the vertices it came from, and a quad whose record does not
 * give them back bit for bit keeps its vertices instead.
 */
final class FaceBuilder {

    /** The words of a general quad's header: sprite and state, tint and flags, and two kept for a normal and more. */
    static final int HEADER_WORDS = 4;
    /** The sprite number of a quad whose sprite is not known. */
    static final int NO_SPRITE = 0xFFFF;

    private static final int LAYERS = CubeMesh.LAYERS;
    private static final int STRIDE = CubeMesh.STRIDE;

    /**
     * Every rectangle of the atlas a quad has shown, numbered in the order first met: least u, least v,
     * greatest u, greatest v, as the game's own floats. The game draws a quad's coordinates a little
     * in from its sprite's corners, so a rectangle is a quad's and not a sprite's; quads of one sprite
     * baked alike share one.
     */
    private float[] spriteRects = new float[4 * 256];
    private int sprites;
    private long spritesVersion;
    /** An open hash over the rectangles: a rectangle's number and one, or 0 for an empty place. */
    private int[] rectPlaces = new int[1024];
    private final float[] rect = new float[4];

    private final int[][] records = new int[LAYERS][FaceRecord.WORDS * 256];
    private final int[] recordCounts = new int[LAYERS];
    private final ByteBuffer[] vertices = new ByteBuffer[LAYERS];
    private final int[] vertexCounts = new int[LAYERS];
    private final int[][] headers = new int[LAYERS][HEADER_WORDS * 64];

    private final float[] positions = new float[12];
    private final float[] coordinates = new float[8];
    private final int[] colors = new int[4];
    private final int[] lights = new int[4];
    private final ByteBuffer expanded = ByteBuffer.allocateDirect(4 * STRIDE).order(ByteOrder.nativeOrder());

    // Counts over everything built.
    private long quads;
    private long compact;
    private long notNoted;
    private long notAFace;
    private long notASprite;
    private long notOpaque;
    private long lightTooGreat;
    private long notGivenBack;

    FaceBuilder() {
        for (int layer = 0; layer < LAYERS; layer++) {
            vertices[layer] = ByteBuffer.allocateDirect(STRIDE * 4 * 64).order(ByteOrder.nativeOrder());
        }
    }

    /**
     * Sorts the quads of the cube a mesher last built.
     *
     * @param walk the notes of the quads drawn through it, in the order drawn within each layer
     */
    void build(ByteBuffer[] layerData, int[] layerVertices, QuadWalk walk) {
        int note = 0;
        for (int layer = 0; layer < LAYERS; layer++) {
            recordCounts[layer] = 0;
            vertexCounts[layer] = 0;
            vertices[layer].clear();
        }
        // The notes are in the order the blocks were drawn, layers mixed; each layer's are in its own order.
        int[] next = new int[LAYERS];
        for (int layer = 0; layer < LAYERS; layer++) {
            next[layer] = nextNote(walk, 0, layer);
        }
        for (int layer = 0; layer < LAYERS; layer++) {
            ByteBuffer data = layerData[layer];
            int start = data == null ? 0 : data.position();
            int count = layerVertices[layer] / 4;
            for (int quad = 0; quad < count; quad++) {
                int at = start + quad * 4 * STRIDE;
                note = next[layer];
                boolean noted = note < walk.count() && walk.firstVertex(note) == quad * 4;
                if (noted) {
                    next[layer] = nextNote(walk, note + 1, layer);
                }
                quads++;
                if (noted && record(data, at, walk, note, layer)) {
                    compact++;
                    continue;
                }
                if (!noted) {
                    notNoted++;
                }
                keep(data, at, layer, noted ? walk : null, note);
            }
        }
    }

    private static int nextNote(QuadWalk walk, int from, int layer) {
        while (from < walk.count() && walk.layer(from) != layer) {
            from++;
        }
        return from;
    }

    /** Makes a record of a noted quad, if it is a whole face showing one rectangle of the atlas and the record gives it back. */
    private boolean record(ByteBuffer data, int at, QuadWalk walk, int note, int layer) {
        boolean opaque = true;
        boolean lit = true;
        for (int vertex = 0; vertex < 4; vertex++) {
            int v = at + vertex * STRIDE;
            positions[vertex * 3] = data.getFloat(v);
            positions[vertex * 3 + 1] = data.getFloat(v + 4);
            positions[vertex * 3 + 2] = data.getFloat(v + 8);
            int color = data.getInt(v + 12);
            // The game's color is red, green, blue, alpha in that order of bytes.
            int red = data.get(v + 12) & 0xFF;
            int green = data.get(v + 13) & 0xFF;
            int blue = data.get(v + 14) & 0xFF;
            opaque &= (data.get(v + 15) & 0xFF) == 255;
            colors[vertex] = red | green << 8 | blue << 16;
            coordinates[vertex * 2] = data.getFloat(v + 16);
            coordinates[vertex * 2 + 1] = data.getFloat(v + 20);
            int block = data.getShort(v + 24);
            int sky = data.getShort(v + 26);
            lit &= block >= 0 && block <= 255 && sky >= 0 && sky <= 255;
            lights[vertex] = sky << 8 | block;
        }
        int cell = walk.cell(note);
        int located = FaceRecord.locate(positions, cell & 15, cell >> 8 & 15, cell >> 4 & 15);
        if (located < 0) {
            notAFace++;
            return false;
        }
        int corners = FaceRecord.rectangle(coordinates, rect);

        if (corners < 0) {
            notASprite++;
            return false;
        }
        if (!opaque) {
            notOpaque++;
            return false;
        }
        if (!lit) {
            lightTooGreat++;
            return false;
        }
        int number = rectangle(rect[0], rect[1], rect[2], rect[3]);
        if (number >= NO_SPRITE) {
            notASprite++;
            return false;
        }
        int count = recordCounts[layer];
        if ((count + 1) * FaceRecord.WORDS > records[layer].length) {
            records[layer] = java.util.Arrays.copyOf(records[layer], records[layer].length * 2);
        }
        int[] out = records[layer];
        int word = count * FaceRecord.WORDS;
        FaceRecord.pack(out, word, cell & 15, cell >> 8 & 15, cell >> 4 & 15, located >> 8, located & 0xFF, corners, number,
            walk.state(note), walk.tint(note), walk.flags(note), colors, lights);
        expand(out, word, expanded, 0);
        for (int i = 0; i < 4 * STRIDE; i++) {
            if (expanded.get(i) != data.get(at + i)) {
                notGivenBack++;
                return false;
            }
        }
        recordCounts[layer] = count + 1;
        return true;
    }

    /** Keeps a quad's four vertices as the game wrote them, with a header. */
    private void keep(ByteBuffer data, int at, int layer, QuadWalk walk, int note) {
        ByteBuffer out = vertices[layer];
        if (out.remaining() < 4 * STRIDE) {
            ByteBuffer larger = ByteBuffer.allocateDirect(out.capacity() * 2).order(ByteOrder.nativeOrder());
            out.flip();
            larger.put(out);
            vertices[layer] = larger;
            out = larger;
        }
        int quad = vertexCounts[layer] / 4;
        out.put(out.position(), data, at, 4 * STRIDE);
        out.position(out.position() + 4 * STRIDE);
        vertexCounts[layer] += 4;
        if ((quad + 1) * HEADER_WORDS > headers[layer].length) {
            headers[layer] = java.util.Arrays.copyOf(headers[layer], headers[layer].length * 2);
        }
        int[] header = headers[layer];
        int word = quad * HEADER_WORDS;
        if (walk == null) {
            header[word] = NO_SPRITE;
            header[word + 1] = 0xFFFFFF;
        } else {
            TextureAtlasSprite sprite = walk.quad(note).getSprite();
            header[word] = (sprite == null ? NO_SPRITE : Math.min(NO_SPRITE,
                rectangle(sprite.getMinU(), sprite.getMinV(), sprite.getMaxU(), sprite.getMaxV()))) | walk.state(note) << 16;
            header[word + 1] = walk.tint(note) | walk.flags(note) << 24;
        }
        header[word + 2] = 0;
        header[word + 3] = 0;
    }

    /** The number a rectangle of the atlas goes by, given the first time it is met. */
    private int rectangle(float minU, float minV, float maxU, float maxV) {
        int mask = rectPlaces.length - 1;
        int hash = Float.floatToRawIntBits(minU) * 0x9E3779B1 ^ Float.floatToRawIntBits(minV) * 0x85EBCA77
            ^ Float.floatToRawIntBits(maxU) * 0xC2B2AE3D ^ Float.floatToRawIntBits(maxV) * 0x27D4EB2F;
        int place = (hash ^ hash >>> 15) & mask;
        while (rectPlaces[place] != 0) {
            int at = (rectPlaces[place] - 1) * 4;
            if (spriteRects[at] == minU && spriteRects[at + 1] == minV && spriteRects[at + 2] == maxU
                && spriteRects[at + 3] == maxV) {
                return rectPlaces[place] - 1;
            }
            place = place + 1 & mask;
        }
        int number = sprites++;
        if (number * 4 + 4 > spriteRects.length) {
            spriteRects = java.util.Arrays.copyOf(spriteRects, spriteRects.length * 2);
        }
        spriteRects[number * 4] = minU;
        spriteRects[number * 4 + 1] = minV;
        spriteRects[number * 4 + 2] = maxU;
        spriteRects[number * 4 + 3] = maxV;
        spritesVersion++;
        rectPlaces[place] = number + 1;
        if (sprites * 2 > rectPlaces.length) {
            // Half full: twice the places, every rectangle put in again.
            rectPlaces = new int[rectPlaces.length * 2];
            mask = rectPlaces.length - 1;
            for (int i = 0; i < sprites; i++) {
                hash = Float.floatToRawIntBits(spriteRects[i * 4]) * 0x9E3779B1
                    ^ Float.floatToRawIntBits(spriteRects[i * 4 + 1]) * 0x85EBCA77
                    ^ Float.floatToRawIntBits(spriteRects[i * 4 + 2]) * 0xC2B2AE3D
                    ^ Float.floatToRawIntBits(spriteRects[i * 4 + 3]) * 0x27D4EB2F;
                place = (hash ^ hash >>> 15) & mask;
                while (rectPlaces[place] != 0) {
                    place = place + 1 & mask;
                }
                rectPlaces[place] = i + 1;
            }
        }
        return number;
    }
    /**
     * Writes the four vertices a record stands for, in the game's vertex format, as the shader that
     * draws from records builds them.
     */
    void expand(int[] in, int word, ByteBuffer out, int at) {
        int face = FaceRecord.face(in, word);
        int rect = FaceRecord.sprite(in, word) * 4;
        for (int vertex = 0; vertex < 4; vertex++) {
            int v = at + vertex * STRIDE;
            int corner = FaceRecord.faceCorner(in, word, vertex);
            out.putFloat(v, FaceRecord.x(in, word) + FaceRecord.cornerOffset(face, corner, 0));
            out.putFloat(v + 4, FaceRecord.y(in, word) + FaceRecord.cornerOffset(face, corner, 1));
            out.putFloat(v + 8, FaceRecord.z(in, word) + FaceRecord.cornerOffset(face, corner, 2));
            int color = FaceRecord.color(in, word, vertex);
            out.put(v + 12, (byte) color);
            out.put(v + 13, (byte) (color >> 8));
            out.put(v + 14, (byte) (color >> 16));
            out.put(v + 15, (byte) 255);
            int showing = FaceRecord.spriteCorner(in, word, vertex);
            out.putFloat(v + 16, spriteRects[rect + ((showing & 1) != 0 ? 2 : 0)]);
            out.putFloat(v + 20, spriteRects[rect + ((showing & 2) != 0 ? 3 : 1)]);
            int light = FaceRecord.light(in, word, vertex);
            out.putShort(v + 24, (short) (light & 0xFF));
            out.putShort(v + 26, (short) (light >> 8));
        }
    }

    // What was built.

    /** A layer's records, {@link FaceRecord#WORDS} words each. */
    int[] records(int layer) {
        return records[layer];
    }

    int recordCount(int layer) {
        return recordCounts[layer];
    }

    /** A layer's kept vertices, from the buffer's start. */
    ByteBuffer vertices(int layer) {
        return vertices[layer];
    }

    int vertexCount(int layer) {
        return vertexCounts[layer];
    }

    /** A layer's headers, {@link #HEADER_WORDS} words for each four kept vertices. */
    int[] headers(int layer) {
        return headers[layer];
    }

    /** For each rectangle of the atlas met, least u, least v, greatest u, greatest v. */
    float[] spriteRects() {
        return spriteRects;
    }

    int sprites() {
        return sprites;
    }

    /** Moves on whenever a rectangle is first met. */
    long spritesVersion() {
        return spritesVersion;
    }

    /** Every quad sorted so far. */
    long quads() {
        return quads;
    }

    /** The part of them that became records, 0 to 1. */
    double recordShare() {
        return quads == 0 ? 0 : (double) compact / quads;
    }

    String summary() {
        return String.format("of %d quads, %d are records (%.1f%%); kept as vertices: %d not drawn from a model, %d not a "
                + "whole face of their cell, %d not one rectangle of texture, %d not opaque, %d with light past a byte, %d whose "
                + "record did not give them back; %d rectangles of texture",
            quads, compact, 100.0 * compact / Math.max(1, quads), notNoted, notAFace, notASprite, notOpaque, lightTooGreat,
            notGivenBack, sprites);
    }
}
