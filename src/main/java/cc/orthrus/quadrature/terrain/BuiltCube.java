package cc.orthrus.quadrature.terrain;

/**
 * What a build on one of Cubature's threads made of a cube, handed back to the client thread to put
 * in place. It owns copies of everything, so the mesher that made it is free for the next cube.
 */
final class BuiltCube {

    /** The geometry of a mesh made from nothing known. */
    static final long NO_GEOMETRY = Long.MIN_VALUE;

    final int x;
    final int y;
    final int z;
    /** The order the build was handed in, across every cube: a result older than what a cube shows is dropped. */
    final long sequence;
    /** The cube held nothing when its snapshot was taken. */
    final boolean empty;
    /** Whether the cube draws anything in the opaque layers. */
    final boolean any;
    /** Which faces of the cube see each other through it. */
    final short links;
    /**
     * A hash of the block versions of the cube and the six that share a face with it, -1 for one not
     * loaded: what its geometry follows. Two meshes of a cube with the same value have the same shape,
     * however their light differs.
     */
    final long geometry;
    /** For the light scene, or null when none is kept: each cell's state, the opaque cells, and the states first met. */
    final short[] cells;
    final long[] blocking;
    final int[] newStates;
    /** For each layer, the vertices kept as the game wrote them (all of them without face records), and how many. */
    private final byte[][] vertices;
    private final int[] vertexCounts;
    /** For each layer, its face records, {@code FaceRecord.WORDS} words each, and how many; null without them. */
    private final int[][] records;
    private final int[] recordCounts;
    /** What this build added to the face builder's counts, the face check's and the census's; null where not kept. */
    final long[] faceCounts;
    final long[] checkCounts;
    final long[] census;
    /** The time the build took on its thread. */
    final long nanos;

    BuiltCube(int x, int y, int z, long sequence, boolean empty, boolean any, short links, long geometry, short[] cells,
        long[] blocking, int[] newStates, byte[][] vertices, int[] vertexCounts, int[][] records, int[] recordCounts,
        long[] faceCounts, long[] checkCounts, long[] census, long nanos) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.sequence = sequence;
        this.empty = empty;
        this.any = any;
        this.links = links;
        this.geometry = geometry;
        this.cells = cells;
        this.blocking = blocking;
        this.newStates = newStates;
        this.vertices = vertices;
        this.vertexCounts = vertexCounts;
        this.records = records;
        this.recordCounts = recordCounts;
        this.faceCounts = faceCounts;
        this.checkCounts = checkCounts;
        this.census = census;
        this.nanos = nanos;
    }

    /** A layer's kept vertices, exactly their bytes; empty where it has none. */
    byte[] vertices(int layer) {
        return vertices[layer];
    }

    int vertexCount(int layer) {
        return vertexCounts[layer];
    }

    /** A layer's face records; empty where it has none or records are not made. */
    int[] records(int layer) {
        return records == null ? NO_RECORDS : records[layer];
    }

    int recordCount(int layer) {
        return records == null ? 0 : recordCounts[layer];
    }

    private static final int[] NO_RECORDS = new int[0];

    /** A light-scene state as it travels: its number, the light it gives and lets through, and whether it blocks. */
    static int packState(int number, int emission, int transmission, boolean blocks) {
        return number & 0xFFFF | (emission & 15) << 16 | (transmission & 15) << 20 | (blocks ? 1 : 0) << 24;
    }

    static int stateNumber(int packed) {
        return packed & 0xFFFF;
    }

    static int stateEmission(int packed) {
        return packed >> 16 & 15;
    }

    static int stateTransmission(int packed) {
        return packed >> 20 & 15;
    }

    static boolean stateBlocks(int packed) {
        return (packed >> 24 & 1) != 0;
    }
}
