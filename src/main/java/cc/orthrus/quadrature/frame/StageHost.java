package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.fade.NearFade;
import cc.orthrus.quadrature.core.stage.FrameBlock;
import cc.orthrus.quadrature.core.stage.FrameClock;
import cc.orthrus.quadrature.core.stage.FrameHistory;
import cc.orthrus.quadrature.core.stage.StageManifest;
import cc.orthrus.quadrature.core.stage.StagePlan;
import cc.orthrus.quadrature.core.view.ShadingTargets;
import cc.orthrus.quadrature.core.view.ShadowMaps;
import cc.orthrus.quadrature.core.view.SunPath;
import cc.orthrus.quadrature.core.view.View;
import cc.orthrus.quadrature.terrain.TerrainHook;

import net.minecraft.client.Minecraft;
import net.minecraft.world.World;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * A shading stage handed over in a folder, run on the frame as a stage of its own between the game's
 * opaque terrain and its entities: the passes its manifest lists, in order, reading the targets for
 * shading and the light's depth. Off unless {@code -Dquadrature.stage=true}, which needs the targets,
 * with the folder named by {@code -Dquadrature.stage.dir}.
 *
 * <p>What the stage writes to its display output is shown only with
 * {@code -Dquadrature.stage.show=true}: its color is then written over the whole of the game's
 * picture, sky included, with the game's depth untouched, so that entities, the hand and what the game
 * draws after are drawn over it by the game's own depth. Which pixels are sky is the stage's to decide.
 * Otherwise the player's picture is the game's. {@code -Dquadrature.stage.image=true} lets the result
 * be written as an image where the player stands still, to be looked at without showing it.
 *
 * <p>The folder is data, read through its manifest and run by {@link StageRunner}. The stage is told
 * of the frame through a uniform block laid out by {@link FrameBlock}. Everything bound or changed
 * here is put back as it was found.
 */
public final class StageHost {

    public static final boolean REQUESTED = Boolean.getBoolean("quadrature.stage");
    public static final boolean SHOW = REQUESTED && Boolean.getBoolean("quadrature.stage.show");
    public static final boolean IMAGES = REQUESTED && Boolean.getBoolean("quadrature.stage.image");

    /** The stage's name in the frame's graph. */
    static final String STAGE = "shading";
    /** The hosted stage it runs in front of. */
    static final String BEFORE = "entities";

    /** The textures the host gives a stage, by the names a manifest asks for them. */
    static final Set<String> HOST_INPUTS = new HashSet<>(Arrays.asList("targetA", "targetB", "targetC", "targetD", "depth",
        "lightViewDepth", "lightViewCompare"));
    private static final String[] TARGET_NAMES = {"targetA", "targetB", "targetC", "targetD"};

    /** What shows the result: the display's color over every pixel, alpha as the game leaves it for terrain. */
    private static final String PRESENT_VERTEX = String.join("\n",
        "#version 460 core",
        "void main() {",
        "    gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0, 0.0, 1.0);",
        "}");
    private static final String PRESENT_FRAGMENT = String.join("\n",
        "#version 460 core",
        "layout(binding = 0) uniform sampler2D shown;",
        "layout(location = 0) out vec4 color;",
        "void main() {",
        "    color = vec4(texelFetch(shown, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);",
        "}");

    private static boolean failed;
    private static StageRunner runner;
    private static boolean displayed;
    private static int present;
    private static int presentArray;
    private static int nearest;
    private static int noShadow;
    private static long targetsGeneration = -1;
    private static long runs;
    private static long lastRun = Long.MIN_VALUE;
    /** The passes run each frame: the manifest's, less those of the groups left out. */
    private static java.util.List<StagePlan.Pass> passes;
    private static int images;
    private static final FrameBlock frame = new FrameBlock();
    private static final FrameClock clock = new FrameClock();
    private static final FrameHistory history = new FrameHistory();
    private static final ByteBuffer blockBytes = ByteBuffer.allocateDirect(FrameBlock.BYTES).order(ByteOrder.nativeOrder());
    private static final ByteBuffer colorMask = ByteBuffer.allocateDirect(16);
    private static final double[] sun = new double[3];
    private static final double[] light = new double[3];

    private StageHost() {
    }

    /**
     * Runs the stage on what the frame's terrain drew. Called at the boundary in front of the game's
     * entities; does nothing in a frame whose targets were not drawn.
     */
    static void run() {
        if (!REQUESTED || failed) {
            return;
        }
        ShadingTargets targets = TerrainHook.shadingTargets();
        View view = TerrainHook.mainView();
        long index = TerrainHook.frame();
        if (targets == null || view == null || targets.texture(0) == 0 || targets.drawnFrame() != index) {
            return;
        }
        ShadowMaps shadows = TerrainHook.shadowMaps();
        StageRunner.Bindings bindings = new StageRunner.Bindings();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean encode = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        colorMask.clear();
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
        try {
            if (runner == null) {
                create();
            }
            if (targets.generation() != targetsGeneration || runner.width() != targets.width()
                || runner.height() != targets.height()) {
                runner.size(targets.width(), targets.height());
                targetsGeneration = targets.generation();
                // Targets made anew hold nothing of the frames before.
                history.drop();
                Quadrature.LOGGER.info("Stage: its targets are made for {} by {}: {} MiB, and {} MiB the display's.", targets.width(),
                    targets.height(), String.format("%.1f", runner.plan().targetBytes(targets.width(), targets.height()) / 1048576.0),
                    String.format("%.1f", targets.width() * (long) targets.height() * 4 / 1048576.0));
            }
            Minecraft minecraft = Minecraft.getMinecraft();
            World world = minecraft.world;
            boolean drawn = shadows != null && shadows.texture() != 0;
            if (index != lastRun + 1) {
                // The stage did not run in the frame before: what it kept is of some earlier frame.
                history.drop();
            }
            lastRun = index;
            boolean reset = fill(targets, drawn ? shadows : null, view, index, world, minecraft.getRenderPartialTicks());
            blockBytes.clear();
            frame.write(blockBytes);
            blockBytes.clear();
            runner.block(blockBytes);
            for (int target = 0; target < 4; target++) {
                runner.host(TARGET_NAMES[target], targets.texture(target));
            }
            runner.host("depth", targets.depthTexture());
            runner.host("lightViewDepth", drawn ? shadows.texture() : noShadow);
            runner.host("lightViewCompare", drawn ? shadows.texture() : noShadow);

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glColorMask(true, true, true, true);
            if (reset) {
                runner.reset();
            }
            runner.run(passes, null);

            if (SHOW && displayed) {
                // Over the whole of the game's picture, color alone: the game's depth stays as terrain left it.
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, minecraft.getFramebuffer().framebufferObject);
                GL11.glViewport(0, 0, minecraft.displayWidth, minecraft.displayHeight);
                GL11.glDisable(GL11.GL_BLEND);
                GL45.glBindTextureUnit(0, runner.display());
                GL33.glBindSampler(0, nearest);
                GL30.glBindVertexArray(presentArray);
                GL20.glUseProgram(present);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }
            if (IMAGES && TerrainHook.imagesDue()) {
                probe(drawn ? shadows : null, targets);
            }
            runner.endFrame();
            runs++;
            if (runs == 1) {
                int error = GL11.glGetError();
                Quadrature.LOGGER.info("Stage: the first frame ran, {} passes at {} by {}, light depth {}, shown {}; driver error "
                    + "0x{}.", passes.size(), runner.width(), runner.height(),
                    drawn ? "read" : "not drawn, everything lit", SHOW && displayed, Integer.toHexString(error));
            }
        } catch (Throwable failure) {
            failed = true;
            Quadrature.LOGGER.error("Stage: it could not be run and is off for this session: {}", failure.toString());
        } finally {
            bindings.restore();
            set(GL11.GL_DEPTH_TEST, depth);
            set(GL11.GL_BLEND, blend);
            set(GL11.GL_CULL_FACE, cull);
            set(GL11.GL_ALPHA_TEST, alpha);
            set(GL11.GL_SCISSOR_TEST, scissor);
            set(GL30.GL_FRAMEBUFFER_SRGB, encode);
            GL11.glDepthMask(depthMask);
            GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
            // The program, the vertex array, the framebuffer and the viewport, as the game holds them.
            StateEnvelope.leave();
        }
    }

    /** What a diagnostic pass wrote in the frame images are written of: the pass, the output, its size and its numbers. */
    private static final java.util.List<Object[]> probed = new java.util.ArrayList<>();

    /**
     * For the frame images are about to be written of: runs the manifest's diagnostic passes on this
     * frame's inputs, keeps what each wrote to be written beside the images, and logs what the stage
     * was told of the light, so that a shadow that is missing from the picture can be traced.
     */
    private static void probe(ShadowMaps shadows, ShadingTargets targets) {
        probed.clear();
        for (StagePlan.Pass diagnostic : runner.plan().diagnosticPasses()) {
            runner.run(java.util.Collections.singletonList(diagnostic), pass -> {
                int divisor = runner.plan().divisor(pass);
                int width = StagePlan.side(runner.width(), divisor);
                int height = StagePlan.side(runner.height(), divisor);
                for (StagePlan.Output output : pass.outputs) {
                    ByteBuffer read = ByteBuffer.allocateDirect(width * height * 16).order(ByteOrder.nativeOrder());
                    GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                    GL45.glGetTextureImage(runner.texture(output.target), 0, GL11.GL_RGBA, GL11.GL_FLOAT, read);
                    float[] values = new float[width * height * 4];
                    read.asFloatBuffer().get(values);
                    probed.add(new Object[] {pass.name, output.location, width, height, values});
                }
            });
        }
        StringBuilder matrix = new StringBuilder();
        for (float value : frame.lightViews[0]) {
            matrix.append(matrix.length() == 0 ? "" : " ").append(String.format("%.6g", value));
        }
        float[] drawnToward = shadows == null ? null : shadows.drawnTowardLight();
        Quadrature.LOGGER.info("Stage probe: {} diagnostic outputs kept; toward the sun {} {} {}, toward the light {} {} {}; {} light "
            + "views given; the first, column-major: {}; its scales {} {}; the light's view generation {}, content {}, drawn "
            + "toward {}, {} cubes; half side {}, cloud height {}.", probed.size(), frame.sun[0], frame.sun[1], frame.sun[2],
            frame.light[0], frame.light[1], frame.light[2], frame.frame[3], matrix, frame.lightScales[0][0], frame.lightScales[0][1],
            shadows == null ? "none" : shadows.generation(), shadows == null ? "none" : shadows.contentGeneration(),
            drawnToward == null ? "nothing" : drawnToward[0] + " " + drawnToward[1] + " " + drawnToward[2],
            TerrainHook.lightViewCubes(), frame.eyeMovement[3], frame.cloudDrift[2]);
        if (shadows != null) {
            lightDepth(shadows, targets);
        }
    }

    private static ByteBuffer mapRead;

    /**
     * Says what the light's depth holds in the frame images are written of, and for one pixel low in
     * the middle of the picture, where a tour's views show ground: where it lies in the light's view,
     * its depth there by the matrix the stage was given, and the depth the map holds at that texel.
     * A stored depth well below the reference means something stands between that ground and the light
     * in the map; one a hair below it means the ground shadows itself.
     */
    private static void lightDepth(ShadowMaps shadows, ShadingTargets targets) {
        try {
            int side = shadows.side();
            if (mapRead == null || mapRead.capacity() != side * side * 4) {
                mapRead = ByteBuffer.allocateDirect(side * side * 4).order(ByteOrder.nativeOrder());
            }
            mapRead.clear();
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL45.glGetTextureSubImage(shadows.texture(), 0, 0, 0, 0, side, side, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, mapRead);
            float least = Float.MAX_VALUE;
            float most = -Float.MAX_VALUE;
            long below = 0;
            for (int i = 0; i < side * side; i++) {
                float value = mapRead.getFloat(i * 4);
                least = Math.min(least, value);
                most = Math.max(most, value);
                below += value < 1f ? 1 : 0;
            }
            int x = targets.width() / 2;
            int y = targets.height() / 6;
            ByteBuffer one = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
            GL45.glGetTextureSubImage(targets.depthTexture(), 0, x, y, 0, 1, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, one);
            float depth = one.getFloat(0);
            double[] place = FrameBlock.apply(FrameBlock.doubles(frame.inverseViewProjection), (x + 0.5) / targets.width() * 2 - 1,
                (y + 0.5) / targets.height() * 2 - 1, depth, 1);
            double[] lit = FrameBlock.apply(FrameBlock.doubles(frame.lightViews[0]), place[0] / place[3], place[1] / place[3],
                place[2] / place[3], 1);
            double u = lit[0] * 0.5 + 0.5;
            double v = lit[1] * 0.5 + 0.5;
            int texelX = (int) Math.floor(u * side);
            int texelY = (int) Math.floor(v * side);
            boolean inside = texelX >= 0 && texelX < side && texelY >= 0 && texelY < side;
            Quadrature.LOGGER.info("Stage probe: the light's depth, {} by {}: least {}, greatest {}, {} of {} texels below 1. The pixel "
                + "({}, {}) counted from the bottom left: stored depth {}, {} {} {} blocks from the eye; in the light's view u {} v {}, "
                + "reference depth {}; the map holds {} at texel ({}, {}); the depth's span is {} blocks, so the reference lies {} "
                + "blocks behind what the map holds.", side, side, least, most, below, (long) side * side, x, y, depth,
                String.format("%.2f", place[0] / place[3]), String.format("%.2f", place[1] / place[3]),
                String.format("%.2f", place[2] / place[3]), String.format("%.5f", u), String.format("%.5f", v),
                String.format("%.6f", lit[2]), inside ? String.format("%.6f", mapRead.getFloat((texelY * side + texelX) * 4)) : "nothing",
                texelX, texelY, frame.lightScales[0][1],
                inside ? String.format("%.3f", (lit[2] - mapRead.getFloat((texelY * side + texelX) * 4)) * frame.lightScales[0][1]) : "?");
        } catch (RuntimeException failure) {
            Quadrature.LOGGER.warn("Stage probe: the light's depth could not be read: {}", failure.toString());
        }
    }

    private static void set(int capability, boolean on) {
        if (on) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }

    /**
     * Fills the frame's block from the view, the light and the game.
     *
     * @param shadows the light's depth, or null where it is not drawn: nothing is then in shadow
     * @return whether the frame drops history
     */
    private static boolean fill(ShadingTargets targets, ShadowMaps shadows, View view, long index, World world,
        float partialTicks) {
        double[] viewProjection = FrameBlock.viewProjectionAtEye(view.projection(), view.modelView());
        double[] inverse = FrameBlock.inverse(viewProjection);
        if (inverse == null) {
            throw new IllegalStateException("the view's matrices have no inverse");
        }
        System.arraycopy(FrameBlock.floats(inverse), 0, frame.inverseViewProjection, 0, 16);
        // Where the camera stands: the view's origin moved by the model-view's own translation.
        double[] eye = NearFade.origin(view.originX(), view.originY(), view.originZ(), view.modelView());
        frame.eye(eye[0], eye[1], eye[2]);
        double tilt = sunTilt();
        float angle = world.getCelestialAngle(partialTicks);
        SunPath.towardSun(angle, tilt, sun);
        SunPath.towardLight(angle, tilt, light);
        for (float[] layer : frame.lightViews) {
            Arrays.fill(layer, 0f);
        }
        for (float[] scale : frame.lightScales) {
            Arrays.fill(scale, 0f);
        }
        int layers = 0;
        if (shadows != null) {
            // The map is kept between frames: its matrices, its origin and its light are those it was drawn with.
            double[] origin = shadows.origin();
            layers = Math.min(shadows.layers(), FrameBlock.LIGHT_VIEWS);
            for (int layer = 0; layer < layers; layer++) {
                double[] matrix = FrameBlock.lightViewFromEye(shadows.viewProjection(layer), eye[0] - origin[0], eye[1] - origin[1],
                    eye[2] - origin[2]);
                frame.lightView(layer, FrameBlock.floats(matrix), (float) (shadows.boxSide(layer) / shadows.side()),
                    (float) shadows.boxSide(layer));
            }
            // The light's direction given is this frame's; how far the map lags it is in the matrix alone.
        }
        for (int axis = 0; axis < 3; axis++) {
            frame.sun[axis] = (float) sun[axis];
            frame.light[axis] = (float) light[axis];
        }
        frame.sun[3] = 0;
        frame.light[3] = 0;
        frame.targetSize(targets.width(), targets.height());
        float rain = world.getRainStrength(partialTicks);
        // Time is the real time of frames while the game ran: it drives animation and follows no world clock.
        double seconds = clock.frame(System.nanoTime(), Minecraft.getMinecraft().isGamePaused());
        // No account is kept of how wet the ground is: it is as wet as it rains.
        frame.weather(rain, rain, 1f, seconds);
        int dimension = world.provider.getDimension();
        boolean reset = history.advance(viewProjection, eye[0], eye[1], eye[2], targets.width(), targets.height(), dimension,
            runner.plan().teleportResetBlocks(), seconds, frame);
        // Beside the eye's movement, how far what was written into the targets reaches; beside the
        // clouds' drift, the height the world keeps its clouds at.
        frame.eyeMovement[3] = TerrainHook.targetsFarEdge();
        frame.cloudDrift[2] = world.provider.getCloudHeight();
        // The count of light views is what lets the stage look a shadow up at all: none drawn, none counted.
        frame.frame(index, reset, dimension, layers);
        return reset;
    }

    private static double sunTilt() {
        try {
            return Double.parseDouble(System.getProperty("quadrature.sun.tilt", "0"));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static void create() throws java.io.IOException {
        String named = System.getProperty("quadrature.stage.dir");
        if (named == null || named.isEmpty()) {
            throw new IllegalStateException("no folder is named; set -Dquadrature.stage.dir");
        }
        StageManifest manifest = StageManifest.read(Paths.get(named));
        StageRunner made = new StageRunner(manifest, HOST_INPUTS, "Stage");
        for (StagePlan.Pass pass : made.plan().passes()) {
            displayed |= pass.writesDisplay();
        }
        if (SHOW && !displayed) {
            Quadrature.LOGGER.warn("Stage: no pass writes the display, so there is nothing to show.");
        }
        present = StageRunner.link("Stage", "present", PRESENT_VERTEX, PRESENT_FRAGMENT);
        presentArray = GL45.glCreateVertexArrays();
        nearest = GL45.glCreateSamplers();
        GL33.glSamplerParameteri(nearest, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(nearest, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(nearest, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(nearest, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        // Where no light's depth is drawn: one texel at the farthest, so nothing is in shadow.
        noShadow = GL45.glCreateTextures(GL30.GL_TEXTURE_2D_ARRAY);
        GL45.glTextureStorage3D(noShadow, 1, GL30.GL_DEPTH_COMPONENT32F, 1, 1, 1);
        ByteBuffer far = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        far.putFloat(0, 1f);
        GL45.glTextureSubImage3D(noShadow, 0, 0, 0, 0, 1, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, far);
        // -Dquadrature.stage.skip=group,group leaves out every pass of the groups named, all of a group or none.
        java.util.List<String> skipped = new java.util.ArrayList<>();
        for (String group : System.getProperty("quadrature.stage.skip", "").split(",")) {
            if (!group.trim().isEmpty()) {
                skipped.add(group.trim());
            }
        }
        java.util.List<String> groups = made.plan().groups();
        for (String group : skipped) {
            if (!groups.contains(group)) {
                Quadrature.LOGGER.warn("Stage: no pass belongs to a group named {}; the manifest's groups are {}.", group, groups);
            }
        }
        passes = new java.util.ArrayList<>();
        for (StagePlan.Pass pass : made.plan().passes()) {
            if (pass.group == null || !skipped.contains(pass.group)) {
                passes.add(pass);
            }
        }
        if (passes.size() != made.plan().passes().size()) {
            Quadrature.LOGGER.info("Stage: the groups {} are left out: {} of {} passes run.", skipped, passes.size(),
                made.plan().passes().size());
        }
        made.timePasses();
        runner = made;
        Quadrature.LOGGER.info("Stage: {} passes are compiled from {}, manifest {}; the result is {}.", made.plan().passes().size(),
            named, manifest.version(), SHOW && displayed ? "shown" : "not shown");
    }

    /** A diagnostic: writes what the stage last wrote to its display as an image, into the client's {@code quadrature-stage/}. */
    public static void writeImage(String name) {
        if (runner == null || runner.display() == 0 || failed) {
            return;
        }
        try {
            int width = runner.width();
            int height = runner.height();
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL45.glGetTextureImage(runner.display(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int at = ((height - 1 - y) * width + x) * 4;
                    image.setRGB(x, y, (pixels.get(at) & 0xFF) << 16 | (pixels.get(at + 1) & 0xFF) << 8 | pixels.get(at + 2) & 0xFF);
                }
            }
            File directory = new File(Minecraft.getMinecraft().gameDir, "quadrature-stage");
            directory.mkdirs();
            File file = new File(directory, String.format("stage-%02d-%s.png", images++, name));
            ImageIO.write(image, "png", file);
            Quadrature.LOGGER.info("Stage image written: {}.", file.getName());
            // What the diagnostic passes wrote in the same frame: the numbers as they are, four 32-bit
            // floats a pixel from the bottom row up, little-endian, and the first three as an image to look at.
            for (Object[] probe : probed) {
                int probeWidth = (Integer) probe[2];
                int probeHeight = (Integer) probe[3];
                float[] values = (float[]) probe[4];
                byte[] raw = new byte[values.length * 4];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(values);
                String stem = String.format("stage-%02d-%s-%s-%d", images - 1, name, probe[0], (Integer) probe[1]);
                java.nio.file.Files.write(new File(directory, stem + ".rgba32f").toPath(), raw);
                BufferedImage shown = new BufferedImage(probeWidth, probeHeight, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < probeHeight; y++) {
                    for (int x = 0; x < probeWidth; x++) {
                        int at = ((probeHeight - 1 - y) * probeWidth + x) * 4;
                        shown.setRGB(x, y, level(values[at]) << 16 | level(values[at + 1]) << 8 | level(values[at + 2]));
                    }
                }
                ImageIO.write(shown, "png", new File(directory, stem + ".png"));
            }
            if (!probed.isEmpty()) {
                Quadrature.LOGGER.info("Stage probe written: {} outputs beside {}.", probed.size(), file.getName());
                probed.clear();
            }
        } catch (java.io.IOException | RuntimeException failure) {
            Quadrature.LOGGER.warn("Stage image not written: {}", failure.toString());
        }
    }

    private static int level(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255)));
    }

    /** One line for the timing log, or nothing where the stage is off. */
    public static String summary() {
        return !REQUESTED ? "" : failed ? "; stage failed and is off" : String.format("; stage run in %d frames, history dropped "
            + "in %d, %s%s", runs, history.resets(), SHOW && displayed ? "shown" : "not shown",
            runner == null ? "" : ", its passes at the median: " + runner.passTimes());
    }
}
