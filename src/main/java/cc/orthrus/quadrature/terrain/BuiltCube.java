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
    /**
     * The cubes around it the build read ({@link SideReads}): the sides whose blocks it read in the low 27 bits, and
     * those whose light it read in the 27 from bit 32. A change in a touching cube can change this build's mesh only
     * where it read that cube for that kind of thing.
     */
    final long reads;

    BuiltCube(int x, int y, int z, long sequence, boolean empty, boolean any, short links, long geometry, short[] cells,
        long[] blocking, int[] newStates, byte[][] vertices, int[] vertexCounts, int[][] records, int[] recordCounts,
        long[] faceCounts, long[] checkCounts, long[] census, long nanos, long reads) {
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
        this.reads = reads;
    }

    /** The translucent layer's vertices and how many, where own terrain draws it ({@link TranslucentCube#OWN}); null without. */
    private byte[] translucent;
    private int translucentCount;

    void translucent(byte[] vertices, int vertexCount) {
        translucent = vertices;
        translucentCount = vertexCount;
    }

    byte[] translucentVertices() {
        return translucent;
    }

    int translucentCount() {
        return translucentCount;
    }

    /** Each added layer's vertices and how many ({@link CubeMesher#ADDED}); null where the cube draws none. */
    private byte[][] added;
    private int[] addedCounts;

    void added(int layer, int layers, byte[] vertices, int vertexCount) {
        if (added == null) {
            added = new byte[layers][];
            addedCounts = new int[layers];
        }
        added[layer] = vertices;
        addedCounts[layer] = vertexCount;
    }

    byte[] addedVertices(int layer) {
        return added == null ? null : added[layer];
    }

    int addedCount(int layer) {
        return added == null ? 0 : addedCounts[layer];
    }

    /** The sides whose blocks a build read, from {@link #reads}. */
    static int blockSides(long reads) {
        return (int) (reads & 0x7FF_FFFFL);
    }

    /** The sides whose light a build read, from {@link #reads}. */
    static int lightSides(long reads) {
        return (int) (reads >>> 32 & 0x7FF_FFFFL);
    }

    /** What the cube draws, hashed; 0 for a cube that draws nothing. Computed once, where it is first asked. */
    private long content;
    private boolean contentKnown;

    /**
     * A hash of every layer's vertices and records with their counts: two builds of a cube that draw the same
     * have the same value. Asked on the build thread where the diagnostics want it, so the client thread only
     * compares.
     */
    long content() {
        if (!contentKnown) {
            long hash = 17;
            if (any && vertices != null) {
                for (int layer = 0; layer < vertices.length; layer++) {
                    hash = hash * 31 + vertexCounts[layer];
                    hash = hash * 1_000_003 + java.util.Arrays.hashCode(vertices[layer]);
                    hash = hash * 31 + recordCount(layer);
                    hash = hash * 1_000_003 + java.util.Arrays.hashCode(records(layer));
                }
            } else {
                hash = 0;
            }
            if (translucentCount > 0) {
                hash = hash * 31 + translucentCount;
                hash = hash * 1_000_003 + java.util.Arrays.hashCode(translucent);
            }
            if (added != null) {
                for (int layer = 0; layer < added.length; layer++) {
                    hash = hash * 31 + addedCounts[layer];
                    hash = hash * 1_000_003 + java.util.Arrays.hashCode(added[layer]);
                }
            }
            content = hash;
            contentKnown = true;
        }
        return content;
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
