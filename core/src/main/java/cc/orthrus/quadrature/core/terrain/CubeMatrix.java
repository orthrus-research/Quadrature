package cc.orthrus.quadrature.core.terrain;

/**
 * The matrix that places a cube's vertices, composed as the game composes a chunk's: the view's
 * model-view, a move to the cube, then the matrix the game gives each of its render chunks, all in
 * single precision and in that order. Every way of drawing a cube uses this one composition, so they
 * all place a vertex the same way.
 */
public final class CubeMatrix {

    /**
     * The game's render chunk matrix: an enlargement by one part in a million about the point eight
     * blocks before the chunk's lowest corner on each axis. Composed in single precision as a move by
     * eight blocks, the enlargement and the move back, which gives a scale of 1.00000095 and an offset
     * of 7.62939453e-06: the values read from the running game.
     */
    public static final float CHUNK_SCALE = 1.000001F;
    public static final float CHUNK_OFFSET = 8.0F * CHUNK_SCALE - 8.0F;

    /** The numbers a matrix has. */
    public static final int LENGTH = 16;

    private CubeMatrix() {
    }

    /**
     * @param modelView the view's model-view matrix, column-major
     * @param x         the cube's lowest corner relative to the view's origin
     * @param out       receives the cube's matrix, column-major, from {@code at}
     */
    public static void compose(float[] modelView, float x, float y, float z, float[] out, int at) {
        for (int row = 0; row < 4; row++) {
            float c0 = modelView[row];
            float c1 = modelView[4 + row];
            float c2 = modelView[8 + row];
            float moved = c0 * x + c1 * y + c2 * z + modelView[12 + row];
            out[at + row] = c0 * CHUNK_SCALE;
            out[at + 4 + row] = c1 * CHUNK_SCALE;
            out[at + 8 + row] = c2 * CHUNK_SCALE;
            out[at + 12 + row] = c0 * CHUNK_OFFSET + c1 * CHUNK_OFFSET + c2 * CHUNK_OFFSET + moved;
        }
    }
}
