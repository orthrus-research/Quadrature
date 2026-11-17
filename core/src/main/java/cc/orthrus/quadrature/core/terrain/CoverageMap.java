package cc.orthrus.quadrature.core.terrain;

/**
 * For every chunk column of the box near terrain holds cubes in, whether the column's surface is
 * loaded and meshed: what something that draws terrain beyond the box, or in place of what has not
 * arrived, needs to know.
 *
 * <p>Where nothing tells near terrain where a column's surface is, it is found from what is known of
 * the column's cubes, from the top of the box down. A cube that has been meshed and draws nothing is taken
 * as air above the ground; the first cube under such air that is not one is the surface cube. The
 * surface band is that cube and the one beneath it.
 *
 * <ul>
 * <li>{@link #NOT_LOADED}: no cube of the column is held.</li>
 * <li>{@link #NO_SURFACE}: cubes are held and no surface is known among them: all that is known is
 * air, so the ground lies below the box; or nothing is known above the highest cube that holds
 * something, so the ground may lie above it.</li>
 * <li>{@link #SURFACE_WAITING}: the surface cube is known and it, or the cube beneath it, is held and
 * not meshed: it waits its turn, or it lacks a neighbor and cannot be meshed.</li>
 * <li>{@link #SURFACE_MESHED}: the surface cube holds a mesh and the cube beneath it does not wait.</li>
 * </ul>
 *
 * <p>Filled anew: {@link #begin}, {@link #cube} for every cube held, {@link #finish}. Or, while the box
 * stays where it is, brought up to date: {@link #update}, {@link #cube} for each cube whose state has
 * changed, with {@link #CUBE_GONE} for one no longer held, then {@link #finish}, which works out again
 * only the columns that were touched.
 */
public final class CoverageMap {

    /** A column's state. */
    public static final byte NOT_LOADED = 0;
    public static final byte NO_SURFACE = 1;
    public static final byte SURFACE_WAITING = 2;
    public static final byte SURFACE_MESHED = 3;

    /** What is known of a cube, as {@link #cube} is told it. A cube not told of is not held. */
    public static final byte CUBE_GONE = 0;
    public static final byte CUBE_HELD = 1;
    /** Meshed, and it draws nothing: air, or solid wholly enclosed. */
    public static final byte CUBE_EMPTY = 2;
    /** Meshed, and it draws something. */
    public static final byte CUBE_MESHED = 3;

    /** The cube height given for a column that has no surface cube. */
    public static final int NONE = Integer.MIN_VALUE;

    private int originX;
    private int originY;
    private int originZ;
    private int across = -1;
    private int upward = -1;
    private int sizeX;
    private int sizeY;
    private byte[] cubes = new byte[0];
    private byte[] columns = new byte[0];
    private boolean[] touched = new boolean[0];
    private boolean all = true;
    private int unanswered;
    private int[] surface = new int[0];
    private int[] bandLow = new int[0];
    private int[] bandHigh = new int[0];
    private final int[] counts = new int[4];
    private long generation;
    private boolean moved;

    /** Starts a filling for the box that reaches {@code across} cubes to each side of an origin cube and {@code upward} up and down. */
    public void begin(int originCubeX, int originCubeY, int originCubeZ, int across, int upward) {
        moved = originCubeX != originX || originCubeZ != originZ || originCubeY != originY || across != this.across
            || upward != this.upward;
        originX = originCubeX;
        originY = originCubeY;
        originZ = originCubeZ;
        if (across != this.across || upward != this.upward) {
            this.across = across;
            this.upward = upward;
            sizeX = 2 * across + 1;
            sizeY = 2 * upward + 1;
            cubes = new byte[sizeX * sizeX * sizeY];
            columns = new byte[sizeX * sizeX];
            bandLow = new int[sizeX * sizeX];
            bandHigh = new int[sizeX * sizeX];
            touched = new boolean[sizeX * sizeX];
            surface = new int[sizeX * sizeX];
        }
        java.util.Arrays.fill(cubes, (byte) 0);
        java.util.Arrays.fill(bandLow, NONE);
        all = true;
    }

    /** Whether the map holds the box that reaches so far about that origin: only then may it be brought up to date. */
    public boolean holds(int originCubeX, int originCubeY, int originCubeZ, int across, int upward) {
        return originCubeX == originX && originCubeY == originY && originCubeZ == originZ && across == this.across
            && upward == this.upward;
    }

    /**
     * Starts bringing the map up to date for the same box: what it knows of every cube and band stays,
     * and only what {@link #cube}, {@link #band} and {@link #noBand} change before {@link #finish} is
     * worked out again.
     */
    public void update() {
        moved = false;
        all = false;
    }

    /**
     * Says where a column's surface band lies, where something knows: the cubes from the lowest to the
     * highest that hold any of the column's surface. A column told of this way is judged by those
     * cubes; one not told of is judged by scanning from the top of the box. Only what is known from
     * loaded cubes should be given: a guess must not make a column read as meshed.
     */
    public void band(int cubeX, int cubeZ, int lowestCubeY, int highestCubeY) {
        int x = cubeX - originX + across;
        int z = cubeZ - originZ + across;
        if (x >= 0 && x < sizeX && z >= 0 && z < sizeX) {
            int column = z * sizeX + x;
            if (bandLow[column] != lowestCubeY || bandHigh[column] != highestCubeY) {
                bandLow[column] = lowestCubeY;
                bandHigh[column] = highestCubeY;
                touched[column] = true;
            }
        }
    }

    /** Says that nothing tells where a column's surface band lies any more: it is judged by scanning again. */
    public void noBand(int cubeX, int cubeZ) {
        int x = cubeX - originX + across;
        int z = cubeZ - originZ + across;
        if (x >= 0 && x < sizeX && z >= 0 && z < sizeX && bandLow[z * sizeX + x] != NONE) {
            bandLow[z * sizeX + x] = NONE;
            touched[z * sizeX + x] = true;
        }
    }

    /** Says what is known of a cube that is held. One outside the box is passed over. */
    public void cube(int cubeX, int cubeY, int cubeZ, byte state) {
        int x = cubeX - originX + across;
        int y = cubeY - originY + upward;
        int z = cubeZ - originZ + across;
        if (x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeX && cubes[(y * sizeX + z) * sizeX + x] != state) {
            cubes[(y * sizeX + z) * sizeX + x] = state;
            touched[z * sizeX + x] = true;
        }
    }

    /**
     * Works out every column's state from the cubes told of.
     *
     * @param maxCubeY the highest cube the world has: nothing lies above it
     */
    public void finish(int maxCubeY) {
        java.util.Arrays.fill(counts, 0);
        unanswered = 0;
        boolean changed = moved;
        for (int z = 0; z < sizeX; z++) {
            for (int x = 0; x < sizeX; x++) {
                int column = z * sizeX + x;
                if (!all && !touched[column]) {
                    // Nothing of this column changed: it is as it was.
                    counts[columns[column]]++;
                    if (columns[column] == SURFACE_MESHED && bandLow[column] == NONE) {
                        unanswered++;
                    }
                    continue;
                }
                touched[column] = false;
                int top = sizeY - 1;
                while (top >= 0 && cubes[(top * sizeX + z) * sizeX + x] == 0) {
                    top--;
                }
                byte state;
                int at = NONE;
                if (top < 0) {
                    state = NOT_LOADED;
                } else if (bandLow[column] != NONE) {
                    // The band is known: the column is as far along as the least of the band's cubes.
                    at = bandHigh[column];
                    boolean any = false;
                    boolean waits = false;
                    boolean missing = false;
                    for (int cubeY = bandLow[column]; cubeY <= bandHigh[column]; cubeY++) {
                        int y = cubeY - originY + upward;
                        byte cube = y < 0 || y >= sizeY ? 0 : cubes[(y * sizeX + z) * sizeX + x];
                        missing |= cube == 0;
                        waits |= cube == CUBE_HELD;
                        any |= cube != 0;
                    }
                    state = !any ? NO_SURFACE : waits || missing ? SURFACE_WAITING : SURFACE_MESHED;
                } else {
                    int y = top;
                    while (y >= 0 && cubes[(y * sizeX + z) * sizeX + x] == CUBE_EMPTY) {
                        y--;
                    }
                    byte found = y < 0 ? 0 : cubes[(y * sizeX + z) * sizeX + x];
                    // Air is known above the cube found, or it is the world's top cube.
                    boolean open = y < top || y - upward + originY >= maxCubeY;
                    if (found == 0 || !open) {
                        state = NO_SURFACE;
                    } else {
                        at = y - upward + originY;
                        byte beneath = y == 0 ? 0 : cubes[((y - 1) * sizeX + z) * sizeX + x];
                        state = found == CUBE_MESHED && beneath != CUBE_HELD ? SURFACE_MESHED : SURFACE_WAITING;
                    }
                }
                changed |= state != columns[column];
                columns[column] = state;
                surface[column] = at;
                counts[state]++;
                if (state == SURFACE_MESHED && bandLow[column] == NONE) {
                    unanswered++;
                }
            }
        }
        if (changed) {
            generation++;
        }
    }

    /** The state of the column of a cube position, or {@link #NOT_LOADED} outside the box. */
    public byte state(int cubeX, int cubeZ) {
        int x = cubeX - originX + across;
        int z = cubeZ - originZ + across;
        return x < 0 || x >= sizeX || z < 0 || z >= sizeX ? NOT_LOADED : columns[z * sizeX + x];
    }

    /** The height of a column's surface cube, in cubes, or {@link #NONE}. */
    public int surfaceCubeY(int cubeX, int cubeZ) {
        int x = cubeX - originX + across;
        int z = cubeZ - originZ + across;
        return x < 0 || x >= sizeX || z < 0 || z >= sizeX ? NONE : surface[z * sizeX + x];
    }

    /** Every column's state, {@link #side} to a row, x fastest, the first at the box's lowest x and z. */
    public byte[] states() {
        return columns;
    }

    /** Columns along a side of the box. */
    public int side() {
        return sizeX;
    }

    public int originCubeX() {
        return originX;
    }

    public int originCubeZ() {
        return originZ;
    }

    /** How many columns are in a state. */
    public int count(byte state) {
        return counts[state];
    }

    /** How many columns have their surface meshed and no band told for them: judged by the scan alone. */
    public int meshedWithoutBand() {
        return unanswered;
    }

    /** Changes whenever a column's state does, or the box moves. */
    public long generation() {
        return generation;
    }
}
