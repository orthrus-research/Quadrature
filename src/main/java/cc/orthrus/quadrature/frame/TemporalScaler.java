package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.scale.Jitter;
import cc.orthrus.quadrature.core.scale.RenderScale;
import cc.orthrus.quadrature.core.scale.Reprojection;
import cc.orthrus.quadrature.core.stage.FrameBlock;
import cc.orthrus.quadrature.depth.DepthCalls;
import cc.orthrus.quadrature.mixin.MinecraftScaleAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;

import org.lwjgl.opengl.EXTDirectStateAccess;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Draws the world below the output's resolution and with a sub-pixel offset each frame, then makes the
 * output's picture from it: motion for what stands still from depth, a temporal pass, and an
 * edge-adaptive spatial scaler. Off unless {@code -Dquadrature.scale=<0.5 to 1>} or
 * {@code -Dquadrature.taa=true} is set; with both off nothing here runs and nothing is changed.
 *
 * <p>Where the world is drawn. For the world pass the game's framebuffer is a smaller one of the same
 * formats, {@link RenderScale#side} pixels each way, and the display's size the game reports is that
 * size: from the pass's start until the composite, {@code Minecraft.getFramebuffer()} and
 * {@code displayWidth} and {@code displayHeight} describe the drawn picture, so the game and every
 * hosted draw, a mod's own post-processing with framebuffers of the game's size included, work on one
 * consistent smaller picture. The game's projection keeps the output's shape. The projection of every
 * world view the game builds (the sky's, the world's, the clouds') takes the frame's offset on its left
 * as it is built, so entities, particles, block entities and terrain all carry it; the hand's does not.
 *
 * <p>The composite runs where the game is about to call the mods' last world drawing: the game's own
 * framebuffer and size are put back, and the picture is made whole at the output's size before anything
 * reads it there, a capture included. What mods draw there, the hand and the interface are drawn at the
 * output's size without the offset; the drawn depth is stretched over the game's depth buffer for them.
 *
 * <p>What has no motion. Motion comes from depth and the two frames' cameras, so it is right for
 * everything that stands still. Entities, particles, falling weather, clouds and animated textures move
 * or change on their own; their history is held to what this frame shows by the box of its
 * neighborhood, and pixels whose depth changed after opaque terrain (entities, block entities, opaque
 * particles, the outline, clouds above the player) take at least {@code -Dquadrature.taa.reactive} of
 * this frame.
 *
 * <p>State. Every pass is a compute program writing images, read on units 4 to 8, so blending, the
 * alpha test, the viewport and the masks hosted code leaves set do not touch it. The depth is copied and
 * stretched with copies and a blit that change no binding. On the way out the units, the image unit and
 * the program are emptied and the game's framebuffer and viewport put back.
 */
public final class TemporalScaler {

    private static final String SCALE_SETTING = System.getProperty("quadrature.scale");
    /** The render scale, 1 unless a factor from 0.5 to 1 is set. */
    public static final double SCALE = RenderScale.parse(SCALE_SETTING);
    /** Whether the temporal pass runs, with the offset each frame. */
    public static final boolean TAA = Boolean.getBoolean("quadrature.taa");
    public static final boolean REQUESTED = TAA || SCALE < 1;

    /** The least weight this frame takes against its history. */
    private static final float LEAST = number("quadrature.taa.blend", 0.1f);
    /** The least weight where the depth changed after opaque terrain. */
    private static final float REACTIVE = number("quadrature.taa.reactive", 0.5f);
    /** Per pixel of motion a frame, how far this frame's weight goes toward a third. */
    private static final float MOVING = number("quadrature.taa.moving", 1f / 16);
    private static final int PHASES = Math.max(1, (int) number("quadrature.taa.phases", 8));
    private static final float SHARPNESS = number("quadrature.scale.sharpness", 0.2f);
    /** How much longer the scaler's kernel is along an edge than across it, less one. */
    private static final float STRETCH = number("quadrature.scale.stretch", 1f);
    /** How a luma gradient's size counts toward an edge's strength. */
    private static final float EDGE = number("quadrature.scale.edge", 8f);
    /** {@code -Dquadrature.scale.kernel=plain}: the separable cubic without edge steering in place of the edge-adaptive scaler. */
    private static final boolean PLAIN = "plain".equals(System.getProperty("quadrature.scale.kernel"));
    /** {@code -Dquadrature.taa.check=true}: the motion target read back every 600 composites and summarized. */
    private static final boolean CHECK = Boolean.getBoolean("quadrature.taa.check");

    /** The stage's name in the frame's graph, and the hosted stage it runs in front of. */
    static final String STAGE = "scaler";
    static final String BEFORE = "render last";
    private static final String TERRAIN = "opaque terrain";
    private static final String ENTITIES = "entities";

    private static final int RGBA8 = 0x8058;

    private static boolean failed;
    private static boolean announced;
    /** This pass is drawn into the smaller framebuffer, with the offset where the temporal pass runs. */
    private static boolean active;
    /** The game's framebuffer and display size are the drawn picture's, to be put back. */
    private static boolean swapped;
    private static boolean composited;
    private static boolean terrainDepthCopied;
    private static long frames;
    private static long lastComposite = Long.MIN_VALUE;
    private static long composites;
    private static long historyKept;

    private static int outputWidth;
    private static int outputHeight;
    private static int drawnWidth;
    private static int drawnHeight;
    private static double offsetX;
    private static double offsetY;
    private static final float[] offsetMatrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    private static final float[] projectionRead = new float[16];
    private static final float[] modelViewRead = new float[16];
    private static Reprojection.Frame previous;
    private static Reprojection.Frame current;

    /** The game's own framebuffer, kept while the drawn one stands in for it. */
    private static Framebuffer game;
    /** The framebuffer the world is drawn into, of the game's formats at the drawn size. */
    private static Framebuffer drawn;
    private static int depthFormat;

    private static int motionProgram;
    private static int temporalProgram;
    private static int scaleProgram;
    private static int copyProgram;
    private static int linear;
    private static int nearest;
    private static final int[] history = new int[2];
    private static int historyIndex;
    private static int motion;
    private static int depthNow;
    private static int depthTerrain;

    private static final String[] PASSES = {"terrain depth", "depth copy", "motion", "temporal", "scale", "depth stretch"};
    private static final PassClock clock = new PassClock(PASSES.length);

    private TemporalScaler() {
    }

    private static float number(String name, float fallback) {
        try {
            String text = System.getProperty(name);
            return text == null ? fallback : Float.parseFloat(text.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    // The frame's boundaries, from FrameHost.

    /** The world pass begins: sizes and targets, this frame's offset, and the drawn framebuffer put in the game's place. */
    static void beginPass() {
        active = false;
        composited = false;
        terrainDepthCopied = false;
        current = null;
        if (!REQUESTED || failed) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (swapped) {
            // A pass that never reached its composite or its end: the game's framebuffer goes back first.
            restore(minecraft);
        }
        if (!announced) {
            announced = true;
            if (RenderScale.refused(SCALE_SETTING)) {
                Quadrature.LOGGER.warn("Scaler: quadrature.scale={} is not a factor from {} to 1; the world is drawn at the output's size.",
                    SCALE_SETTING, RenderScale.LEAST);
            }
            Quadrature.LOGGER.info("Scaler: render scale {}, {} scaler, temporal pass {}{}.", SCALE, PLAIN ? "plain" : "edge-adaptive",
                TAA ? "on" : "off", TAA ? String.format(" (least weight %.3f, reactive %.2f, %d offsets)", LEAST, REACTIVE, PHASES) : "");
        }
        if (minecraft.gameSettings.anaglyph || !OpenGlHelper.isFramebufferEnabled() || minecraft.world == null) {
            // Two passes a frame, or no framebuffer of the game's to stand in for: drawn as the game draws.
            return;
        }
        try {
            prepare(minecraft.getFramebuffer(), minecraft.displayWidth, minecraft.displayHeight);
        } catch (RuntimeException failure) {
            fail(failure);
            return;
        }
        frames++;
        active = true;
        if (TAA) {
            double[] offset = Jitter.offset(frames, PHASES);
            offsetX = offset[0];
            offsetY = offset[1];
            float[] matrix = Jitter.matrix(offsetX, offsetY, drawnWidth, drawnHeight);
            offsetMatrix[12] = matrix[12];
            offsetMatrix[13] = matrix[13];
        }
        // The drawn framebuffer stands in for the game's, and the display's size is its size.
        game = minecraft.getFramebuffer();
        ((MinecraftScaleAccess) minecraft).quadrature$setFramebuffer(drawn);
        minecraft.displayWidth = drawnWidth;
        minecraft.displayHeight = drawnHeight;
        swapped = true;
        drawn.bindFramebuffer(true);
    }

    /** The frame entered a stage: where the view is read, the terrain's depth kept, and the picture made. */
    static void boundary(String stage) {
        if (!active || composited) {
            return;
        }
        if (stage.equals(TERRAIN)) {
            readView();
        } else if (stage.equals(ENTITIES) && TAA) {
            clock.begin(0);
            GL43.glCopyImageSubData(drawn.depthBuffer, GL30.GL_RENDERBUFFER, 0, 0, 0, 0, depthTerrain, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                drawnWidth, drawnHeight, 1);
            clock.end(0);
            terrainDepthCopied = true;
        } else if (stage.equals(BEFORE)) {
            composite();
        }
    }

    /** Whether the composite is still to run in this pass, so its stage is entered. */
    static boolean due() {
        return active && !composited;
    }

    /** The world pass ends; a composite that found no place in it runs here. */
    static void endPass() {
        if (active && !composited) {
            composite();
        }
        if (swapped) {
            restore(Minecraft.getMinecraft());
        }
        active = false;
    }

    // The two hooks in the game's building of its projections.

    /** Before the game builds a projection for the world: the frame's offset goes on its left. */
    public static void offsetProjection() {
        if (active && TAA && !composited) {
            GL11.glMultMatrixf(offsetMatrix);
        }
    }

    /**
     * The shape the game gives a world projection: the output's while the display's size is the drawn
     * picture's, so that rounding the drawn size to whole pixels stretches nothing once scaled back.
     */
    public static float aspect(float aspect) {
        return swapped ? (float) outputWidth / outputHeight : aspect;
    }

    // The work.

    /** Puts the game's own framebuffer and the display's size back. */
    private static void restore(Minecraft minecraft) {
        ((MinecraftScaleAccess) minecraft).quadrature$setFramebuffer(game);
        minecraft.displayWidth = outputWidth;
        minecraft.displayHeight = outputHeight;
        swapped = false;
    }

    private static void readView() {
        Minecraft minecraft = Minecraft.getMinecraft();
        Entity viewer = minecraft.getRenderViewEntity();
        if (viewer == null || minecraft.world == null) {
            return;
        }
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projectionRead);
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, modelViewRead);
        double[] projection = FrameBlock.doubles(projectionRead);
        if (TAA) {
            Jitter.remove(projection, offsetMatrix[12], offsetMatrix[13]);
        }
        // The game draws the world relative to the viewer's interpolated position.
        double partial = minecraft.getRenderPartialTicks();
        current = new Reprojection.Frame(projection, FrameBlock.doubles(modelViewRead),
            viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * partial,
            viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * partial,
            viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * partial, minecraft.world.provider.getDimension());
    }

    private static void composite() {
        composited = true;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (swapped) {
            restore(minecraft);
        }
        try {
            int picture = game.framebufferTexture;
            int source = drawn.framebufferTexture;
            if (TAA) {
                clock.begin(1);
                GL43.glCopyImageSubData(drawn.depthBuffer, GL30.GL_RENDERBUFFER, 0, 0, 0, 0, depthNow, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                    drawnWidth, drawnHeight, 1);
                clock.end(1);
                double[] toPrevious = lastComposite == frames - 1 ? Reprojection.currentToPrevious(previous, current) : null;
                boolean kept = toPrevious != null;
                int groupsX = (drawnWidth + ScalerShaders.GROUP - 1) / ScalerShaders.GROUP;
                int groupsY = (drawnHeight + ScalerShaders.GROUP - 1) / ScalerShaders.GROUP;

                clock.begin(2);
                GL20.glUseProgram(motionProgram);
                if (kept) {
                    GL41.glProgramUniformMatrix4fv(motionProgram, 0, false, FrameBlock.floats(toPrevious));
                }
                GL41.glProgramUniform2f(motionProgram, 1, drawnWidth, drawnHeight);
                GL41.glProgramUniform2f(motionProgram, 2, (float) offsetX, (float) offsetY);
                GL41.glProgramUniform1i(motionProgram, 3, DepthCalls.active() ? 1 : 0);
                GL41.glProgramUniform1i(motionProgram, 4, kept ? 1 : 0);
                bind(ScalerShaders.UNIT_A, depthNow, nearest);
                GL42.glBindImageTexture(0, motion, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG16F);
                GL43.glDispatchCompute(groupsX, groupsY, 1);
                clock.end(2);
                GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);

                clock.begin(3);
                int next = historyIndex ^ 1;
                GL20.glUseProgram(temporalProgram);
                GL41.glProgramUniform2f(temporalProgram, 1, drawnWidth, drawnHeight);
                GL41.glProgramUniform2f(temporalProgram, 2, (float) offsetX, (float) offsetY);
                GL41.glProgramUniform1i(temporalProgram, 3, DepthCalls.active() ? 1 : 0);
                GL41.glProgramUniform1i(temporalProgram, 4, kept ? 1 : 0);
                GL41.glProgramUniform1f(temporalProgram, 5, LEAST);
                GL41.glProgramUniform1f(temporalProgram, 6, REACTIVE);
                GL41.glProgramUniform1i(temporalProgram, 7, terrainDepthCopied ? 1 : 0);
                GL41.glProgramUniform1f(temporalProgram, 8, MOVING);
                bind(ScalerShaders.UNIT_A, source, nearest);
                bind(ScalerShaders.UNIT_B, history[historyIndex], linear);
                bind(ScalerShaders.UNIT_C, motion, nearest);
                bind(ScalerShaders.UNIT_D, depthNow, nearest);
                bind(ScalerShaders.UNIT_E, depthTerrain, nearest);
                GL42.glBindImageTexture(0, history[next], 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
                GL43.glDispatchCompute(groupsX, groupsY, 1);
                clock.end(3);
                GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
                historyIndex = next;
                source = history[next];
                composites++;
                historyKept += kept ? 1 : 0;
                if (CHECK && composites % 600 == 0) {
                    checkMotion(kept);
                }
            }

            clock.begin(4);
            GL42.glBindImageTexture(0, picture, 0, false, 0, GL15.GL_WRITE_ONLY, RGBA8);
            if (drawnWidth != outputWidth || drawnHeight != outputHeight) {
                GL20.glUseProgram(scaleProgram);
                GL41.glProgramUniform2f(scaleProgram, 1, drawnWidth, drawnHeight);
                GL41.glProgramUniform2f(scaleProgram, 2, outputWidth, outputHeight);
                GL41.glProgramUniform1f(scaleProgram, 3, SHARPNESS);
                if (!PLAIN) {
                    GL41.glProgramUniform1f(scaleProgram, 4, STRETCH);
                    GL41.glProgramUniform1f(scaleProgram, 5, EDGE);
                }
            } else {
                GL20.glUseProgram(copyProgram);
                GL41.glProgramUniform2f(copyProgram, 1, outputWidth, outputHeight);
            }
            bind(ScalerShaders.UNIT_A, source, nearest);
            GL43.glDispatchCompute((outputWidth + ScalerShaders.GROUP - 1) / ScalerShaders.GROUP,
                (outputHeight + ScalerShaders.GROUP - 1) / ScalerShaders.GROUP, 1);
            clock.end(4);
            // What follows draws into the picture, reads it as a texture or copies it out.
            GL42.glMemoryBarrier(GL42.GL_ALL_BARRIER_BITS);

            // The drawn depth over the game's whole depth buffer, for what mods draw last against the world.
            clock.begin(5);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL45.glBlitNamedFramebuffer(drawn.framebufferObject, game.framebufferObject, 0, 0, drawnWidth, drawnHeight, 0, 0,
                outputWidth, outputHeight, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            clock.end(5);
            if (TAA) {
                // What is drawn from here on is drawn at the output's size: without the offset.
                GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projectionRead);
                double[] projection = FrameBlock.doubles(projectionRead);
                Jitter.remove(projection, offsetMatrix[12], offsetMatrix[13]);
                EXTDirectStateAccess.glMatrixLoadfEXT(GL11.GL_PROJECTION, FrameBlock.floats(projection));
            }
            previous = current;
            lastComposite = frames;
            clock.frame();
            if (!announcedFirst) {
                announcedFirst = true;
                Quadrature.LOGGER.info("Scaler: the first picture is made, the world drawn at {} by {} for {} by {}; depth format 0x{}; "
                    + "driver error 0x{}.", drawnWidth, drawnHeight, outputWidth, outputHeight, Integer.toHexString(depthFormat),
                    Integer.toHexString(GL11.glGetError()));
            }
        } catch (RuntimeException failure) {
            fail(failure);
        } finally {
            for (int unit = ScalerShaders.UNIT_A; unit <= ScalerShaders.UNIT_E; unit++) {
                GL45.glBindTextureUnit(unit, 0);
                GL33.glBindSampler(unit, 0);
            }
            GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, RGBA8);
            // The game's framebuffer bound with its viewport, the program and the rest put back.
            StateEnvelope.leave();
        }
    }

    private static boolean announcedFirst;

    private static void bind(int unit, int texture, int sampler) {
        GL45.glBindTextureUnit(unit, texture);
        GL33.glBindSampler(unit, sampler);
    }

    private static void fail(RuntimeException failure) {
        failed = true;
        active = false;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (swapped) {
            restore(minecraft);
            minecraft.getFramebuffer().bindFramebuffer(true);
        }
        Quadrature.LOGGER.error("Scaler: it could not be run and is off for this session; the world is drawn at the output's size: {}",
            failure.toString());
    }

    // The targets.

    /** Makes or remakes what the passes need for the game's framebuffer at its present size. */
    private static void prepare(Framebuffer framebuffer, int width, int height) {
        if (programsMissing()) {
            motionProgram = link("motion", ScalerShaders.MOTION_COMPUTE);
            temporalProgram = link("temporal", ScalerShaders.TEMPORAL_COMPUTE);
            scaleProgram = link("scale", PLAIN ? ScalerShaders.SCALE_PLAIN_COMPUTE : ScalerShaders.SCALE_COMPUTE);
            copyProgram = link("copy", ScalerShaders.COPY_COMPUTE);
            linear = sampler(GL11.GL_LINEAR);
            nearest = sampler(GL11.GL_NEAREST);
        }
        if (!framebuffer.useDepth || framebuffer.depthBuffer <= 0) {
            throw new IllegalStateException("the game's framebuffer has no depth buffer");
        }
        boolean stencil = framebuffer.isStencilEnabled();
        if (drawn != null && width == outputWidth && height == outputHeight && drawn.isStencilEnabled() == stencil
            && game == framebuffer) {
            return;
        }
        int format = GL45.glGetTextureLevelParameteri(framebuffer.framebufferTexture, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
        if (format != RGBA8) {
            throw new IllegalStateException("the game's picture is not RGBA8 but 0x" + Integer.toHexString(format));
        }
        outputWidth = width;
        outputHeight = height;
        drawnWidth = RenderScale.side(width, SCALE);
        drawnHeight = RenderScale.side(height, SCALE);
        release();
        // Said before anything is made, so a failure to make them is preceded by their sizes.
        long bytes = (long) drawnWidth * drawnHeight * (4 + 4 + 8 * 2 + 4 + 4 * 2);
        Quadrature.LOGGER.info("Scaler: the world is drawn at {} by {} of {} by {}; making a framebuffer and targets of {} MiB.", drawnWidth,
            drawnHeight, width, height, String.format("%.1f", bytes / 1048576.0));
        // A framebuffer of the game's own kind, so its formats are those the game and Quadrature give the game's.
        drawn = new Framebuffer(drawnWidth, drawnHeight, true);
        if (stencil) {
            drawn.enableStencil();
        }
        drawn.setFramebufferColor(0, 0, 0, 0);
        depthFormat = GL45.glGetNamedRenderbufferParameteri(drawn.depthBuffer, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
        if (GL45.glGetTextureLevelParameteri(drawn.framebufferTexture, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) != RGBA8) {
            throw new IllegalStateException("the drawn framebuffer's picture is not RGBA8");
        }
        for (int i = 0; i < 2; i++) {
            history[i] = texture(GL30.GL_RGBA16F, drawnWidth, drawnHeight);
        }
        motion = texture(GL30.GL_RG16F, drawnWidth, drawnHeight);
        depthNow = texture(depthFormat, drawnWidth, drawnHeight);
        depthTerrain = texture(depthFormat, drawnWidth, drawnHeight);
        game = framebuffer;
        // Targets made anew hold nothing of the frames before.
        lastComposite = Long.MIN_VALUE;
        // Making the framebuffer bound it; the game's goes back for the rest of the frame's start.
        framebuffer.bindFramebuffer(true);
    }

    /** Compiles and links one compute program; a failure is said with the driver's log and stops the scaler. */
    private static int link(String name, String source) {
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String message = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            Quadrature.LOGGER.error("Scaler: the {} program does not compile:{}{}", name, System.lineSeparator(), message);
            throw new IllegalStateException("the " + name + " program does not compile");
        }
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            String message = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            Quadrature.LOGGER.error("Scaler: the {} program does not link:{}{}", name, System.lineSeparator(), message);
            throw new IllegalStateException("the " + name + " program does not link");
        }
        return program;
    }

    private static boolean programsMissing() {
        return motionProgram == 0;
    }

    private static int texture(int format, int width, int height) {
        int texture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(texture, 1, format, width, height);
        return texture;
    }

    private static int sampler(int filter) {
        int sampler = GL45.glCreateSamplers();
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return sampler;
    }

    private static void release() {
        for (int texture : new int[] {history[0], history[1], motion, depthNow, depthTerrain}) {
            if (texture != 0) {
                GL11.glDeleteTextures(texture);
            }
        }
        history[0] = history[1] = motion = depthNow = depthTerrain = 0;
        if (drawn != null) {
            drawn.deleteFramebuffer();
            drawn = null;
        }
    }

    // Diagnostics.

    private static ByteBuffer checkRead;

    /** Reads the motion target back and says how much moves: for a view held still, nothing should. */
    private static void checkMotion(boolean kept) {
        int size = drawnWidth * drawnHeight * 8;
        if (checkRead == null || checkRead.capacity() != size) {
            // Kept from check to check: the read is the size of the motion target, 13 MiB at 1715 by 965.
            Quadrature.LOGGER.info("Scaler motion check: a read buffer of {} MiB is made.", String.format("%.1f", size / 1048576.0));
            checkRead = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        }
        ByteBuffer read = checkRead;
        read.clear();
        // The motion pass wrote through an image; a read of the texture's image needs its own barrier.
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL45.glGetTextureImage(motion, 0, GL30.GL_RG, GL11.GL_FLOAT, read);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        FloatBuffer values = read.asFloatBuffer();
        double most = 0;
        double sum = 0;
        long over = 0;
        long pixels = (long) drawnWidth * drawnHeight;
        for (long i = 0; i < pixels; i++) {
            double x = values.get((int) (2 * i));
            double y = values.get((int) (2 * i + 1));
            double length = Math.sqrt(x * x + y * y);
            most = Math.max(most, length);
            sum += length;
            over += length > 0.01 ? 1 : 0;
        }
        double[] eye = current == null ? new double[3] : new double[] {current.originX(), current.originY(), current.originZ()};
        Quadrature.LOGGER.info("Scaler motion check: history {}; motion mean {} px, greatest {} px, {} of {} pixels past 0.01 px; origin "
            + "{} {} {}.", kept ? "kept" : "dropped", String.format("%.5f", sum / pixels), String.format("%.4f", most), over, pixels,
            String.format("%.3f", eye[0]), String.format("%.3f", eye[1]), String.format("%.3f", eye[2]));
    }

    /** One line for the timing log, or null where the scaler is off. */
    public static String summary() {
        if (!REQUESTED) {
            return null;
        }
        if (failed) {
            return "scaler failed and is off";
        }
        StringBuilder line = new StringBuilder(String.format("scaler: drawn %d by %d of %d by %d (scale %.2f, %s), temporal %s, "
            + "history kept in %d of %d composites; passes at the median, GPU:", drawnWidth, drawnHeight, outputWidth, outputHeight, SCALE,
            PLAIN ? "plain" : "edge", TAA ? "on" : "off", historyKept, composites));
        for (int i = 0; i < PASSES.length; i++) {
            line.append(String.format(" %s %.3f", PASSES[i], clock.median(i) / 1e6));
        }
        return line.append(" ms").toString();
    }

    /**
     * GPU time per pass by timestamp queries, read a few frames late and only when the driver has them,
     * so reading never waits.
     */
    private static final class PassClock {

        private static final int RING = 4;
        private static final int SAMPLES = 256;
        private final int[][] begin;
        private final int[][] end;
        private final boolean[][] asked;
        private final long[][] samples;
        private final int[] counts;
        private int slot;

        PassClock(int passes) {
            begin = new int[RING][passes];
            end = new int[RING][passes];
            asked = new boolean[RING][passes];
            samples = new long[passes][SAMPLES];
            counts = new int[passes];
        }

        void begin(int pass) {
            if (begin[0][0] == 0) {
                for (int r = 0; r < RING; r++) {
                    for (int p = 0; p < begin[r].length; p++) {
                        begin[r][p] = GL15.glGenQueries();
                        end[r][p] = GL15.glGenQueries();
                    }
                }
            }
            if (asked[slot][pass]) {
                asked[slot][pass] = false;
                if (GL15.glGetQueryObjecti(end[slot][pass], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                    long took = GL33.glGetQueryObjectui64(end[slot][pass], GL15.GL_QUERY_RESULT)
                        - GL33.glGetQueryObjectui64(begin[slot][pass], GL15.GL_QUERY_RESULT);
                    samples[pass][counts[pass]++ % SAMPLES] = Math.max(0, took);
                }
            }
            GL33.glQueryCounter(begin[slot][pass], GL33.GL_TIMESTAMP);
        }

        void end(int pass) {
            GL33.glQueryCounter(end[slot][pass], GL33.GL_TIMESTAMP);
            asked[slot][pass] = true;
        }

        void frame() {
            slot = (slot + 1) % RING;
        }

        long median(int pass) {
            int held = Math.min(counts[pass], SAMPLES);
            if (held == 0) {
                return 0;
            }
            long[] sorted = java.util.Arrays.copyOf(samples[pass], held);
            java.util.Arrays.sort(sorted);
            return sorted[held / 2];
        }
    }
}
