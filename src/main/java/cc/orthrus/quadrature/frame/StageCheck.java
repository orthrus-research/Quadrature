package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.stage.FrameBlock;
import cc.orthrus.quadrature.core.stage.StageCompare;
import cc.orthrus.quadrature.core.stage.StageManifest;
import cc.orthrus.quadrature.core.stage.StagePlan;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * A shading stage handed over in a folder, run once on inputs that came with it and set beside the
 * outputs that came with it. Off unless {@code -Dquadrature.stage.check=true}, with the folder named
 * by {@code -Dquadrature.stage.dir}. It needs no terrain and touches none of the frame's own targets:
 * every texture, buffer and framebuffer it uses is made here and deleted here.
 *
 * <p>The folder is data. Files are read through its manifest, which checks each one's size and hash.
 * The passes are run by {@link StageRunner}, as the live stage runs them, with a file standing for
 * each texture the host would give and for the frame's block. Each check the manifest lists is made
 * right after its pass has drawn: the output is read back and compared with the expected file, by the
 * tolerance and leaving out the pixels of the mask the check names. Diagnostic passes are run after
 * the frame's own. PASS or FAIL is logged with counts, and what differed is written into the client's
 * {@code quadrature-stage/} for whoever wrote the stage.
 */
final class StageCheck {

    static final boolean REQUESTED = Boolean.getBoolean("quadrature.stage.check");

    private static boolean ran;

    /** What one check found, with what it compared. */
    private static final class Made {
        final StagePlan.Check check;
        final StageCompare.Result result;
        final int width;
        final int height;
        final float[] gotFloats;
        final float[] wantFloats;
        final byte[] gotBytes;
        final byte[] wantBytes;
        final String rule;

        Made(StagePlan.Check check, StageCompare.Result result, int width, int height, float[] gotFloats, float[] wantFloats,
            byte[] gotBytes, byte[] wantBytes, String rule) {
            this.check = check;
            this.result = result;
            this.width = width;
            this.height = height;
            this.gotFloats = gotFloats;
            this.wantFloats = wantFloats;
            this.gotBytes = gotBytes;
            this.wantBytes = wantBytes;
            this.rule = rule;
        }
    }

    private StageCheck() {
    }

    /** Runs the check, the first time it is called with the graphics context current, and never again. */
    static void runOnce() {
        if (!REQUESTED || ran) {
            return;
        }
        ran = true;
        String named = System.getProperty("quadrature.stage.dir");
        if (named == null || named.isEmpty()) {
            Quadrature.LOGGER.error("Stage check: FAIL: no folder is named; set -Dquadrature.stage.dir.");
            return;
        }
        Map<String, Integer> textures = new HashMap<>();
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
            Path folder = Paths.get(named);
            StageManifest manifest = StageManifest.read(folder);
            Quadrature.LOGGER.info("Stage check: manifest {} of {}, {} files, programs {}, tolerances as stated {}; list of kinds "
                + "revision {}.", manifest.version(), folder, manifest.files().size(), manifest.programs().keySet(),
                manifest.tolerance(), manifest.materialKindsRevision());
            if (manifest.plan() == null) {
                throw new IllegalStateException("the manifest is of version " + manifest.version() + ", which does not say what is "
                    + "to be run; version " + StageManifest.PLANNED_FROM + " is checked");
            }
            StagePlan plan = manifest.plan();
            if (plan.checks().isEmpty()) {
                throw new IllegalStateException("the manifest lists no check");
            }
            // In a check every texture the manifest asks the host for is given, from the file it names.
            runner = new StageRunner(manifest, plan.hostInputs().keySet(), "Stage check");

            // The window the files were made for: the size of an input that is the window's own size.
            int width = 0;
            int height = 0;
            for (StagePlan.HostInput input : plan.hostInputs().values()) {
                StageManifest.Entry entry = manifest.files().get(input.file);
                if (input.sizeDivisor == 1 && entry.shape.length >= 2) {
                    height = entry.shape[0];
                    width = entry.shape[1];
                    break;
                }
            }
            if (width == 0) {
                throw new IllegalStateException("no input of the manifest gives the window's size");
            }

            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            int lightDepth = 0;
            byte[] lightFile = null;
            int lightRows = 0;
            int lightColumns = 0;
            for (StagePlan.HostInput input : plan.hostInputs().values()) {
                Integer texture = textures.get(input.file);
                if (texture == null) {
                    StageManifest.Entry entry = manifest.files().get(input.file);
                    if (entry.shape.length < 2) {
                        throw new IllegalStateException("the file of " + input.name + " has no rows and columns");
                    }
                    int rows = entry.shape[0];
                    int columns = entry.shape[1];
                    byte[] file = manifest.load(input.file);
                    String format = input.format.toLowerCase(java.util.Locale.ROOT);
                    if (format.contains("array")) {
                        file = lightDepth(file, rows, columns);
                        texture = GL45.glCreateTextures(GL30.GL_TEXTURE_2D_ARRAY);
                        GL45.glTextureStorage3D(texture, 1, GL30.GL_DEPTH_COMPONENT32F, columns, rows, 1);
                        GL45.glTextureSubImage3D(texture, 0, 0, 0, 0, columns, rows, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT,
                            direct(file));
                        lightDepth = texture;
                        lightFile = file;
                        lightRows = rows;
                        lightColumns = columns;
                    } else if (format.contains("depth")) {
                        texture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
                        GL45.glTextureStorage2D(texture, 1, GL30.GL_DEPTH_COMPONENT32F, columns, rows);
                        GL45.glTextureSubImage2D(texture, 0, 0, 0, columns, rows, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, direct(file));
                    } else if (format.equals("rgba8")) {
                        texture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
                        GL45.glTextureStorage2D(texture, 1, GL11.GL_RGBA8, columns, rows);
                        GL45.glTextureSubImage2D(texture, 0, 0, 0, columns, rows, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, direct(file));
                    } else {
                        throw new IllegalStateException("host input " + input.name + " has a format no texture is made for: "
                            + input.format);
                    }
                    textures.put(input.file, texture);
                }
                runner.host(input.name, texture);
            }
            runner.size(width, height);

            if (plan.frames().isEmpty()) {
                throw new IllegalStateException("the manifest names no file for the frame's block");
            }

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glColorMask(true, true, true, true);
            if (lightDepth != 0) {
                StagePlan.HostInput plain = null;
                StagePlan.HostInput comparing = null;
                for (StagePlan.HostInput input : plan.hostInputs().values()) {
                    if (textures.get(input.file) == lightDepth) {
                        if (input.sampler.comparison == StagePlan.Comparison.NONE) {
                            plain = input;
                        } else {
                            comparing = input;
                        }
                    }
                }
                if (plain != null && comparing != null) {
                    diagnose(lightDepth, runner.sampler(plain.sampler, false), runner.sampler(comparing.sampler, false), lightFile,
                        lightRows, lightColumns);
                }
            }
            List<Made> made = new ArrayList<>();
            StageRunner running = runner;
            int[] frameNow = new int[1];
            java.util.function.Consumer<StagePlan.Pass> after = pass -> {
                for (StagePlan.Check check : plan.checks()) {
                    if (check.pass.equals(pass.name) && check.frame == frameNow[0]) {
                        try {
                            made.add(compare(manifest, running, pass, check));
                        } catch (java.io.IOException unread) {
                            throw new IllegalStateException(unread.getMessage());
                        }
                    }
                }
                if (pass.diagnostic && pass.name.equals("shadowProbe")) {
                    shadowProbe(running, pass);
                }
            };
            // The frames the manifest lists, one after another as the live stage runs them: what a target
            // kept is carried from each to the next, swap and all.
            int frames = plan.frames().size();
            for (int frame = 0; frame < frames; frame++) {
                frameNow[0] = frame;
                byte[] block = manifest.load(plan.frames().get(frame));
                if (block.length != FrameBlock.BYTES) {
                    throw new IllegalStateException("the block of frame " + frame + " is " + block.length + " bytes, not "
                        + FrameBlock.BYTES);
                }
                runner.block(direct(block));
                ByteBuffer held = runner.blockHeld();
                blockHeld(held);
                // A frame whose block says history is dropped starts as the live stage starts such a frame.
                if (held.getInt(FrameBlock.OFFSET_FRAME + 4) != 0) {
                    runner.reset();
                }
                runner.run(plan.passes(), after);
                // A diagnostic pass is run after the frame a check of it names, and all of them after the last.
                for (StagePlan.Pass diagnostic : plan.diagnosticPasses()) {
                    boolean due = frame == frames - 1;
                    for (StagePlan.Check check : plan.checks()) {
                        due |= check.pass.equals(diagnostic.name) && check.frame == frame;
                    }
                    if (due) {
                        runner.run(Collections.singletonList(diagnostic), after);
                    }
                }
                if (frame < frames - 1) {
                    runner.endFrame();
                }
            }
            GL11.glFinish();
            int error = GL11.glGetError();

            boolean passes = error == 0 && made.size() == plan.checks().size();
            StringBuilder verdict = new StringBuilder();
            for (Made one : made) {
                passes &= one.result.passes();
                verdict.append(String.format(" %s output %d%s (%s) %s.", one.check.pass, one.check.output,
                    frames == 1 ? "" : " after frame " + one.check.frame, one.rule, one.result));
            }
            String line = String.format("Stage check: %s.%s Driver error 0x%s. %d of %d checks made, at %d by %d.",
                passes ? "PASS" : "FAIL", verdict, Integer.toHexString(error), made.size(), plan.checks().size(), width, height);
            if (passes) {
                Quadrature.LOGGER.info(line);
            } else {
                Quadrature.LOGGER.error(line);
            }
            report(line, made);
        } catch (Throwable failure) {
            Quadrature.LOGGER.error("Stage check: FAIL: it could not be run: {}", failure.toString());
        } finally {
            // Out again: everything made here deleted, everything bound here put back as it was found.
            GL20.glUseProgram(0);
            if (runner != null) {
                runner.delete();
            }
            bindings.restore();
            for (int texture : textures.values()) {
                GL11.glDeleteTextures(texture);
            }
            set(GL11.GL_DEPTH_TEST, depth);
            set(GL11.GL_BLEND, blend);
            set(GL11.GL_CULL_FACE, cull);
            set(GL11.GL_ALPHA_TEST, alpha);
            set(GL11.GL_SCISSOR_TEST, scissor);
            set(GL30.GL_FRAMEBUFFER_SRGB, encode);
            GL11.glDepthMask(depthMask);
            GL11.glColorMask(colorMask.get(0) != 0, colorMask.get(1) != 0, colorMask.get(2) != 0, colorMask.get(3) != 0);
            // The framebuffer, the viewport, the vertex array and the pixel store, as the game holds them.
            StateEnvelope.leave();
        }
    }

    /** Reads a checked output back and sets it beside what the check expects. */
    private static Made compare(StageManifest manifest, StageRunner runner, StagePlan.Pass pass, StagePlan.Check check)
        throws java.io.IOException {
        StagePlan plan = runner.plan();
        int divisor = plan.divisor(pass);
        int width = StagePlan.side(runner.width(), divisor);
        int height = StagePlan.side(runner.height(), divisor);
        int texture = runner.texture(StagePlan.target(pass, check.output));
        byte[] mask = check.mask == null ? null : manifest.load(check.mask);
        if (mask != null && mask.length != width * height) {
            throw new IllegalStateException("the mask of the check of " + check.pass + " is " + mask.length + " bytes; the output "
                + "has " + width * height + " pixels");
        }
        byte[] expected = manifest.load(check.expected);
        String key = check.tolerance;
        if (manifest.toleranceByLevels(key)) {
            if (expected.length != width * height * 4) {
                throw new IllegalStateException("what the check of " + check.pass + " expects is not the output's size");
            }
            ByteBuffer got = ByteBuffer.allocateDirect(width * height * 4);
            GL45.glGetTextureImage(texture, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, got);
            byte[] values = new byte[width * height * 4];
            got.get(values);
            int levels = (int) manifest.toleranceNumber(key, "maxCodeValueDifference", StageCompare.DISPLAY_LEVELS);
            boolean alphaCompared = manifest.toleranceFlag(key, "alphaCompared", true);
            return new Made(check, StageCompare.display(values, expected, mask, manifest.maskComparedValue(), width, height, levels,
                alphaCompared), width, height, null, null, values, expected,
                "within " + levels + " levels, alpha " + (alphaCompared ? "compared" : "not compared"));
        }
        if (expected.length != width * height * 16) {
            throw new IllegalStateException("what the check of " + check.pass + " expects is not the output's size");
        }
        ByteBuffer got = ByteBuffer.allocateDirect(width * height * 16).order(ByteOrder.nativeOrder());
        GL45.glGetTextureImage(texture, 0, GL11.GL_RGBA, GL11.GL_FLOAT, got);
        float[] values = new float[width * height * 4];
        got.asFloatBuffer().get(values);
        float[] want = StageCompare.floats(expected);
        double absolute = manifest.toleranceNumber(key, "absolute", StageCompare.RADIANCE_ABSOLUTE);
        double relative = manifest.toleranceNumber(key, "relative", StageCompare.RADIANCE_RELATIVE);
        boolean alphaExact = manifest.toleranceFlag(key, "alphaExact", true);
        return new Made(check, StageCompare.radiance(values, want, mask, manifest.maskComparedValue(), width, height, absolute,
            relative, alphaExact), width, height, values, want, null, null, String.format("within %.4g + %.3g of expected, alpha %s",
            absolute, relative, alphaExact ? "exact" : "as color"));
    }

    /** Says what the driver holds of the frame's block: the first light view, its scales, the light and the frame. */
    private static void blockHeld(ByteBuffer held) {
        StringBuilder matrix = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            matrix.append(i == 0 ? "" : " ").append(String.format("%.5g", held.getFloat(FrameBlock.OFFSET_LIGHT_VIEWS + i * 4)));
        }
        int views = 0;
        for (int view = 0; view < FrameBlock.LIGHT_VIEWS; view++) {
            boolean any = false;
            for (int i = 0; i < 16; i++) {
                any |= held.getFloat(FrameBlock.OFFSET_LIGHT_VIEWS + view * 64 + i * 4) != 0f;
            }
            views += any ? 1 : 0;
        }
        int scales = FrameBlock.OFFSET_LIGHT_SCALES;
        int light = FrameBlock.OFFSET_LIGHT;
        int frame = FrameBlock.OFFSET_FRAME;
        Quadrature.LOGGER.info("Stage check: the frame block as the driver holds it: {} light views that are not all zeros; the "
            + "first, column-major: {}; its scales {} {} {} {}; light direction {} {} {}; frame {} {} {} {}; eye movement {} {} {}; "
            + "cloud drift {} {}.", views, matrix, held.getFloat(scales), held.getFloat(scales + 4), held.getFloat(scales + 8),
            held.getFloat(scales + 12), held.getFloat(light), held.getFloat(light + 4), held.getFloat(light + 8), held.getInt(frame),
            held.getInt(frame + 4), held.getInt(frame + 8), held.getInt(frame + 12), held.getFloat(FrameBlock.OFFSET_EYE_MOVEMENT),
            held.getFloat(FrameBlock.OFFSET_EYE_MOVEMENT + 4), held.getFloat(FrameBlock.OFFSET_EYE_MOVEMENT + 8),
            held.getFloat(FrameBlock.OFFSET_CLOUD_DRIFT), held.getFloat(FrameBlock.OFFSET_CLOUD_DRIFT + 4));
    }

    /** {@code -Dquadrature.stage.shadowRows=flip}: a diagnostic that hands the light's depth over with its rows in the other order. */
    private static final boolean FLIP_SHADOW_ROWS = "flip".equals(System.getProperty("quadrature.stage.shadowRows"));

    /** Says what the light's depth holds, and turns its rows about where that is asked for. */
    private static byte[] lightDepth(byte[] file, int rows, int columns) {
        java.nio.FloatBuffer values = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float least = Float.MAX_VALUE;
        float most = -Float.MAX_VALUE;
        int below = 0;
        int leastAt = 0;
        for (int i = 0; i < values.remaining(); i++) {
            float value = values.get(i);
            if (value < least) {
                least = value;
                leastAt = i;
            }
            most = Math.max(most, value);
            below += value < 1f ? 1 : 0;
        }
        Quadrature.LOGGER.info("Stage check: the light's depth, {} by {}: least {} at column {}, row {} of the file, greatest {}, "
            + "{} of {} texels below 1; rows handed over {}.", columns, rows, least, leastAt % columns, leastAt / columns, most, below,
            rows * columns, FLIP_SHADOW_ROWS ? "in the other order" : "as they lie in the file");
        if (!FLIP_SHADOW_ROWS) {
            return file;
        }
        byte[] turned = new byte[file.length];
        int row = columns * 4;
        for (int y = 0; y < rows; y++) {
            System.arraycopy(file, y * row, turned, (rows - 1 - y) * row, row);
        }
        return turned;
    }

    /** A probe of Quadrature's own: reads the light's depth through both samplers at one place and writes what it reads. */
    private static final String PROBE_FRAGMENT = String.join("\n",
        "#version 460 core",
        "layout(binding = 6) uniform sampler2DArray raw;",
        "layout(binding = 7) uniform sampler2DArrayShadow compared;",
        "uniform vec2 at;",
        "layout(location = 0) out vec4 read;",
        "void main() {",
        "    float depth = texture(raw, vec3(at, 0.0)).r;",
        "    // A reference nearer the light than what is stored passes less-or-equal; one farther does not.",
        "    read = vec4(depth, texture(compared, vec4(at, 0.0, depth - 0.01)), texture(compared, vec4(at, 0.0, depth + 0.01)), 1.0);",
        "}");
    private static final String PROBE_VERTEX = String.join("\n",
        "#version 460 core",
        "void main() {",
        "    gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0, 0.0, 1.0);",
        "}");

    /**
     * Logs what bears on whether a stage can find a shadow, apart from the stage's own programs:
     * whether the texture is complete, whether it holds what the file holds, and what a probe of
     * Quadrature's own reads through the two samplers the manifest asks for.
     */
    private static void diagnose(int lightDepth, int plain, int comparing, byte[] file, int rows, int columns) {
        int probe = 0;
        int target = 0;
        int framebuffer = 0;
        int vertexArray = 0;
        try {
            // What decides whether the texture is complete: its range of levels is its own, whatever sampler reads it.
            Quadrature.LOGGER.info("Stage check: the light's depth texture: {} by {} by {} of format 0x{}, base level {}, greatest "
                + "level {}, storage {} with {} levels; read through a sampler with compare mode 0x{} and filter 0x{}, and one with "
                + "compare mode 0x{}, function 0x{} and filter 0x{}.",
                GL45.glGetTextureLevelParameteri(lightDepth, 0, GL11.GL_TEXTURE_WIDTH),
                GL45.glGetTextureLevelParameteri(lightDepth, 0, GL11.GL_TEXTURE_HEIGHT),
                GL45.glGetTextureLevelParameteri(lightDepth, 0, GL12.GL_TEXTURE_DEPTH),
                Integer.toHexString(GL45.glGetTextureLevelParameteri(lightDepth, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)),
                GL45.glGetTextureParameteri(lightDepth, GL12.GL_TEXTURE_BASE_LEVEL),
                GL45.glGetTextureParameteri(lightDepth, GL12.GL_TEXTURE_MAX_LEVEL),
                GL45.glGetTextureParameteri(lightDepth, 0x912F) != 0 ? "immutable" : "not immutable",
                GL45.glGetTextureParameteri(lightDepth, 0x82DF),
                Integer.toHexString(GL33.glGetSamplerParameteri(plain, GL14.GL_TEXTURE_COMPARE_MODE)),
                Integer.toHexString(GL33.glGetSamplerParameteri(plain, GL11.GL_TEXTURE_MIN_FILTER)),
                Integer.toHexString(GL33.glGetSamplerParameteri(comparing, GL14.GL_TEXTURE_COMPARE_MODE)),
                Integer.toHexString(GL33.glGetSamplerParameteri(comparing, GL14.GL_TEXTURE_COMPARE_FUNC)),
                Integer.toHexString(GL33.glGetSamplerParameteri(comparing, GL11.GL_TEXTURE_MIN_FILTER)));
            // The texture read back, set beside what was handed over.
            ByteBuffer back = ByteBuffer.allocateDirect(file.length).order(ByteOrder.nativeOrder());
            GL45.glGetTextureImage(lightDepth, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, back);
            java.nio.FloatBuffer want = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float worst = 0;
            int least = 0;
            for (int i = 0; i < want.remaining(); i++) {
                worst = Math.max(worst, Math.abs(back.getFloat(i * 4) - want.get(i)));
                least = want.get(i) < want.get(least) ? i : least;
            }
            Quadrature.LOGGER.info("Stage check: the light's depth read back from the texture differs from what was handed over by "
                + "at most {}.", worst);
            // The probe, at the texel nearest the light: its depth, and the two comparisons.
            probe = StageRunner.link("Stage check", "probe", PROBE_VERTEX, PROBE_FRAGMENT);
            target = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(target, 1, GL30.GL_RGBA32F, 1, 1);
            framebuffer = GL45.glCreateFramebuffers();
            GL45.glNamedFramebufferTexture(framebuffer, GL30.GL_COLOR_ATTACHMENT0, target, 0);
            GL45.glNamedFramebufferDrawBuffer(framebuffer, GL30.GL_COLOR_ATTACHMENT0);
            GL45.glProgramUniform2f(probe, GL20.glGetUniformLocation(probe, "at"), (least % columns + 0.5f) / columns,
                (least / columns + 0.5f) / rows);
            GL45.glBindTextureUnit(6, lightDepth);
            GL33.glBindSampler(6, plain);
            GL45.glBindTextureUnit(7, lightDepth);
            GL33.glBindSampler(7, comparing);
            vertexArray = GL45.glCreateVertexArrays();
            GL30.glBindVertexArray(vertexArray);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glViewport(0, 0, 1, 1);
            GL20.glUseProgram(probe);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            ByteBuffer read = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
            GL45.glGetTextureImage(target, 0, GL11.GL_RGBA, GL11.GL_FLOAT, read);
            Quadrature.LOGGER.info("Stage check: a probe at the texel nearest the light (file's {}): read through the plain sampler {}; "
                + "compared with a reference nearer the light {} (1 expected), with one farther {} (0 expected); driver error 0x{}.",
                want.get(least), read.getFloat(0), read.getFloat(4), read.getFloat(8), Integer.toHexString(GL11.glGetError()));
        } catch (Throwable failure) {
            Quadrature.LOGGER.warn("Stage check: its diagnosis of the light's depth could not be made: {}", failure.toString());
        } finally {
            GL20.glUseProgram(0);
            if (probe != 0) {
                GL20.glDeleteProgram(probe);
            }
            if (framebuffer != 0) {
                GL30.glDeleteFramebuffers(framebuffer);
            }
            if (target != 0) {
                GL11.glDeleteTextures(target);
            }
            if (vertexArray != 0) {
                GL30.glDeleteVertexArrays(vertexArray);
            }
        }
    }

    /**
     * Says what the folder's probe of the shadow lookup wrote. By the folder's account each pixel holds
     * the light's u and v, the reference depth and the depth stored; a first number of -2 means the
     * place lies outside every light view, with the count of light views and the dimension beside it;
     * -1 means no stored inputs; a stored depth of 1 means the texture is empty; a reference far below
     * 0 means the depth's span was read as nothing.
     */
    private static void shadowProbe(StageRunner runner, StagePlan.Pass pass) {
        try {
            int divisor = runner.plan().divisor(pass);
            int width = StagePlan.side(runner.width(), divisor);
            int height = StagePlan.side(runner.height(), divisor);
            ByteBuffer raw = ByteBuffer.allocateDirect(width * height * 16).order(ByteOrder.nativeOrder());
            GL45.glGetTextureImage(runner.texture(pass.outputs.get(0).target), 0, GL11.GL_RGBA, GL11.GL_FLOAT, raw);
            int outside = 0;
            int noInputs = 0;
            int empty = 0;
            int scale = 0;
            int looked = 0;
            int layers = Integer.MIN_VALUE;
            int dimension = Integer.MIN_VALUE;
            for (int pixel = 0; pixel < width * height; pixel++) {
                float r = raw.getFloat(pixel * 16);
                float g = raw.getFloat(pixel * 16 + 4);
                float b = raw.getFloat(pixel * 16 + 8);
                float a = raw.getFloat(pixel * 16 + 12);
                if (r == -2f) {
                    outside++;
                    layers = Math.round(g);
                    dimension = Math.round(b);
                } else if (r == -1f) {
                    noInputs++;
                } else {
                    looked++;
                    empty += a == 1f ? 1 : 0;
                    scale += b < -1f ? 1 : 0;
                }
            }
            Quadrature.LOGGER.info("Stage check: the folder's shadow probe, {} by {}: {} pixels looked up, of them {} with a stored depth "
                + "of 1 and {} with a reference far below 0; {} outside every light view{}; {} with no stored inputs.", width, height,
                looked, empty, scale, outside,
                outside == 0 ? "" : " (light views counted " + layers + ", dimension " + dimension + ")", noInputs);
            // The places the folder's worked values are for.
            for (int[] at : new int[][] {{280, 99}, {width / 2, height / 2}}) {
                if (at[0] < width && at[1] < height) {
                    int pixel = (at[1] * width + at[0]) * 16;
                    Quadrature.LOGGER.info("Stage check: the shadow probe at ({}, {}), rows from the bottom: light u {} v {}, reference "
                        + "{}, stored depth {}.", at[0], at[1], raw.getFloat(pixel), raw.getFloat(pixel + 4), raw.getFloat(pixel + 8),
                        raw.getFloat(pixel + 12));
                }
            }
        } catch (RuntimeException failure) {
            Quadrature.LOGGER.warn("Stage check: what the folder's shadow probe wrote could not be read: {}", failure.toString());
        }
    }

    private static void set(int capability, boolean on) {
        if (on) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }

    private static ByteBuffer direct(byte[] bytes) {
        ByteBuffer data = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        data.put(bytes).flip();
        return data;
    }

    /** Writes what differed for whoever wrote the stage: a text of counts and the worst pixels, and images. */
    private static void report(String verdict, List<Made> made) {
        try {
            File directory = new File(Minecraft.getMinecraft().gameDir, "quadrature-stage");
            directory.mkdirs();
            StringBuilder text = new StringBuilder(verdict).append("\n\nRows are counted from the bottom, as in the files.\n");
            for (Made one : made) {
                int width = one.width;
                text.append(String.format("%n%s output %d, the worst pixels (x, y: got r g b a | expected r g b a):%n", one.check.pass,
                    one.check.output));
                for (int pixel : worst(one.result, 16)) {
                    if (one.gotFloats != null) {
                        text.append(String.format("  %d, %d: %.5f %.5f %.5f %.5f | %.5f %.5f %.5f %.5f%n", pixel % width, pixel / width,
                            one.gotFloats[pixel * 4], one.gotFloats[pixel * 4 + 1], one.gotFloats[pixel * 4 + 2],
                            one.gotFloats[pixel * 4 + 3], one.wantFloats[pixel * 4], one.wantFloats[pixel * 4 + 1],
                            one.wantFloats[pixel * 4 + 2], one.wantFloats[pixel * 4 + 3]));
                    } else {
                        text.append(String.format("  %d, %d: %d %d %d %d | %d %d %d %d%n", pixel % width, pixel / width,
                            one.gotBytes[pixel * 4] & 0xFF, one.gotBytes[pixel * 4 + 1] & 0xFF, one.gotBytes[pixel * 4 + 2] & 0xFF,
                            one.gotBytes[pixel * 4 + 3] & 0xFF, one.wantBytes[pixel * 4] & 0xFF, one.wantBytes[pixel * 4 + 1] & 0xFF,
                            one.wantBytes[pixel * 4 + 2] & 0xFF, one.wantBytes[pixel * 4 + 3] & 0xFF));
                    }
                }
                if (!one.result.passes()) {
                    // Where the failures lie by row, for a fault that shows only in part of the picture.
                    text.append(String.format("%s output %d, rows %d to %d hold its differing pixels; by row (row: pixels):%n",
                        one.check.pass, one.check.output, one.result.minY, one.result.maxY));
                    StringBuilder rows = new StringBuilder();
                    for (int y = one.result.minY; y <= one.result.maxY; y++) {
                        int count = 0;
                        for (int x = 0; x < width; x++) {
                            count += one.result.difference[y * width + x] != 0 ? 1 : 0;
                        }
                        if (count != 0) {
                            rows.append(rows.length() == 0 ? "  " : ", ").append(y).append(": ").append(count);
                        }
                    }
                    text.append(rows).append(String.format("%n"));
                }
                String name = one.check.pass + "-" + one.check.output + (one.check.frame == 0 ? "" : "-frame" + one.check.frame);
                gray(new File(directory, name + "-difference.png"), one.result.difference, width, one.height);
                if (one.gotBytes != null) {
                    color(new File(directory, name + "-got.png"), one.gotBytes, width, one.height);
                    color(new File(directory, name + "-expected.png"), one.wantBytes, width, one.height);
                }
            }
            Files.write(new File(directory, "stage-check.txt").toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
            Quadrature.LOGGER.info("Stage check: its report and images are in {}.", directory);
        } catch (java.io.IOException | RuntimeException failure) {
            Quadrature.LOGGER.warn("Stage check: its report could not be written: {}", failure.toString());
        }
    }

    /** The pixels that differ most, the greatest first, at most {@code count}. */
    private static int[] worst(StageCompare.Result result, int count) {
        List<Integer> pixels = new ArrayList<>();
        for (int pixel = 0; pixel < result.difference.length; pixel++) {
            if (result.difference[pixel] != 0) {
                pixels.add(pixel);
            }
        }
        pixels.sort((a, b) -> (result.difference[b] & 0xFF) - (result.difference[a] & 0xFF));
        int[] out = new int[Math.min(count, pixels.size())];
        for (int i = 0; i < out.length; i++) {
            out[i] = pixels.get(i);
        }
        return out;
    }

    private static void gray(File file, byte[] values, int width, int height) throws java.io.IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.getRaster().setSample(x, y, 0, values[(height - 1 - y) * width + x] & 0xFF);
            }
        }
        ImageIO.write(image, "png", file);
    }

    private static void color(File file, byte[] values, int width, int height) throws java.io.IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = ((height - 1 - y) * width + x) * 4;
                image.setRGB(x, y, (values[at] & 0xFF) << 16 | (values[at + 1] & 0xFF) << 8 | values[at + 2] & 0xFF);
            }
        }
        ImageIO.write(image, "png", file);
    }
}
