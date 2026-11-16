package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.scene.LightScene;
import cc.orthrus.quadrature.core.scene.PublishedScene;
import cc.orthrus.quadrature.core.scene.SceneBricks;

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

    SceneBuffers(LightScene scene) {
        this.scene = scene;
    }

    PublishedScene published() {
        return published;
    }

    /** Makes the buffers for a box of this reach, dropping any made for another. */
    private void create(int across, int upward) {
        if (lookupBuffer != 0) {
            org.lwjgl.opengl.GL11.glDeleteTextures(lookupTexture);
            org.lwjgl.opengl.GL11.glDeleteTextures(brickTexture);
            org.lwjgl.opengl.GL15.glDeleteBuffers(lookupBuffer);
            org.lwjgl.opengl.GL15.glDeleteBuffers(brickBuffer);
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
        bricks.update(originCubeX, originCubeY, originCubeZ);
        published.beginFrame(originCubeX, originCubeY, originCubeZ, scene.version());

        int[] lookup = bricks.lookup();
        int taken = bricks.takeBricks(slots, BRICKS_A_FRAME);
        long[] words = bricks.bricks();
        for (int i = 0; i < taken; i++) {
            int slot = slots[i];
            brickBytes.clear();
            brickBytes.asLongBuffer().put(words, slot * SceneBricks.BRICK_WORDS, SceneBricks.BRICK_WORDS);
            GL45.glNamedBufferSubData(brickBuffer, (long) slot * SceneBricks.BRICK_BYTES, brickBytes);
        }
        bricksCopied += taken;

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
        published.endFrame();

        lastNanos = System.nanoTime() - start;
        nanos += lastNanos;
        mostNanos = Math.max(mostNanos, lastNanos);
        frames++;
    }

    String summary() {
        if (bricks == null) {
            return "; scene buffers not yet made";
        }
        return String.format("; scene buffers %.1f MiB, %d bricks held, %d waiting, %d entries waiting, generation %d, "
                + "%d bricks and %d entries copied, the whole lookup %d times, CPU %.3f ms last frame, %.3f ms mean, "
                + "%.3f ms most",
            published.bytes() / 1048576.0, bricks.bricksHeld(), bricks.bricksWaiting(), bricks.entriesWaiting(),
            published.generation(), bricksCopied, entriesCopied, wholeLookups, lastNanos / 1e6,
            frames == 0 ? 0.0 : nanos / 1e6 / frames, mostNanos / 1e6);
    }
}
