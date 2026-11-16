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
 *
 * <p><b>States</b>, where they are published. Three more buffers, each the store of a buffer texture,
 * tell which block state each cell of a brick's cube holds. Cells: an unsigned byte for each cell of
 * each slot, 4,096 to a slot in the brick's cell order, an index into the slot's list. Lists: 256
 * unsigned 16-bit state numbers to a slot. A cube of more than 256 states is clamped: cells of the
 * states past the 255th name the last place. State table: for each of 65,536 states four unsigned
 * 16-bit numbers, of which the first holds transmission in bits 0 to 3, emission in bits 4 to 7 and
 * whether the state blocks light in bit 8; the other three are kept for color and are zero.
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

    private int cellTexture;
    private int listTexture;
    private int stateTexture;
    private long stateBytes;
    private long clampedCubes;
    private int slotsInUse;
    private int mostSlotsInUse;

    // What the host sets.

    /** Says where the states are published. */
    public void states(int cellTexture, int listTexture, int stateTexture, long bytes) {
        this.cellTexture = cellTexture;
        this.listTexture = listTexture;
        this.stateTexture = stateTexture;
        this.stateBytes = bytes;
    }

    /** Says how many brick slots are in use, and the most there have been. */
    public void slots(int inUse, int most) {
        slotsInUse = inUse;
        mostSlotsInUse = most;
    }

    /** Notes a cube whose states were clamped to 256 when its cells were copied. */
    public void clamped() {
        clampedCubes++;
    }

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

    /** The memory the lookup and brick buffers take. */
    public long bytes() {
        return bytes;
    }

    /** A buffer texture of unsigned bytes: each cell's place in its slot's list. 0 where states are not published. */
    public int cellTexture() {
        return cellTexture;
    }

    /** A buffer texture of unsigned 16-bit integers: each slot's list of state numbers. */
    public int listTexture() {
        return listTexture;
    }

    /** A buffer texture of four unsigned 16-bit integers a state: what each state does to light. */
    public int stateTexture() {
        return stateTexture;
    }

    /** The memory the three state buffers take. */
    public long stateBytes() {
        return stateBytes;
    }

    /** Brick slots in use this frame. */
    public int slotsInUse() {
        return slotsInUse;
    }

    /** The most brick slots ever in use at once. */
    public int mostSlotsInUse() {
        return mostSlotsInUse;
    }

    /** How many times a cube's states were clamped to 256. */
    public long clampedCubes() {
        return clampedCubes;
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
