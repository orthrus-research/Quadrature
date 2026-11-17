package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.stage.StageManifest;
import cc.orthrus.quadrature.core.stage.StagePlan;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A test of what no folder handed over has yet asked of the host: a target kept from frame to frame,
 * smaller than the window. Off unless {@code -Dquadrature.stage.selfTest=true}. It writes a small
 * stage of its own into the client's {@code quadrature-stage/self-test/}, reads it back through its
 * manifest as any stage is read, and runs it for four frames in a window of odd sides.
 *
 * <p>The stage's first pass adds an eighth to what its target held the frame before; its second
 * writes that to the display. So the target must hold one eighth after the first frame, two after the
 * second and three after the third, which shows the two textures swapped and the history read; a
 * frame that drops history must bring it back to one eighth, which shows the zeroing; and the target
 * must be half the window's sides rounded up.
 */
final class StageSelfTest {

    static final boolean REQUESTED = Boolean.getBoolean("quadrature.stage.selfTest");

    private static final int WIDTH = 101;
    private static final int HEIGHT = 51;

    private static final String VERTEX = String.join("\n",
        "#version 460 core",
        "void main() {",
        "    gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0, 0.0, 1.0);",
        "}", "");
    private static final String KEEP = String.join("\n",
        "#version 460 core",
        "layout(binding = 0) uniform sampler2D qBefore;",
        "layout(location = 0) out vec4 kept;",
        "void main() {",
        "    kept = texelFetch(qBefore, ivec2(gl_FragCoord.xy), 0) + vec4(0.125);",
        "}", "");
    private static final String SHOW = String.join("\n",
        "#version 460 core",
        "layout(binding = 0) uniform sampler2D qKept;",
        "layout(location = 0) out vec4 shown;",
        "void main() {",
        "    shown = vec4(texture(qKept, gl_FragCoord.xy / vec2(101.0, 51.0)).rgb, 1.0);",
        "}", "");

    private static boolean ran;

    private StageSelfTest() {
    }

    /** Runs the test, the first time it is called with the graphics context current, and never again. */
    static void runOnce() {
        if (!REQUESTED || ran) {
            return;
        }
        ran = true;
        StageRunner.Bindings bindings = new StageRunner.Bindings();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean encode = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        ByteBuffer colorMask = ByteBuffer.allocateDirect(16);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
        StageRunner runner = null;
        try {
            File folder = new File(new File(Minecraft.getMinecraft().gameDir, "quadrature-stage"), "self-test");
            folder.mkdirs();
            write(folder);
            runner = new StageRunner(StageManifest.read(folder.toPath()), Collections.emptySet(), "Stage self-test");
            StagePlan plan = runner.plan();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glColorMask(true, true, true, true);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            runner.size(WIDTH, HEIGHT);

            List<String> wrong = new ArrayList<>();
            int halfWidth = StagePlan.side(WIDTH, 2);
            int halfHeight = StagePlan.side(HEIGHT, 2);
            // Three frames one after another, then one that drops history.
            float[] expected = {0.125f, 0.25f, 0.375f, 0.125f};
            for (int frame = 0; frame < expected.length; frame++) {
                if (frame == 0 || frame == 3) {
                    runner.reset();
                }
                runner.run(plan.passes(), null);
                int kept = runner.texture("kept");
                int width = GL45.glGetTextureLevelParameteri(kept, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL45.glGetTextureLevelParameteri(kept, 0, GL11.GL_TEXTURE_HEIGHT);
                if (width != halfWidth || height != halfHeight) {
                    wrong.add("frame " + frame + ": the kept target is " + width + " by " + height + ", not " + halfWidth + " by "
                        + halfHeight);
                    break;
                }
                ByteBuffer read = ByteBuffer.allocateDirect(width * height * 16).order(ByteOrder.nativeOrder());
                GL45.glGetTextureImage(kept, 0, GL11.GL_RGBA, GL11.GL_FLOAT, read);
                int differing = 0;
                for (int i = 0; i < width * height * 4; i++) {
                    differing += read.getFloat(i * 4) != expected[frame] ? 1 : 0;
                }
                if (differing != 0) {
                    wrong.add("frame " + frame + ": " + differing + " of " + width * height * 4 + " numbers of the kept target are not "
                        + expected[frame] + " (the first is " + read.getFloat(0) + ")");
                }
                ByteBuffer shown = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
                GL45.glGetTextureImage(runner.display(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, shown);
                int level = Math.round(expected[frame] * 255);
                int off = 0;
                for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                    off += Math.abs((shown.get(pixel * 4) & 0xFF) - level) > 1 ? 1 : 0;
                }
                if (off != 0) {
                    wrong.add("frame " + frame + ": " + off + " pixels of the display are not level " + level);
                }
                runner.endFrame();
            }
            int error = GL11.glGetError();
            if (wrong.isEmpty() && error == 0) {
                Quadrature.LOGGER.info("Stage self-test: PASS. A target kept from frame to frame at half the sides of a window of "
                    + "{} by {} is {} by {}, holds what the frame before left, and starts again on a frame that drops history.",
                    WIDTH, HEIGHT, halfWidth, halfHeight);
            } else {
                Quadrature.LOGGER.error("Stage self-test: FAIL. Driver error 0x{}. {}", Integer.toHexString(error),
                    String.join("; ", wrong));
            }
        } catch (Throwable failure) {
            Quadrature.LOGGER.error("Stage self-test: FAIL: it could not be run: {}", failure.toString());
        } finally {
            if (runner != null) {
                runner.delete();
            }
            bindings.restore();
            set(GL11.GL_DEPTH_TEST, depth);
            set(GL11.GL_BLEND, blend);
            set(GL11.GL_CULL_FACE, cull);
            set(GL11.GL_ALPHA_TEST, alpha);
            set(GL11.GL_SCISSOR_TEST, scissor);
            set(GL30.GL_FRAMEBUFFER_SRGB, encode);
            GL11.glDepthMask(depthMask);
            GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
            StateEnvelope.leave();
        }
    }

    private static void set(int capability, boolean on) {
        if (on) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }

    /** Writes the stage's three sources and a manifest that names them. */
    private static void write(File folder) throws java.io.IOException {
        String[][] sources = {{"fullscreen.vert", VERTEX}, {"keep.frag", KEEP}, {"show.frag", SHOW}};
        StringBuilder files = new StringBuilder();
        for (String[] source : sources) {
            byte[] bytes = source[1].getBytes(StandardCharsets.UTF_8);
            Files.write(new File(folder, source[0]).toPath(), bytes);
            files.append(files.length() == 0 ? "" : ",").append("\n  {\"file\": \"").append(source[0])
                .append("\", \"role\": \"program source\", \"format\": \"text/utf-8\", \"shape\": [], \"bytes\": ")
                .append(bytes.length).append(", \"sha256\": \"").append(StageManifest.sha256(bytes)).append("\"}");
        }
        String nearest = "{\"filter\": \"nearest\", \"wrap\": \"clamp to edge\", \"comparison\": \"none\"}";
        String manifest = "{ \"manifest\": " + StageManifest.NEWEST + ", \"frameBlockBytes\": 592, \"teleportResetBlocks\": 64,\n"
            + " \"programs\": { \"keep\": {\"vertex\": \"fullscreen.vert\", \"fragment\": \"keep.frag\"},"
            + " \"show\": {\"vertex\": \"fullscreen.vert\", \"fragment\": \"show.frag\"} },\n"
            + " \"hostInputs\": [], \"assets\": [],\n"
            + " \"targets\": [ {\"name\": \"kept\", \"format\": \"RGBA16F\", \"sizeDivisor\": 2, \"persistent\": true,"
            + " \"clear\": \"zero on reset\", \"sampler\": " + nearest + "} ],\n"
            + " \"passes\": [\n"
            + "  {\"name\": \"keep\", \"program\": \"keep\", \"vertex\": \"fullscreen.vert\", \"fragment\": \"keep.frag\","
            + " \"inputs\": [{\"sampler\": \"qBefore\", \"unit\": 0, \"source\": \"history:kept\"}],"
            + " \"outputs\": [{\"location\": 0, \"target\": \"kept\"}]},\n"
            + "  {\"name\": \"show\", \"program\": \"show\", \"vertex\": \"fullscreen.vert\", \"fragment\": \"show.frag\","
            + " \"inputs\": [{\"sampler\": \"qKept\", \"unit\": 0, \"source\": \"target:kept\"}],"
            + " \"outputs\": [{\"location\": 0, \"target\": \"display\"}]} ],\n"
            + " \"tolerance\": {},\n \"files\": [" + files + "\n ] }\n";
        Files.write(new File(folder, StageManifest.FILE).toPath(), manifest.getBytes(StandardCharsets.UTF_8));
    }
}
