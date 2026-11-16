package cc.orthrus.quadrature.core.scene;

/**
 * What is published each frame about the light scene as a shader reads it: two buffers of unsigned
 * 32-bit integers, each also bound to a buffer texture so that it can be read with {@code texelFetch},
 * the box the lookup covers, and which lookup entries were copied this frame.
 *
 * <p><b>Lookup.</b> One integer for each cube position of a box of {@link #sizeX} by {@link #sizeY} by
 * {@link #sizeZ} cubes about the origin cube. A cube's entry is at its position wrapped by the box's
 * size, {@code (mod(y, sizeY) * sizeZ + mod(z, sizeZ)) * sizeX + mod(x, sizeX)}; a cube further than
 * half the box from the origin cube has no entry. The two highest bits say what the entry is: 0,
 * nothing is known of the cube; 1, every cell holds the block state in the low 16 bits; 2, the cube has
 * a brick at the slot in the low 24 bits.
 *
 * <p><b>Bricks.</b> {@link #BRICK_INTEGERS} integers to a slot. The first 128 hold a bit for each cell
 * that blocks light: cell {@code (y * 16 + z) * 16 + x} is bit {@code cell & 31} of integer
 * {@code cell >> 5}. The last 16 hold a bit for each block of two cells a side in which any cell blocks
 * light, numbered {@code (y / 2 * 8 + z / 2) * 8 + x / 2} the same way.
 *
 * <p>A brick is always copied before the entry that names it.
 */
public final class PublishedScene {

    /** Integers to a brick slot. */
    public static final int BRICK_INTEGERS = SceneBricks.BRICK_BYTES / 4;

    private int lookupBuffer;
    private int lookupTexture;
    private int brickBuffer;
    private int brickTexture;
    private long bytes;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private int originX;
    private int originY;
    private int originZ;
    private long generation;
    private long sceneVersion;
    private int[] changed = new int[256];
    private int changedCount;
    private boolean everything;

    // What the host sets.

    public void objects(int lookupBuffer, int lookupTexture, int brickBuffer, int brickTexture, long bytes) {
        this.lookupBuffer = lookupBuffer;
        this.lookupTexture = lookupTexture;
        this.brickBuffer = brickBuffer;
        this.brickTexture = brickTexture;
        this.bytes = bytes;
    }

    public void box(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
    }

    /** Starts a frame: nothing is yet marked as copied in it. */
    public void beginFrame(int originX, int originY, int originZ, long sceneVersion) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.sceneVersion = sceneVersion;
        changedCount = 0;
        everything = false;
    }

    /** Notes that a lookup entry was copied this frame, itself or the brick it names. */
    public void changed(int entryIndex) {
        if (changedCount == changed.length) {
            changed = java.util.Arrays.copyOf(changed, changedCount * 2);
        }
        changed[changedCount++] = entryIndex;
    }

    /** Notes that the whole lookup was copied this frame. */
    public void changedEverything() {
        everything = true;
    }

    /** Ends a frame: the generation moves on if anything was copied in it. */
    public void endFrame() {
        if (everything || changedCount > 0) {
            generation++;
        }
    }

    // What a shader's host reads.

    public int lookupBuffer() {
        return lookupBuffer;
    }

    /** A buffer texture of unsigned 32-bit integers over the lookup. */
    public int lookupTexture() {
        return lookupTexture;
    }

    public int brickBuffer() {
        return brickBuffer;
    }

    /** A buffer texture of unsigned 32-bit integers over the bricks. */
    public int brickTexture() {
        return brickTexture;
    }

    /** The memory the two buffers take. */
    public long bytes() {
        return bytes;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    /** The cube the box is centered on this frame. */
    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    /** Changes whenever anything was copied to the buffers. */
    public long generation() {
        return generation;
    }

    /** The scene's own counter as of this frame; what is copied may lag behind it. */
    public long sceneVersion() {
        return sceneVersion;
    }

    /** Whether the whole lookup was copied this frame, in which case every entry counts as changed. */
    public boolean everythingChanged() {
        return everything;
    }

    /** How many entries of {@link #changedEntries} are this frame's. */
    public int changedCount() {
        return changedCount;
    }

    /** Where in the lookup each entry copied this frame lies; an entry whose brick alone was copied is among them. */
    public int[] changedEntries() {
        return changed;
    }
}
