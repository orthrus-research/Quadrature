package cc.orthrus.quadrature.core.terrain;

/**
 * A whole face of a cell in 32 bytes, where the game's four vertices take 112: for a quad that is an
 * axis-aligned square of one cell on a cell boundary and shows a whole sprite.
 *
 * <p>The record holds enough to give back the game's four vertices exactly, in the order the game
 * wrote them: which corner of the face each vertex is, which corner of the sprite its texture
 * coordinate is, its color as the game wrote it and its light. Beside that it holds what shading
 * needs apart: the sprite, the block state, the tint alone and what the game multiplied in.
 *
 * <p>Eight 32-bit words:
 * <ol start="0">
 * <li>cell x, y, z in bits 0 to 11, four each; the face in bits 12 to 14; for each vertex the corner of
 * the face, two bits, in bits 15 to 22; for each vertex the corner of the sprite, two bits, in bits 23
 * to 30</li>
 * <li>the sprite in the low half, the block state in the high half</li>
 * <li>the tint, red highest, in bits 0 to 23; flags in bits 24 to 31</li>
 * <li>to 5: the four vertices' colors, three bytes each, red first, packed end to end</li>
 * <li value="6">and 7: the four vertices' light, sky then block, a byte each</li>
 * </ol>
 *
 * <p>A corner of a face is numbered by two bits, one for each of the two axes across the face: for a
 * face up or down, x then z; north or south, x then y; west or east, z then y. A set bit is the
 * higher side of the cell. A corner of the sprite likewise: bit 0 the greater u, bit 1 the greater v.
 */
public final class FaceRecord {

    public static final int WORDS = 8;
    public static final int BYTES = WORDS * 4;

    /** Flag: the quad is tinted, and the tint is not white for want of one. */
    public static final int TINTED = 1;
    /** Flag: the game darkened the vertices by what stands around them. */
    public static final int OCCLUDED = 2;
    /** Flag: the game darkened the face by the way it is turned. */
    public static final int SHADED = 4;
    /** Flag: the block is foliage, a thin surface light passes through. */
    public static final int FOLIAGE = 8;

    private FaceRecord() {
    }

    /**
     * Writes a record.
     *
     * @param faceCorners   for each vertex in the game's order, the corner of the face, two bits each,
     *                      the first vertex lowest
     * @param spriteCorners for each vertex, the corner of the sprite, likewise
     * @param tint          the tint as red, green, blue in the low 24 bits
     * @param colors        four colors as the game wrote them: red in bits 0 to 7, green 8 to 15, blue 16 to 23
     * @param lights        four lights: sky in bits 8 to 15, block in bits 0 to 7
     */
    public static void pack(int[] out, int at, int x, int y, int z, int face, int faceCorners, int spriteCorners,
        int sprite, int state, int tint, int flags, int[] colors, int[] lights) {
        out[at] = x | y << 4 | z << 8 | face << 12 | faceCorners << 15 | spriteCorners << 23;
        out[at + 1] = sprite & 0xFFFF | state << 16;
        out[at + 2] = tint & 0xFFFFFF | flags << 24;
        long low = colors[0] & 0xFFFFFFL | (colors[1] & 0xFFFFFFL) << 24 | (colors[2] & 0xFFFFL) << 48;
        out[at + 3] = (int) low;
        out[at + 4] = (int) (low >>> 32);
        out[at + 5] = colors[2] >>> 16 & 0xFF | (colors[3] & 0xFFFFFF) << 8;
        out[at + 6] = lights[0] & 0xFFFF | lights[1] << 16;
        out[at + 7] = lights[2] & 0xFFFF | lights[3] << 16;
    }

    public static int x(int[] in, int at) {
        return in[at] & 15;
    }

    public static int y(int[] in, int at) {
        return in[at] >>> 4 & 15;
    }

    public static int z(int[] in, int at) {
        return in[at] >>> 8 & 15;
    }

    /** The face, as {@link CubeConnectivity} numbers them. */
    public static int face(int[] in, int at) {
        return in[at] >>> 12 & 7;
    }

    /** The corner of the face a vertex is, 0 to 3. */
    public static int faceCorner(int[] in, int at, int vertex) {
        return in[at] >>> 15 + vertex * 2 & 3;
    }

    /** The corner of the sprite a vertex shows, 0 to 3. */
    public static int spriteCorner(int[] in, int at, int vertex) {
        return in[at] >>> 23 + vertex * 2 & 3;
    }

    public static int sprite(int[] in, int at) {
        return in[at + 1] & 0xFFFF;
    }

    public static int state(int[] in, int at) {
        return in[at + 1] >>> 16;
    }

    public static int tint(int[] in, int at) {
        return in[at + 2] & 0xFFFFFF;
    }

    public static int flags(int[] in, int at) {
        return in[at + 2] >>> 24;
    }

    /** A vertex's color as the game wrote it: red in bits 0 to 7, green 8 to 15, blue 16 to 23. */
    public static int color(int[] in, int at, int vertex) {
        long low = in[at + 3] & 0xFFFFFFFFL | (long) in[at + 4] << 32;
        switch (vertex) {
            case 0:
                return (int) (low & 0xFFFFFF);
            case 1:
                return (int) (low >>> 24 & 0xFFFFFF);
            case 2:
                return (int) (low >>> 48 & 0xFFFF) | (in[at + 5] & 0xFF) << 16;
            default:
                return in[at + 5] >>> 8 & 0xFFFFFF;
        }
    }

    /** A vertex's light: sky in bits 8 to 15, block in bits 0 to 7. */
    public static int light(int[] in, int at, int vertex) {
        return in[at + 6 + (vertex >> 1)] >>> (vertex & 1) * 16 & 0xFFFF;
    }

    /** Where along an axis, 0 or 1, a corner of a face lies in its cell: axis 0 is x, 1 is y, 2 is z. */
    public static int cornerOffset(int face, int corner, int axis) {
        int fixed = face >> 1 == 0 ? 1 : face >> 1 == 1 ? 2 : 0;
        if (axis == fixed) {
            // Down, north and west are the lower side of the cell; up, south and east the higher.
            return face & 1;
        }
        int first = fixed == 0 ? 2 : 0;
        return axis == first ? corner & 1 : corner >> 1 & 1;
    }

    /**
     * Finds which face of a cell four vertices are, and which corner each is.
     *
     * @param positions twelve numbers, x y z for each vertex, relative to the cube's lowest corner
     * @param cellX     the cell the quad belongs to, 0 to 15 each way
     * @return the face in bits 8 to 10 and the four face corners in bits 0 to 7, the first vertex
     *         lowest; or -1 when the vertices are not the four corners of one face of that cell
     */
    public static int locate(float[] positions, int cellX, int cellY, int cellZ) {
        for (int fixed = 0; fixed < 3; fixed++) {
            float plane = positions[fixed];
            if (positions[3 + fixed] != plane || positions[6 + fixed] != plane || positions[9 + fixed] != plane) {
                continue;
            }
            int cell = fixed == 0 ? cellX : fixed == 1 ? cellY : cellZ;
            int side;
            if (plane == cell) {
                side = 0;
            } else if (plane == cell + 1) {
                side = 1;
            } else {
                return -1;
            }
            int first = fixed == 0 ? 2 : 0;
            int second = fixed == 1 ? 2 : 1;
            int cellA = first == 0 ? cellX : cellZ;
            int cellB = second == 1 ? cellY : cellZ;
            int corners = 0;
            int met = 0;
            for (int vertex = 0; vertex < 4; vertex++) {
                float a = positions[vertex * 3 + first] - cellA;
                float b = positions[vertex * 3 + second] - cellB;
                if (a != 0 && a != 1 || b != 0 && b != 1) {
                    return -1;
                }
                int corner = (int) a | (int) b << 1;
                corners |= corner << vertex * 2;
                met |= 1 << corner;
            }
            if (met != 15) {
                return -1;
            }
            int face = (fixed == 1 ? 0 : fixed == 2 ? 2 : 4) + side;
            return face << 8 | corners;
        }
        return -1;
    }

    /**
     * Finds the rectangle of texture four texture coordinates span, and which corner of it each is.
     * The game does not give a quad its sprite's own corners: it draws each a little toward the
     * sprite's middle. So the rectangle is taken from the quad itself.
     *
     * @param coordinates eight numbers, u v for each vertex
     * @param rectangle   receives the least u, the least v, the greatest u and the greatest v
     * @return the four corners, two bits each, the first vertex lowest; or -1 when the coordinates are
     *         not the four corners of one rectangle, each once
     */
    public static int rectangle(float[] coordinates, float[] rectangle) {
        float minU = coordinates[0];
        float maxU = minU;
        float minV = coordinates[1];
        float maxV = minV;
        for (int vertex = 1; vertex < 4; vertex++) {
            minU = Math.min(minU, coordinates[vertex * 2]);
            maxU = Math.max(maxU, coordinates[vertex * 2]);
            minV = Math.min(minV, coordinates[vertex * 2 + 1]);
            maxV = Math.max(maxV, coordinates[vertex * 2 + 1]);
        }
        int corners = 0;
        int met = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            float u = coordinates[vertex * 2];
            float v = coordinates[vertex * 2 + 1];
            if (u != minU && u != maxU || v != minV && v != maxV) {
                return -1;
            }
            int corner = (u == maxU ? 1 : 0) | (v == maxV ? 2 : 0);
            corners |= corner << vertex * 2;
            met |= 1 << corner;
        }
        // All four met also means the rectangle has a width and a height.
        if (met != 15) {
            return -1;
        }
        rectangle[0] = minU;
        rectangle[1] = minV;
        rectangle[2] = maxU;
        rectangle[3] = maxV;
        return corners;
    }
}