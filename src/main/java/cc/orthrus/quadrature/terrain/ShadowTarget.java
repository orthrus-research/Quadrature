package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.depth.DepthCalls;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.imageio.ImageIO;

/**
 * The depth a light's view is drawn into: one square texture of depth alone, owned by Quadrature, with a
 * framebuffer that has no color.
 *
 * <p>The side is 4,096 texels. The view along the light is a cube centered on the player that holds
 * every cube the client has: 544 blocks a side at a reach of twelve chunks, so a texel is 0.13 of a
 * block. It is one layer of an array texture; boxes of other sizes can be added as further layers. What
 * resolution a shadow needs near the eye is decided when the map is sampled. Depth is kept in 24 bits:
 * the box is orthographic, so depth is linear, and 24 bits divide a box 544 blocks deep into steps of a
 * thirty-thousandth of a block. The texture takes 4 bytes a texel, 64 MiB.
 */
final class ShadowTarget {

    static final int SIDE = 4096;
    /** The texture is an array, so that more boxes of other sizes can be added as layers; one for now. */
    static final int LAYERS = 1;
    /** The bytes the depth texture takes: 24 bits of depth stored in 4 bytes a texel. */
    static final long BYTES = (long) SIDE * SIDE * 4 * LAYERS;
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
    private int compareSampler;
    private int depthSampler;
    private boolean offset;
    private float offsetFactor;
    private float offsetUnits;
    private int depthFunction;
    private boolean cull;
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

    private void create() {
        texture = GL45.glCreateTextures(GL30.GL_TEXTURE_2D_ARRAY);
        GL45.glTextureStorage3D(texture, 1, GL14.GL_DEPTH_COMPONENT24, SIDE, SIDE, LAYERS);
        GL45.glTextureParameteri(texture, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(texture, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(texture, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL45.glTextureParameteri(texture, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
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
        Quadrature.LOGGER.info("Shadow target: {} by {} texels of depth, {} MiB.", SIDE, SIDE, BYTES >> 20);
    }

    /**
     * Makes the target the place draws go, cleared to the farthest depth, with the state a depth-only
     * draw of Quadrature's own depends on. What it changes that the game's draws depend on is read
     * first and put back by {@link #end}.
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
            }
        }
        GL33.glQueryCounter(stamps[ring][0], GL33.GL_TIMESTAMP);

        depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL11.glViewport(0, 0, SIDE, SIDE);
        GL45.glClearNamedFramebufferfv(framebuffer, GL11.GL_DEPTH, 0, far);
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
    }

    private boolean offsetFound;

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
        GL11.glDepthFunc(depthFunction);
        GL33.glQueryCounter(stamps[ring][1], GL33.GL_TIMESTAMP);
        stamped[ring] = true;
    }

    int texture() {
        return texture;
    }

    int compareSampler() {
        return compareSampler;
    }

    int depthSampler() {
        return depthSampler;
    }

    double gpuMillisLast() {
        return lastGpuNanos / 1e6;
    }

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

    /**
     * A diagnostic: writes the depth as a gray image, nearer the light brighter and nothing drawn black,
     * each pixel the nearest of the texels it covers.
     *
     * @return the file written, or null when it could not be
     */
    File writeImage(String name) {
        if (readback == null) {
            readback = ByteBuffer.allocateDirect(SIDE * SIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        readback.clear();
        GL45.glGetTextureSubImage(texture, 0, 0, 0, 0, SIDE, SIDE, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, readback);
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
        File file = new File(directory, String.format("shadow-%02d-%s.png", images++, name));
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
