package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.view.ShadingTargets;

import net.minecraft.client.Minecraft;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL45;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import javax.imageio.ImageIO;

/**
 * A diagnostic: the four shading targets and their depth written as images, as they stand after
 * distant terrain has drawn into them. Near terrain's own images are written before that and show
 * near terrain alone. Each target is one image of its color and one of its alpha as gray; depth,
 * which is reversed and falls off fast, is written as its fourth root so that far ground can be
 * told from none.
 */
final class TargetImages {

    private static final String[] NAMES = {"A", "B", "C", "D"};

    private TargetImages() {
    }

    /** Writes the images beside a capture named {@code capture} in the game's screenshots. */
    static void write(ShadingTargets targets, String capture) {
        int width = targets.width();
        int height = targets.height();
        String stem = capture.endsWith(".png") ? capture.substring(0, capture.length() - 4) : capture;
        File folder = new File(Minecraft.getMinecraft().gameDir, "screenshots");
        try {
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            for (int target = 0; target < ShadingTargets.TARGETS; target++) {
                pixels.clear();
                GL45.glGetTextureImage(targets.texture(target), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                BufferedImage color = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                BufferedImage alpha = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int at = ((height - 1 - y) * width + x) * 4;
                        int red = pixels.get(at) & 0xFF;
                        int green = pixels.get(at + 1) & 0xFF;
                        int blue = pixels.get(at + 2) & 0xFF;
                        int a = pixels.get(at + 3) & 0xFF;
                        color.setRGB(x, y, red << 16 | green << 8 | blue);
                        alpha.setRGB(x, y, a << 16 | a << 8 | a);
                    }
                }
                ImageIO.write(color, "png", new File(folder, stem + "-target" + NAMES[target] + ".png"));
                ImageIO.write(alpha, "png", new File(folder, stem + "-target" + NAMES[target] + "-alpha.png"));
            }
            FloatBuffer depth = BufferUtils.createFloatBuffer(width * height);
            GL45.glGetTextureImage(targets.depthTexture(), 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float value = depth.get((height - 1 - y) * width + x);
                    int gray = (int) Math.min(255, Math.round(255 * Math.sqrt(Math.sqrt(Math.max(0f, value)))));
                    image.setRGB(x, y, gray << 16 | gray << 8 | gray);
                }
            }
            ImageIO.write(image, "png", new File(folder, stem + "-targetDepth.png"));
            Quadrature.LOGGER.info("Distant terrain: wrote the shading targets beside {}.", capture);
        } catch (IOException | RuntimeException failure) {
            Quadrature.LOGGER.warn("Distant terrain: the shading targets could not be written as images.", failure);
        }
    }
}
