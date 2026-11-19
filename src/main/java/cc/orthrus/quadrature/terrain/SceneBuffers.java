package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.scene.LightScene;
import cc.orthrus.quadrature.core.scene.PublishedScene;
import cc.orthrus.quadrature.core.scene.SceneBricks;
import cc.orthrus.quadrature.core.scene.ScenePalette;

import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The light scene where a shader reads it: the lookup and the bricks, each in a buffer written through
 * the driver and never mapped, as the terrain's pages are, and each also the store of a buffer texture.
 *
 * <p>Each frame what the scene changed is copied, bricks first and no more than a bound of them, then
 * every lookup entry whose brick has been copied. Nothing here changes any state: buffers and textures
 * are made and filled by name.
 */
final class SceneBuffers {

    /** Bricks copied in a frame at most: 37 KiB. */
    private static final int BRICKS_A_FRAME = 64;
    /** From this many changed entries the whole lookup is copied in one call. */
    private static final int WHOLE_LOOKUP = 128;

    private final LightScene scene;
    private final PublishedScene published = new PublishedScene();
    private SceneBricks bricks;
    private int across = -1;
    private int upward = -1;
    private int lookupBuffer;
    private int lookupTexture;
    private int brickBuffer;
    private int brickTexture;
    /** The lookup as it stands in the buffer: an entry enters it only once its brick has been copied. */
    private int[] copied;
    private ByteBuffer lookupBytes;
    private final ByteBuffer brickBytes = ByteBuffer.allocateDirect(SceneBricks.BRICK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer entryBytes = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN);
    private final int[] slots = new int[BRICKS_A_FRAME];
    private int[] indices = new int[1024];
    /** For each brick slot, the entry that names it, so that a brick copied alone can be reported by entry. */
    private int[] entryOfSlot = new int[0];

    private long nanos;
    private long lastNanos;
    private long mostNanos;
    private long frames;
    private long bricksCopied;
    private long entriesCopied;
    private long wholeLookups;
    private String worst = "none";

    /** Cubes taken from the scene in a frame at most, so that a jump of the box is spread over frames. */
    private static final int CUBES_A_FRAME = 512;
    /** Frames between checks of what the buffers hold, where checking is asked for. */
    private static final int CHECK_FRAMES = 600;
    /** Bytes to a state in the state table: a 16-bit number in use and three kept for color. */
    private static final int STATE_BYTES = 8;

    /** Whether each cell's state is published as well as whether it blocks light. */
    private final boolean states;
    /** A diagnostic: whether what the buffers hold is read back and set beside the scene. */
    private final boolean check;
    private final ScenePalette palette = new ScenePalette();
    private final byte[] cellIndices = new byte[LightScene.CELLS];
    private final short[] cellStates = new short[ScenePalette.STATES];
    private final ByteBuffer cellBytes = ByteBuffer.allocateDirect(LightScene.CELLS);
    private final ByteBuffer listBytes = ByteBuffer.allocateDirect(ScenePalette.STATES * 2).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer stateBytes = ByteBuffer.allocateDirect(LightScene.STATES * STATE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private int cellBuffer;
    private int cellTexture;
    private int listBuffer;
    private int listTexture;
    private int stateBuffer;
    private int stateTexture;
    private long statesSeen = -1;
    private long stateTables;
    private long checks;
    private long checkedBricks;
    private long mismatches;

    SceneBuffers(LightScene scene, boolean states, boolean check) {
        this.scene = scene;
        this.states = states;
        this.check = check;
    }

    PublishedScene published() {
        return published;
    }

    /**
     * What the buffers have yet to take from the scene and copy, as the last frame left it: cubes queued, bricks
     * and lookup entries changed and not copied. Read only; 0 before the first frame, when nothing is held either.
     */
    long waiting() {
        return bricks == null ? 0 : (long) bricks.cubesWaiting() + bricks.bricksWaiting() + bricks.entriesWaiting();
    }

    /** Makes the buffers for a box of this reach, dropping any made for another. */
    private void create(int across, int upward) {
        if (lookupBuffer != 0) {
            org.lwjgl.opengl.GL11.glDeleteTextures(lookupTexture);
            org.lwjgl.opengl.GL11.glDeleteTextures(brickTexture);
            org.lwjgl.opengl.GL15.glDeleteBuffers(lookupBuffer);
            org.lwjgl.opengl.GL15.glDeleteBuffers(brickBuffer);
            if (states) {
                org.lwjgl.opengl.GL11.glDeleteTextures(cellTexture);
                org.lwjgl.opengl.GL11.glDeleteTextures(listTexture);
                org.lwjgl.opengl.GL15.glDeleteBuffers(cellBuffer);
                org.lwjgl.opengl.GL15.glDeleteBuffers(listBuffer);
            }
        }
        this.across = across;
        this.upward = upward;
        bricks = new SceneBricks(scene, across, upward);
        int entries = bricks.lookup().length;
        copied = new int[entries];
        entryOfSlot = new int[entries];
        indices = new int[entries];
        lookupBytes = ByteBuffer.allocateDirect(entries * 4).order(ByteOrder.LITTLE_ENDIAN);
        long lookupSize = entries * 4L;
        // Room for a brick for every cube of the box, so no cube is ever without one for want of room.
        long brickSize = (long) entries * SceneBricks.BRICK_BYTES;
        lookupBuffer = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(lookupBuffer, lookupSize, GL44.GL_DYNAMIC_STORAGE_BIT);
        GL45.glClearNamedBufferData(lookupBuffer, GL30.GL_R32UI, GL30.GL_RED_INTEGER, org.lwjgl.opengl.GL11.GL_UNSIGNED_INT,
            (ByteBuffer) null);
        brickBuffer = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(brickBuffer, brickSize, GL44.GL_DYNAMIC_STORAGE_BIT);
        lookupTexture = GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
        GL45.glTextureBuffer(lookupTexture, GL30.GL_R32UI, lookupBuffer);
        brickTexture = GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
        GL45.glTextureBuffer(brickTexture, GL30.GL_R32UI, brickBuffer);
        published.objects(lookupBuffer, lookupTexture, brickBuffer, brickTexture, lookupSize + brickSize);
        published.box(bricks.sizeX(), bricks.sizeY(), bricks.sizeZ());
        if (states) {
            long cellSize = (long) entries * LightScene.CELLS;
            long listSize = (long) entries * ScenePalette.STATES * 2;
            cellBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(cellBuffer, cellSize, GL44.GL_DYNAMIC_STORAGE_BIT);
            listBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(listBuffer, listSize, GL44.GL_DYNAMIC_STORAGE_BIT);
            cellTexture = GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
            GL45.glTextureBuffer(cellTexture, GL30.GL_R8UI, cellBuffer);
            listTexture = GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
            GL45.glTextureBuffer(listTexture, GL30.GL_R16UI, listBuffer);
            if (stateBuffer == 0) {
                stateBuffer = GL45.glCreateBuffers();
                GL45.glNamedBufferStorage(stateBuffer, (long) LightScene.STATES * STATE_BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
                stateTexture = GL45.glCreateTextures(GL31.GL_TEXTURE_BUFFER);
                GL45.glTextureBuffer(stateTexture, GL30.GL_RGBA16UI, stateBuffer);
                statesSeen = -1;
            }
            long stateSize = (long) LightScene.STATES * STATE_BYTES;
            published.states(cellTexture, listTexture, stateTexture, cellSize + listSize + stateSize);
            Quadrature.LOGGER.info("Light scene states: a byte a cell, {} MiB, lists of {} states a cube, {} MiB, and "
                    + "a table of {} KiB.", String.format("%.1f", cellSize / 1048576.0), ScenePalette.STATES,
                String.format("%.1f", listSize / 1048576.0), stateSize >> 10);
        }
        Quadrature.LOGGER.info("Light scene buffers: a lookup of {} by {} by {} cubes, {} KiB, and room for {} "
                + "bricks of {} bytes, {} MiB.", bricks.sizeX(), bricks.sizeY(), bricks.sizeZ(), lookupSize >> 10, entries,
            SceneBricks.BRICK_BYTES, String.format("%.1f", brickSize / 1048576.0));
    }

    /**
     * Brings the buffers toward the scene as it is now, about the cube the view stands in.
     *
     * @param across the view's horizontal reach in cubes, which is the box's
     * @param upward its vertical reach
     */
    void update(int originCubeX, int originCubeY, int originCubeZ, int across, int upward) {
        long start = System.nanoTime();
        if (across != this.across || upward != this.upward) {
            create(across, upward);
        }
        long made = System.nanoTime();
        bricks.update(originCubeX, originCubeY, originCubeZ, CUBES_A_FRAME);
        long updated = System.nanoTime();
        published.beginFrame(originCubeX, originCubeY, originCubeZ, scene.version());
        if (states && statesSeen != scene.statesDefined()) {
            // A state was first seen: the table is small and changes rarely, so it is sent whole.
            statesSeen = scene.statesDefined();
            short[] table = scene.stateTable();
            for (int state = 0; state < LightScene.STATES; state++) {
                stateBytes.putShort(state * STATE_BYTES, (short) (table[state] & 0x1FF));
            }
            GL45.glNamedBufferSubData(stateBuffer, 0, stateBytes);
            stateTables++;
        }

        long tabled = System.nanoTime();
        int[] lookup = bricks.lookup();
        int taken = bricks.takeBricks(slots, BRICKS_A_FRAME);
        long[] words = bricks.bricks();
        for (int i = 0; i < taken; i++) {
            int slot = slots[i];
            brickBytes.clear();
            brickBytes.asLongBuffer().put(words, slot * SceneBricks.BRICK_WORDS, SceneBricks.BRICK_WORDS);
            GL45.glNamedBufferSubData(brickBuffer, (long) slot * SceneBricks.BRICK_BYTES, brickBytes);
            if (states && cells(slot)) {
                GL45.glNamedBufferSubData(cellBuffer, (long) slot * LightScene.CELLS, cellBytes);
                GL45.glNamedBufferSubData(listBuffer, (long) slot * ScenePalette.STATES * 2, listBytes);
            }
        }
        bricksCopied += taken;
        long bricked = System.nanoTime();

        int entries = bricks.takeEntries(indices, indices.length);
        for (int i = 0; i < entries; i++) {
            int index = indices[i];
            copied[index] = lookup[index];
            if ((lookup[index] & SceneBricks.BRICK) != 0 && (lookup[index] & SceneBricks.UNIFORM) == 0) {
                entryOfSlot[lookup[index] & 0xFFFFFF] = index;
            }
        }
        if (entries >= WHOLE_LOOKUP) {
            lookupBytes.clear();
            lookupBytes.asIntBuffer().put(copied);
            GL45.glNamedBufferSubData(lookupBuffer, 0, lookupBytes);
            published.changedEverything();
            wholeLookups++;
        } else {
            for (int i = 0; i < entries; i++) {
                entryBytes.putInt(0, copied[indices[i]]);
                GL45.glNamedBufferSubData(lookupBuffer, indices[i] * 4L, entryBytes);
                published.changed(indices[i]);
            }
            // A brick copied again for a cube whose entry stood: the entry is reported, since what it names changed.
            for (int i = 0; i < taken; i++) {
                int index = entryOfSlot[slots[i]];
                if (copied[index] == (SceneBricks.BRICK | slots[i])) {
                    boolean told = false;
                    for (int j = 0; j < entries && !told; j++) {
                        told = indices[j] == index;
                    }
                    if (!told) {
                        published.changed(index);
                    }
                }
            }
        }
        entriesCopied += entries;
        published.slots(bricks.bricksHeld(), bricks.mostBricksHeld());
        published.endFrame();

        long done = System.nanoTime();
        lastNanos = done - start;
        nanos += lastNanos;
        if (lastNanos > mostNanos) {
            mostNanos = lastNanos;
            worst = String.format("frame %d: making buffers %.3f ms, taking cubes from the scene %.3f ms, the state "
                    + "table %.3f ms, %d bricks %.3f ms, %d entries %.3f ms", frames, (made - start) / 1e6,
                (updated - made) / 1e6, (tabled - updated) / 1e6, taken, (bricked - tabled) / 1e6, entries,
                (done - bricked) / 1e6);
        }
        frames++;
        // The check is a diagnostic and is not part of the time a frame's copying takes.
        if (check && frames % CHECK_FRAMES == 0) {
            check();
        }
    }

    /**
     * Fills the staging buffers with a slot's cells as places in its list of states, and the list.
     *
     * @return false when the slot's cube no longer has cells of its own
     */
    private boolean cells(int slot) {
        long key = bricks.cubeOfSlot(slot);
        short[] cells = scene.cells(cc.orthrus.quadrature.core.terrain.CubeKey.x(key),
            cc.orthrus.quadrature.core.terrain.CubeKey.y(key), cc.orthrus.quadrature.core.terrain.CubeKey.z(key));
        if (cells == null) {
            return false;
        }
        if (palette.build(cells, cellIndices, cellStates) > ScenePalette.STATES) {
            published.clamped();
        }
        cellBytes.clear();
        cellBytes.put(cellIndices).flip();
        listBytes.clear();
        listBytes.asShortBuffer().put(cellStates);
        return true;
    }

    /**
     * A diagnostic: reads back the lookup and a few bricks from the buffers and sets them beside what
     * was copied and beside the scene. A brick still waiting to be copied is passed over.
     */
    private void check() {
        int wrong = 0;
        ByteBuffer back = ByteBuffer.allocateDirect(copied.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        GL45.glGetNamedBufferSubData(lookupBuffer, 0, back);
        for (int i = 0; i < copied.length; i++) {
            if (back.getInt(i * 4) != copied[i]) {
                wrong++;
            }
        }
        int looked = 0;
        ByteBuffer brick = ByteBuffer.allocateDirect(SceneBricks.BRICK_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer cellsBack = ByteBuffer.allocateDirect(LightScene.CELLS);
        ByteBuffer listBack = ByteBuffer.allocateDirect(ScenePalette.STATES * 2).order(ByteOrder.LITTLE_ENDIAN);
        long[] words = bricks.bricks();
        int[] lookup = bricks.lookup();
        for (int i = (int) (checks * 131 % copied.length), tried = 0; tried < copied.length && looked < 16; tried++) {
            int index = (i + tried) % copied.length;
            int entry = copied[index];
            if ((entry & SceneBricks.BRICK) == 0 || (entry & SceneBricks.UNIFORM) != 0 || entry != lookup[index]) {
                continue;
            }
            int slot = entry & 0xFFFFFF;
            long key = bricks.cubeOfSlot(slot);
            int x = cc.orthrus.quadrature.core.terrain.CubeKey.x(key);
            int y = cc.orthrus.quadrature.core.terrain.CubeKey.y(key);
            int z = cc.orthrus.quadrature.core.terrain.CubeKey.z(key);
            long[] blocking = scene.blocking(x, y, z);
            short[] cells = scene.cells(x, y, z);
            if (blocking == null || cells == null || bricks.entry(x, y, z) != entry) {
                continue;
            }
            GL45.glGetNamedBufferSubData(brickBuffer, (long) slot * SceneBricks.BRICK_BYTES, brick);
            boolean stale = false;
            for (int word = 0; word < SceneBricks.BRICK_WORDS && !stale; word++) {
                stale = brick.getLong(word * 8) != words[slot * SceneBricks.BRICK_WORDS + word];
            }
            if (stale) {
                // Changed since it was copied and waiting its turn: not an error.
                continue;
            }
            looked++;
            for (int word = 0; word < SceneBricks.FINE_WORDS; word++) {
                if (brick.getLong(word * 8) != blocking[word]) {
                    wrong++;
                    break;
                }
            }
            if (states) {
                GL45.glGetNamedBufferSubData(cellBuffer, (long) slot * LightScene.CELLS, cellsBack);
                GL45.glGetNamedBufferSubData(listBuffer, (long) slot * ScenePalette.STATES * 2, listBack);
                boolean clamped = palette.build(cells, cellIndices, cellStates) > ScenePalette.STATES;
                for (int cell = 0; cell < LightScene.CELLS && !clamped; cell++) {
                    if (listBack.getShort((cellsBack.get(cell) & 0xFF) * 2) != cells[cell]) {
                        wrong++;
                        break;
                    }
                }
            }
        }
        checks++;
        checkedBricks += looked;
        mismatches += wrong;
        if (wrong > 0 || checks % 10 == 1) {
            Quadrature.LOGGER.info("Light scene check {}: the lookup and {} bricks read back, {} differences; {} in all.",
                checks, looked, wrong, mismatches);
        }
    }

    String summary() {
        if (bricks == null) {
            return "; scene buffers not yet made";
        }
        String line = String.format("; scene buffers %.1f MiB, %d bricks held, %d at most, %d waiting, %d entries waiting, "
                + "%d cubes waiting, generation %d, %d bricks and %d entries copied, the whole lookup %d times, "
                + "CPU %.3f ms last frame, %.3f ms mean, %.3f ms most",
            published.bytes() / 1048576.0, bricks.bricksHeld(), bricks.mostBricksHeld(), bricks.bricksWaiting(),
            bricks.entriesWaiting(), bricks.cubesWaiting(), published.generation(), bricksCopied, entriesCopied, wholeLookups, lastNanos / 1e6,
            frames == 0 ? 0.0 : nanos / 1e6 / frames, mostNanos / 1e6);
        if (states) {
            line += String.format("; states %.1f MiB, table sent %d times, %d cubes clamped", published.stateBytes() / 1048576.0,
                stateTables, published.clampedCubes());
        }
        if (check) {
            line += String.format("; %d checks of %d bricks, %d differences", checks, checkedBricks, mismatches);
        }
        line += "; the frame that copied longest, " + worst;
        return line;
    }
}
