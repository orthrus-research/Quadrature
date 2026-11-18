package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.view.ShadowLayers;
import cc.orthrus.quadrature.depth.DepthCalls;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.imageio.ImageIO;

/**
 * The depth a light's view is drawn into: an array texture of depth alone that is published, a scratch
 * texture of as many layers where a new box is drawn before it is copied over the published one, and a
 * framebuffer with no color that either is attached to a layer at a time.
 *
 * <p>Each layer is 4,096 texels a side. The wide view along the light is a cube that holds every cube the
 * client has: 544 blocks a side at a reach of twelve chunks, so a texel is 0.13 of a block. With
 * {@code -Dquadrature.shadow.near=true} a near box 192 blocks across, 0.047 of a block a texel, is layer
 * 0 and the wide box layer 1; otherwise the wide box is the only layer. Depth is kept in 24 bits: the
 * boxes are orthographic, so depth is linear, and 24 bits divide a box 544 blocks deep into steps of a
 * thirty-thousandth of a block. Each texture takes 4 bytes a texel, 64 MiB a layer. See
 * {@link ShadowLayers}.
 *
 * <p>Layers are drawn a few tiles at a time: the tiles are cleared through the scissor and every cube
 * that may cover them is drawn whole. What such a cube draws beyond the tiles is what is there already,
 * the same geometry at the same depth, so only the tiles named change.
 */
final class ShadowTarget {

    /** The layers drawn: the near one only where {@code quadrature.shadow.near} is set. */
    static final ShadowLayers LAYOUT = new ShadowLayers(Boolean.getBoolean("quadrature.shadow.near"));
    static final int SIDE = ShadowLayers.TEXELS;
    static final int LAYERS = LAYOUT.count();
    /** The bytes the published texture takes: 24 bits of depth stored in 4 bytes a texel, for every layer. */
    static final long BYTES = LAYOUT.bytes();
    /**
     * The polygon offset the depth is drawn with, as {@code factor,units}; none unless
     * {@code quadrature.shadow.polygonOffset} is set. Any other bias is the sampler's.
     */
    static final float[] POLYGON_OFFSET = polygonOffset();

    private static float[] polygonOffset() {
        String[] parts = System.getProperty("quadrature.shadow.polygonOffset", "0,0").split(",");
        try {
            return new float[] {Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim())};
        } catch (RuntimeException e) {
            return new float[] {0f, 0f};
        }
    }

    /** The side of the image the diagnostic writes. */
    private static final int IMAGE_SIDE = 1024;
    private static final int RING = 4;

    private int framebuffer;
    private int texture;
    private int scratch;
    private int compareSampler;
    private int depthSampler;
    private boolean clearPublished = true;

    // What begin reads of the game's state, put back by end.
    private boolean offset;
    private float offsetFactor;
    private float offsetUnits;
    private int depthFunction;
    private boolean depthMask;
    private boolean cull;
    private boolean scissor;
    private final int[] scissorBox = new int[4];
    private final float[] far = {1f};

    private final int[][] stamps = new int[RING][2];
    private final boolean[] stamped = new boolean[RING];
    private int ring = -1;
    private long gpuNanos;
    private long gpuSamples;
    private long lastGpuNanos;
    private long mostGpuNanos;

    private FloatBuffer readback;
    private int images;
    private boolean offsetFound;

    private void create() {
        texture = depthArray();
        scratch = depthArray();
        // Two ways to sample it: compared with a reference and filtered, or the depth itself.
        compareSampler = GL45.glCreateSamplers();
        GL33.glSamplerParameteri(compareSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(compareSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(compareSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(compareSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(compareSampler, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
        GL33.glSamplerParameteri(compareSampler, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        depthSampler = GL45.glCreateSamplers();
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(depthSampler, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
        framebuffer = GL45.glCreateFramebuffers();
        GL45.glNamedFramebufferTextureLayer(framebuffer, GL30.GL_DEPTH_ATTACHMENT, texture, 0, 0);
        GL45.glNamedFramebufferDrawBuffer(framebuffer, GL11.GL_NONE);
        GL45.glNamedFramebufferReadBuffer(framebuffer, GL11.GL_NONE);
        int status = GL45.glCheckNamedFramebufferStatus(framebuffer, GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Shadow framebuffer is incomplete: 0x" + Integer.toHexString(status));
        }
        for (int[] pair : stamps) {
            pair[0] = GL15.glGenQueries();
            pair[1] = GL15.glGenQueries();
        }
        Quadrature.LOGGER.info("Shadow target: {} by {} texels of depth, {} layer{}, {} MiB published and {} MiB of scratch{}.",
            SIDE, SIDE, LAYERS, LAYERS == 1 ? "" : "s", BYTES >> 20, BYTES >> 20, LAYOUT.near()
                ? String.format(" (%d MiB a layer for the near one, %.0f blocks across)", ShadowLayers.layerBytes() >> 20,
                    ShadowLayers.NEAR_SIDE) : "");
    }

    private static int depthArray() {
        int made = GL45.glCreateTextures(GL30.GL_TEXTURE_2D_ARRAY);
        GL45.glTextureStorage3D(made, 1, GL14.GL_DEPTH_COMPONENT24, SIDE, SIDE, LAYERS);
        GL45.glTextureParameteri(made, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(made, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(made, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL45.glTextureParameteri(made, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return made;
    }

    /** The published layers are cleared to the farthest depth at the next {@link #begin}: another world. */
    void clearPublished() {
        clearPublished = true;
    }

    /**
     * Sets the state a depth-only draw of Quadrature's own depends on, before the first layer drawn in
     * a frame. What it changes that the game's draws depend on is read first and put back by
     * {@link #end}. Draws go nowhere until {@link #attach} names a layer.
     */
    void begin() {
        if (framebuffer == 0) {
            create();
        }
        ring = (ring + 1) % RING;
        if (stamped[ring]) {
            // Issued a few frames ago: taken only where the driver has it ready, so nothing waits.
            stamped[ring] = false;
            if (GL15.glGetQueryObjecti(stamps[ring][1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                lastGpuNanos = GL33.glGetQueryObjectui64(stamps[ring][1], GL15.GL_QUERY_RESULT)
                    - GL33.glGetQueryObjectui64(stamps[ring][0], GL15.GL_QUERY_RESULT);
                gpuNanos += lastGpuNanos;
                gpuSamples++;
                mostGpuNanos = Math.max(mostGpuNanos, lastGpuNanos);
                mostGpuNanosSince = Math.max(mostGpuNanosSince, lastGpuNanos);
                if (lastGpuNanos > 1_000_000) {
                    gpuOverOne++;
                }
                if (lastGpuNanos > 2_000_000) {
                    gpuOverTwo++;
                }
            }
        }
        GL33.glQueryCounter(stamps[ring][0], GL33.GL_TIMESTAMP);

        depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL11.glViewport(0, 0, SIDE, SIDE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        if (DepthCalls.active()) {
            // The light's box is written for clip depth from -1 to 1, whatever the frame uses.
            GL45.glClipControl(GL45.GL_LOWER_LEFT, GL45.GL_NEGATIVE_ONE_TO_ONE);
        }
        // A thin model's one face must cast whichever way it is turned to the light.
        GL11.glDisable(GL11.GL_CULL_FACE);
        // What stands nearer the light than the view's box still casts into it.
        GL11.glEnable(GL32.GL_DEPTH_CLAMP);
        // The depth is drawn with the offset set for it and no other: what the game holds at this point
        // is read and put back, never drawn with. An offset left on by the game would move every stored
        // depth by the slope of its surface, which at a low light is most of a block on flat ground.
        offset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
        offsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        offsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        if (offset && (offsetFactor != 0 || offsetUnits != 0) && !offsetFound) {
            offsetFound = true;
            Quadrature.LOGGER.warn("Shadow target: the game holds a polygon offset of {}, {} switched on where the light's depth is "
                + "drawn; the depth is drawn without it. It is said once.", offsetFactor, offsetUnits);
        }
        if (POLYGON_OFFSET[0] != 0 || POLYGON_OFFSET[1] != 0) {
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(POLYGON_OFFSET[0], POLYGON_OFFSET[1]);
        } else {
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        }
        if (clearPublished) {
            clearPublished = false;
            for (int layer = 0; layer < LAYERS; layer++) {
                attach(false, layer);
                GL45.glClearNamedFramebufferfv(framebuffer, GL11.GL_DEPTH, 0, far);
            }
        }
    }

    /** Makes a layer of the published texture or of the scratch the place draws and clears go. */
    void attach(boolean toScratch, int layer) {
        GL45.glNamedFramebufferTextureLayer(framebuffer, GL30.GL_DEPTH_ATTACHMENT, toScratch ? scratch : texture, 0, layer);
    }

    /**
     * Clears rectangles of the layer attached to the farthest depth.
     *
     * @param rectangles x, y, width and height of each, in texels
     */
    void clear(int[] rectangles, int count) {
        if (count == 0) {
            return;
        }
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        for (int i = 0; i < count; i++) {
            GL11.glScissor(rectangles[i * 4], rectangles[i * 4 + 1], rectangles[i * 4 + 2], rectangles[i * 4 + 3]);
            GL45.glClearNamedFramebufferfv(framebuffer, GL11.GL_DEPTH, 0, far);
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    /** Copies a layer of the scratch, drawn whole, over the published layer. */
    void publish(int layer) {
        GL43.glCopyImageSubData(scratch, GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, texture, GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0,
            layer, SIDE, SIDE, 1);
    }

    /**
     * A diagnostic: sets a layer of the scratch beside the published layer, texel by texel. Waits for the
     * driver to finish both, so it is for checks alone.
     *
     * @return how many texels differ by more than a step of 24-bit depth, how many by more than a
     *         thousandth of the depth's span, which rounding cannot account for, and the largest difference
     *         in millionths of the span
     */
    long[] compareWithScratch(int layer) {
        if (readback == null) {
            readback = ByteBuffer.allocateDirect(SIDE * SIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        if (scratchReadback == null) {
            scratchReadback = ByteBuffer.allocateDirect(SIDE * SIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        readback.clear();
        scratchReadback.clear();
        GL45.glGetTextureSubImage(texture, 0, 0, 0, layer, SIDE, SIDE, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, readback);
        GL45.glGetTextureSubImage(scratch, 0, 0, 0, layer, SIDE, SIDE, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT,
            scratchReadback);
        long differ = 0;
        long far = 0;
        float largest = 0;
        // One step of 24-bit depth, with room for rounding in the conversion to float.
        float step = 1.5f / (1 << 24);
        for (int i = 0; i < SIDE * SIDE; i++) {
            float difference = Math.abs(readback.get(i) - scratchReadback.get(i));
            if (difference > step) {
                differ++;
                largest = Math.max(largest, difference);
                if (difference > 1e-3f) {
                    far++;
                }
            }
        }
        return new long[] {differ, far, Math.round(largest * 1e6)};
    }

    private FloatBuffer scratchReadback;

    /** Puts back what {@link #begin} changed. The framebuffer and viewport are the envelope's to restore. */
    void end() {
        GL11.glPolygonOffset(offsetFactor, offsetUnits);
        if (offset) {
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        } else {
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        }
        GL11.glDisable(GL32.GL_DEPTH_CLAMP);
        if (DepthCalls.active()) {
            GL45.glClipControl(GL45.GL_LOWER_LEFT, GL45.GL_ZERO_TO_ONE);
        }
        if (cull) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
        GL11.glDepthMask(depthMask);
        GL11.glDepthFunc(depthFunction);
        GL33.glQueryCounter(stamps[ring][1], GL33.GL_TIMESTAMP);
        stamped[ring] = true;
    }

    /** The published texture's name, or 0 before the first frame anything is drawn. */
    int texture() {
        return texture;
    }

    int compareSampler() {
        return compareSampler;
    }

    int depthSampler() {
        return depthSampler;
    }

    /** GPU time of the last frame that drew, as far as the driver has answered. */
    double gpuMillisLast() {
        return lastGpuNanos / 1e6;
    }

    /** The mean over the frames that drew and whose time the driver has answered. */
    double gpuMillisMean() {
        return gpuSamples == 0 ? 0.0 : gpuNanos / 1e6 / gpuSamples;
    }

    /** All the GPU time measured for the draws so far. */
    double gpuMillisTotal() {
        return gpuNanos / 1e6;
    }

    double gpuMillisMost() {
        return mostGpuNanos / 1e6;
    }

    /** The most GPU time a frame took since this was last asked; asking starts the count again. */
    double takeGpuMillisMostSince() {
        double most = mostGpuNanosSince / 1e6;
        mostGpuNanosSince = 0;
        return most;
    }

    /** Frames whose GPU time was measured above 1 ms, and above 2 ms. */
    long gpuFramesOverOne() {
        return gpuOverOne;
    }

    long gpuFramesOverTwo() {
        return gpuOverTwo;
    }

    private long mostGpuNanosSince;
    private long gpuOverOne;
    private long gpuOverTwo;

    /**
     * A diagnostic: writes a published layer as a gray image, nearer the light brighter and nothing drawn
     * black, each pixel the nearest of the texels it covers.
     *
     * @return the file written, or null when it could not be
     */
    File writeImage(String name, int layer) {
        if (readback == null) {
            readback = ByteBuffer.allocateDirect(SIDE * SIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        readback.clear();
        GL45.glGetTextureSubImage(texture, 0, 0, 0, layer, SIDE, SIDE, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, readback);
        int step = SIDE / IMAGE_SIDE;
        BufferedImage image = new BufferedImage(IMAGE_SIDE, IMAGE_SIDE, BufferedImage.TYPE_BYTE_GRAY);
        byte[] gray = ((java.awt.image.DataBufferByte) image.getRaster().getDataBuffer()).getData();
        long covered = 0;
        float[] reduced = new float[IMAGE_SIDE * IMAGE_SIDE];
        float least = 1f;
        float most = 0f;
        for (int y = 0; y < IMAGE_SIDE; y++) {
            for (int x = 0; x < IMAGE_SIDE; x++) {
                float nearest = 1f;
                for (int dy = 0; dy < step; dy++) {
                    int row = (y * step + dy) * SIDE + x * step;
                    for (int dx = 0; dx < step; dx++) {
                        nearest = Math.min(nearest, readback.get(row + dx));
                    }
                }
                reduced[y * IMAGE_SIDE + x] = nearest;
                if (nearest < 1f) {
                    covered++;
                    least = Math.min(least, nearest);
                    most = Math.max(most, nearest);
                }
            }
        }
        // The grays are spread over the depths that occur, which are a small part of the box's.
        float span = Math.max(most - least, 1e-6f);
        for (int y = 0; y < IMAGE_SIDE; y++) {
            for (int x = 0; x < IMAGE_SIDE; x++) {
                float nearest = reduced[y * IMAGE_SIDE + x];
                // The image's first row is the top; the texture's first row is the bottom.
                gray[(IMAGE_SIDE - 1 - y) * IMAGE_SIDE + x] = (byte) (nearest >= 1f ? 0
                    : 32 + Math.round((most - nearest) / span * 223f));
            }
        }
        File directory = new File(Minecraft.getMinecraft().gameDir, "quadrature-shadow");
        File file = new File(directory, LAYERS == 1 ? String.format("shadow-%02d-%s.png", images++, name)
            : String.format("shadow-%02d-%s-layer%d.png", images++, name, layer));
        try {
            directory.mkdirs();
            ImageIO.write(image, "png", file);
        } catch (java.io.IOException | RuntimeException error) {
            Quadrature.LOGGER.warn("Shadow image not written: {}", error.toString());
            return null;
        }
        Quadrature.LOGGER.info("Shadow image written: {}, {} of {} pixels hold depth, from {} to {} of the box.",
            file.getName(), covered, IMAGE_SIDE * IMAGE_SIDE, String.format("%.4f", least), String.format("%.4f", most));
        return file;
    }
}
