package cc.orthrus.quadrature.core.terrain;

/**
 * The order translucent quads are drawn in within one cube: by the squared distance from the eye to each quad's
 * center, the farthest first, so blending draws each over what lies behind it. Quads at the same distance keep the
 * order they were meshed in. This is the rule the game sorts a render chunk's translucent quads by; its centers are
 * the mean of a quad's four corners.
 *
 * <p>One instance sorts one cube at a time and keeps its working arrays between cubes. Not thread safe.
 */
public final class QuadOrder {

    private float[] distance = new float[0];
    private int[] scratch = new int[0];

    /**
     * Orders quads for an eye.
     *
     * @param centers each quad's center, three floats a quad, in the coordinates the eye is given in
     * @param quads   how many quads
     * @param order   filled with the quads' numbers, the farthest first; at least {@code quads} long
     */
    public void sort(float[] centers, int quads, float eyeX, float eyeY, float eyeZ, int[] order) {
        if (distance.length < quads) {
            distance = new float[quads];
            scratch = new int[quads];
        }
        for (int q = 0; q < quads; q++) {
            float dx = centers[q * 3] - eyeX;
            float dy = centers[q * 3 + 1] - eyeY;
            float dz = centers[q * 3 + 2] - eyeZ;
            distance[q] = dx * dx + dy * dy + dz * dz;
            order[q] = q;
        }
        // A merge sort from runs of one upward: stable, so equal distances keep the meshed order.
        int[] from = order;
        int[] to = scratch;
        for (int width = 1; width < quads; width <<= 1) {
            for (int low = 0; low < quads; low += width << 1) {
                int middle = Math.min(low + width, quads);
                int high = Math.min(low + (width << 1), quads);
                int a = low;
                int b = middle;
                int out = low;
                while (a < middle && b < high) {
                    // Farther first; a tie takes the left run's quad, the one meshed earlier.
                    to[out++] = Float.compare(distance[from[b]], distance[from[a]]) > 0 ? from[b++] : from[a++];
                }
                while (a < middle) {
                    to[out++] = from[a++];
                }
                while (b < high) {
                    to[out++] = from[b++];
                }
            }
            int[] swap = from;
            from = to;
            to = swap;
        }
        if (from != order) {
            System.arraycopy(from, 0, order, 0, quads);
        }
    }
}
