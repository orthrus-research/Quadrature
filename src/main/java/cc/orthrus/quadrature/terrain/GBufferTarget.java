package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

/**
 * What the opaque terrain pass writes for shading: four targets of 8 bits a channel, and depth, the
 * size of the window, owned by Quadrature and read by a shading stage. No lit color is written.
 *
 * <ul>
 * <li>A: the world normal as n * 0.5 + 0.5; in alpha, perceptual roughness.</li>
 * <li>B: sky light in red, emission strength in green, block light in blue, each 0 to 1 over the game's
 * 0 to 15; in alpha a response class, 0.375 on terrain and 0 where nothing was written.</li>
 * <li>C: the square root of linear albedo after tint; in alpha, 1 for a conductor.</li>
 * <li>D: for a surface that is not a metal, reflectance in red and subsurface in green; for a metal,
 * its colored reflectance; in alpha, the material's occlusion times the game's vertex occlusion.</li>
 * </ul>
 *
 * <p>Depth is reversed, 32-bit float, in an attachment of the pass's own until the frame's targets are
 * Quadrature's. The targets are cleared when the first layer of a frame is drawn.
 */
final class GBufferTarget {

    static final int TARGETS = 4;

    private int framebuffer;
    private final int[] colors = new int[TARGETS];
    private int depth;
    private int width;
    private int height;
    private long generation;
    private long drawnFrame = -1;
    private boolean alpha;
    private boolean blend;
    private final float[] zero = {0f, 0f, 0f, 0f};
    private final float[] far = {0f};
    private final int[] buffers = {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT0 + 1, GL30.GL_COLOR_ATTACHMENT0 + 2,
        GL30.GL_COLOR_ATTACHMENT0 + 3};
    private ByteBuffer readback;
    private int images;

    /** Makes the targets the size of the window, anew when that has changed. */
    private void ensure() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (framebuffer != 0 && width == minecraft.displayWidth && height == minecraft.displayHeight) {
            return;
        }
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            for (int color : colors) {
                GL11.glDeleteTextures(color);
            }
            GL11.glDeleteTextures(depth);
        }
        width = minecraft.displayWidth;
        height = minecraft.displayHeight;
        framebuffer = GL45.glCreateFramebuffers();
        for (int i = 0; i < TARGETS; i++) {
            colors[i] = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(colors[i], 1, GL11.GL_RGBA8, width, height);
            GL45.glTextureParameteri(colors[i], GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(colors[i], GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL45.glNamedFramebufferTexture(framebuffer, GL30.GL_COLOR_ATTACHMENT0 + i, colors[i], 0);
        }
        depth = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(depth, 1, GL30.GL_DEPTH_COMPONENT32F, width, height);
        GL45.glTextureParameteri(depth, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(depth, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL45.glNamedFramebufferTexture(framebuffer, GL30.GL_DEPTH_ATTACHMENT, depth, 0);
        GL45.glNamedFramebufferDrawBuffers(framebuffer, buffers);
        int status = GL45.glCheckNamedFramebufferStatus(framebuffer, GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("The shading targets' framebuffer is incomplete: 0x" + Integer.toHexString(status));
        }
        readback = null;
        generation++;
        Quadrature.LOGGER.info("Shading targets: {} by {}, four of 8 bits a channel and depth, {} MiB.", width, height,
            String.format("%.1f", bytes() / 1048576.0));
    }

    /** The memory the four targets and the depth take. */
    long bytes() {
        return (long) width * height * (TARGETS * 4 + 4);
    }

    /**
     * Makes the targets the place draws go. What it changes that the game's draws depend on is read
     * first and put back by {@link #end}; the framebuffer and viewport are the envelope's to restore.
     *
     * @param first whether this is the frame's first layer, at which the targets are cleared
     * @param frame the frame's index, kept as the frame the targets were last drawn in
     */
    void begin(boolean first, long frame) {
        ensure();
        if (first) {
            for (int i = 0; i < TARGETS; i++) {
                GL45.glClearNamedFramebufferfv(framebuffer, GL11.GL_COLOR, i, zero);
            }
            // Depth is reversed: the farthest is 0.
            GL45.glClearNamedFramebufferfv(framebuffer, GL11.GL_DEPTH, 0, far);
            drawnFrame = frame;
        }
        // The game's alpha test would judge a fragment by the first target's alpha, which is roughness.
        alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        if (alpha) {
            GL11.glDisable(GL11.GL_ALPHA_TEST);
        }
        // What is written is no color: nothing may be mixed with what a target holds.
        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        if (blend) {
            GL11.glDisable(GL11.GL_BLEND);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL11.glViewport(0, 0, width, height);
    }

    /** Puts back what {@link #begin} changed. */
    void end() {
        if (alpha) {
            GL11.glEnable(GL11.GL_ALPHA_TEST);
        }
        if (blend) {
            GL11.glEnable(GL11.GL_BLEND);
        }
    }

    /** The framebuffer the targets and the depth are attached to. */
    int framebuffer() {
        return framebuffer;
    }

    int texture(int target) {
        return colors[target];
    }

    int depthTexture() {
        return depth;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Moves on whenever the textures are made anew, as when the window changes size; not from frame to frame. */
    long generation() {
        return generation;
    }

    /** The frame the targets were last cleared and drawn in, or -1. */
    long drawnFrame() {
        return drawnFrame;
    }

    /** A diagnostic: writes each target as an image, color as it is stored and alpha as a gray image beside it. */
    void writeImages(String name) {
        if (framebuffer == 0) {
            return;
        }
        if (readback == null) {
            readback = ByteBuffer.allocateDirect(width * height * 4);
        }
        File directory = new File(Minecraft.getMinecraft().gameDir, "quadrature-gbuffer");
        directory.mkdirs();
        String[] letters = {"A", "B", "C", "D"};
        for (int target = 0; target < TARGETS; target++) {
            readback.clear();
            GL45.glGetTextureImage(colors[target], 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, readback);
            BufferedImage color = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            BufferedImage alphaImage = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int at = ((height - 1 - y) * width + x) * 4;
                    int red = readback.get(at) & 0xFF;
                    int green = readback.get(at + 1) & 0xFF;
                    int blue = readback.get(at + 2) & 0xFF;
                    color.setRGB(x, y, red << 16 | green << 8 | blue);
                    alphaImage.getRaster().setSample(x, y, 0, readback.get(at + 3) & 0xFF);
                }
            }
            try {
                ImageIO.write(color, "png", new File(directory, String.format("gbuffer-%02d-%s-%s.png", images, name, letters[target])));
                ImageIO.write(alphaImage, "png",
                    new File(directory, String.format("gbuffer-%02d-%s-%s-alpha.png", images, name, letters[target])));
            } catch (java.io.IOException | RuntimeException error) {
                Quadrature.LOGGER.warn("Shading target image not written: {}", error.toString());
                return;
            }
        }
        Quadrature.LOGGER.info("Shading target images written: set {} at {}.", images, name);
        images++;
    }
}
