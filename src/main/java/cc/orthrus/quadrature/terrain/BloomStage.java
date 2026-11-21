package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.bloom.BloomChain;
import cc.orthrus.quadrature.modinterface.ModInterface;

import net.minecraft.client.shader.Framebuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

/**
 * The glow of what mods declare emissive (D25, D27; PLAN item P3-B2), and the timing of the emissive stage.
 *
 * <p>The source. While the emissive stage draws, each bloom kind's two weights ({@link BloomChain}: over black and
 * over white, as fractions of the greatest) go into one weight target the size of the drawn picture, sharing its
 * depth, the fragment's alpha their cover: a declared layer in the same draw as its color, through the terrain
 * program's emissive variant and a framebuffer holding both targets; a contribution in a second draw of its vertices
 * by a program of the stage's own. No brightness threshold: what glows is what the mods declare.
 *
 * <p>The chain. From the picture's color times the weight over black, worked out a texel at a time for the first
 * halving, which also carries in its fourth channel the luma of the color times the difference of the weights; four
 * more halvings, each level the output's size halved and rounded up; doublings that add a tent of the level below,
 * its reads the kind's radius apart in that level's texels; and the composite, which gives GTCEu: Reloaded's rule
 * from the two channels (research R4, 7c). Kinds of different radii share the chain at the radius of the first kind
 * drawn in the frame.
 *
 * <p>Where. Without the temporal scaler the glow is worked out from the picture after translucent terrain and laid
 * over it there, where GTCEu: Reloaded's own path laid its glow. Where the scaler makes the picture it is worked out
 * after its passes, from the temporal pass's output, whose alpha holds the weight over black resolved over frames as
 * the color is, and laid over the output's picture; so it neither enters the history nor is sharpened, and keeps its
 * size on screen at every scale.
 *
 * <p>{@code -Dquadrature.bloom=off} draws the emissive stage without glow; {@code =alternate} glows on the frames a mix
 * of the frame's number chooses, so the summary compares the two kinds of frame paired over one run.
 * {@code -Dquadrature.bloom.levels} (2 to 6, 5), {@code .gain} (4.5, {@link #CALIBRATED_GAIN}) and
 * {@code .brightnessWeighted} (false, for high dynamic range) set the chain.
 *
 * <p>Client thread. Objects are made on first use and remade when a size or the picture's framebuffer changes. Nothing
 * is read back from the driver while drawing: the stage binds the picture's framebuffer back by its name, and the
 * timer queries are read a few frames late, where the driver has them ready.
 */
final class BloomStage {

    static final String MODE = System.getProperty("quadrature.bloom", "on");
    private static final boolean OFF = "off".equals(MODE) || "false".equals(MODE);
    private static final boolean ALTERNATE = "alternate".equals(MODE);
    static final int LEVELS = BloomChain.levels(Integer.getInteger("quadrature.bloom.levels", BloomChain.DEFAULT_LEVELS));
    private static final float GAIN = gain(System.getProperty("quadrature.bloom.gain"));
    private static final boolean BRIGHTNESS_WEIGHTED = Boolean.getBoolean("quadrature.bloom.brightnessWeighted");
    /** {@code -Dquadrature.bloom.passTimes=true}: the glow's GPU time split by pass in the summary, three stamps more. */
    private static final boolean PASS_TIMES = Boolean.getBoolean("quadrature.bloom.passTimes");

    private static final int GROUP = 8;
    private static final int RING = 4;
    private static final float[] CLEAR = {0, 0, 0, 0};

    /**
     * The calibration gain (D27), set in P3-V1 against GTCEu: Reloaded's own glow: its default style weighs its five
     * levels 0.2 to 1.0, 3 in all, and applies the strength twice, so its glow is about 3 times 1.5 the chain's; the
     * light the two add around its lamps, in one world, came out 4.4 to 5.0 apart on three views of four.
     */
    static final float CALIBRATED_GAIN = 4.5f;

    private static float gain(String value) {
        try {
            return value == null ? CALIBRATED_GAIN : Math.max(0f, Float.parseFloat(value));
        } catch (NumberFormatException e) {
            return CALIBRATED_GAIN;
        }
    }

    private int firstProgram;
    private int downProgram;
    private int upProgram;
    private int compositeProgram;
    private int weightProgram;
    private int linear;
    private boolean broken;

    private int firstResolved;
    private int firstBrightness;
    private int upRadius;
    private int compositeScale;
    private int weightTextured;
    private int weightKind;

    /** The weight target and the two framebuffers it is drawn into, both sharing the picture's depth. */
    private int weights;
    private int pictureAndWeights;
    private int weightsAlone;
    private int drawnWidth;
    private int drawnHeight;
    private int pictureFramebuffer = -1;
    private int pictureTexture = -1;
    private int pictureDepth = -1;

    /** The chain, sized from the output's picture. */
    private int[] down = new int[0];
    private int[] up = new int[0];
    private int[] levelWidth = new int[0];
    private int[] levelHeight = new int[0];
    private int outputWidth;
    private int outputHeight;

    /** The frame begun last, whether it glows, whether weights were drawn in it, and the chain's radius for it. */
    private long frame = Long.MIN_VALUE;
    private boolean glows;
    private boolean drawing;
    private boolean weighted;
    private float radius;
    private boolean weightProgramBound;

    // GPU time: a row of stamps a frame, read when the ring comes round: the stage's draws begin and end (0, 1), the glow
    // begins and ends (2, 3), and under passTimes the first halving, the halvings and the doublings end (4, 5, 6).
    private final int[][] stamps = new int[RING][7];
    /** Under passTimes: GPU nanoseconds of the first halving, the other halvings, the doublings and the composite. */
    private final long[] passNanos = new long[4];
    private long passFrames;
    private final boolean[] stampedDraws = new boolean[RING];
    private final boolean[] stampedGlow = new boolean[RING];
    private final boolean[] glowed = new boolean[RING];
    private int ring = -1;
    /** By whether the frame glowed: GPU nanoseconds of the stage's draws, and frames measured. */
    private final long[] drawNanos = new long[2];
    private final long[] drawFrames = new long[2];
    private long glowNanos;
    private long glowFrames;
    private long glowingFrames;
    private long plainFrames;
    private long glowClientNanos;
    private long glowsAfterScale;
    private boolean radiiDiffered;

    /** Whether a frame glows: always, never, or under {@code alternate} as a mix of its number chooses. */
    static boolean glows(long frame) {
        return !OFF && (!ALTERNATE || (GpuCulling.mix(frame) & 1L) == 0);
    }

    /**
     * Begins the emissive stage's draws for a frame of the picture's framebuffer, where this many bloom kinds are
     * declared; nothing is drawn into the weight target until it is first bound this frame. Returns whether the frame
     * glows.
     */
    boolean begin(Framebuffer picture, int kinds, long frameIndex) {
        if (stamps[0][0] == 0) {
            for (int[] row : stamps) {
                for (int i = 0; i < row.length; i++) {
                    row[i] = GL15.glGenQueries();
                }
            }
        }
        frame = frameIndex;
        readStamps();
        GL33.glQueryCounter(stamps[ring][0], GL33.GL_TIMESTAMP);
        drawing = true;
        weighted = false;
        glows = kinds > 0 && !broken && glows(frameIndex);
        if (glows && (picture.framebufferWidth != drawnWidth || picture.framebufferHeight != drawnHeight
            || picture.framebufferObject != pictureFramebuffer || picture.framebufferTexture != pictureTexture
            || picture.depthBuffer != pictureDepth)) {
            remakeTargets(picture);
            glows = !broken;
        }
        glowed[ring] = glows;
        if (glows) {
            glowingFrames++;
        } else {
            plainFrames++;
        }
        return glows;
    }

    /** Takes the times of the ring's next row, issued a few frames ago, where the driver has them, and claims it. */
    private void readStamps() {
        ring = (ring + 1) % RING;
        int[] row = stamps[ring];
        if (stampedDraws[ring]) {
            stampedDraws[ring] = false;
            if (GL15.glGetQueryObjecti(row[1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                int kind = glowed[ring] ? 1 : 0;
                drawNanos[kind] += GL33.glGetQueryObjectui64(row[1], GL15.GL_QUERY_RESULT)
                    - GL33.glGetQueryObjectui64(row[0], GL15.GL_QUERY_RESULT);
                drawFrames[kind]++;
            }
        }
        if (stampedGlow[ring]) {
            stampedGlow[ring] = false;
            if (GL15.glGetQueryObjecti(row[3], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long begun = GL33.glGetQueryObjectui64(row[2], GL15.GL_QUERY_RESULT);
                long ended = GL33.glGetQueryObjectui64(row[3], GL15.GL_QUERY_RESULT);
                glowNanos += ended - begun;
                glowFrames++;
                if (PASS_TIMES) {
                    long first = GL33.glGetQueryObjectui64(row[4], GL15.GL_QUERY_RESULT);
                    long halvings = GL33.glGetQueryObjectui64(row[5], GL15.GL_QUERY_RESULT);
                    long doublings = GL33.glGetQueryObjectui64(row[6], GL15.GL_QUERY_RESULT);
                    passNanos[0] += first - begun;
                    passNanos[1] += halvings - first;
                    passNanos[2] += doublings - halvings;
                    passNanos[3] += ended - doublings;
                    passFrames++;
                }
            }
        }
    }

    /** Ends the stage's draws for the frame. */
    void endDraws() {
        if (drawing) {
            GL33.glQueryCounter(stamps[ring][1], GL33.GL_TIMESTAMP);
            stampedDraws[ring] = true;
            drawing = false;
        }
    }

    /** The kind's weight over black, as a fraction of the greatest. */
    static float overBlack(ModInterface.BloomKind kind) {
        return BloomChain.overBlack(kind.strength(), kind.base(), kind.highLimit()) / BloomChain.GREATEST_WEIGHT;
    }

    /** The kind's weight over white, as a fraction of the greatest. */
    static float overWhite(ModInterface.BloomKind kind) {
        return BloomChain.overWhite(kind.strength(), kind.base(), kind.lowLimit()) / BloomChain.GREATEST_WEIGHT;
    }

    /**
     * Binds the framebuffer holding the picture's color and the weight target, for a declared layer of this kind drawn
     * by the terrain program's emissive variant; false where the frame does not glow.
     */
    boolean layerTargets(ModInterface.BloomKind kind) {
        if (!glows || pictureAndWeights == 0) {
            return false;
        }
        weigh(kind);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, pictureAndWeights);
        return true;
    }

    /**
     * Binds the weight target alone and the stage's weight program for a contribution of this kind, drawn a second
     * time; false where the frame does not glow. {@link #endTargets} puts both back.
     */
    boolean contributionTarget(ModInterface.BloomKind kind, boolean textured) {
        if (!glows || weightsAlone == 0) {
            return false;
        }
        weigh(kind);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, weightsAlone);
        GL20.glUseProgram(weightProgram);
        GL20.glUniform1i(weightTextured, textured ? 1 : 0);
        GL20.glUniform2f(weightKind, overBlack(kind), overWhite(kind));
        weightProgramBound = true;
        return true;
    }

    /** Clears the weight target on its first use this frame, and takes the chain's radius from the first kind drawn. */
    private void weigh(ModInterface.BloomKind kind) {
        float kindRadius = BloomChain.radius(kind.radius());
        if (!weighted) {
            GL45.glClearNamedFramebufferfv(weightsAlone, GL11.GL_COLOR, 0, CLEAR);
            weighted = true;
            radius = kindRadius;
        } else if (kindRadius != radius && !radiiDiffered) {
            radiiDiffered = true;
            Quadrature.LOGGER.info("Bloom kinds of different radii glow in one frame; they share the first one's, {}.", radius);
        }
    }

    /** Binds the picture's framebuffer back, and no program, after {@link #layerTargets} or {@link #contributionTarget}. */
    void endTargets() {
        if (weightProgramBound) {
            GL20.glUseProgram(0);
            weightProgramBound = false;
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, pictureFramebuffer);
    }

    /** The weight target where weights were drawn into it this frame, for the temporal pass to resolve; else 0. */
    int weightsDrawn(long frameIndex) {
        return glows && weighted && frameIndex == frame ? weights : 0;
    }

    /**
     * Works out the glow of what was weighed this frame and lays it over the output's picture: from a color texture of
     * the drawn picture's size, whose alpha holds the weight over black resolved where {@code resolved}, else read
     * from the weight target as drawn.
     */
    void glow(int color, boolean resolved, Framebuffer output, long frameIndex, boolean afterScale) {
        if (!glows || !weighted || frameIndex != frame || broken) {
            return;
        }
        if (output.framebufferWidth != outputWidth || output.framebufferHeight != outputHeight || down.length == 0) {
            remakeChain(output.framebufferWidth, output.framebufferHeight);
            if (broken) {
                return;
            }
        }
        long began = System.nanoTime();
        GL33.glQueryCounter(stamps[ring][2], GL33.GL_TIMESTAMP);
        int levels = down.length;
        // The first halving: the picture's color times the weight over black, worked out a texel at a time.
        GL20.glUseProgram(firstProgram);
        GL20.glUniform1i(firstResolved, resolved ? 1 : 0);
        GL20.glUniform1i(firstBrightness, BRIGHTNESS_WEIGHTED ? 1 : 0);
        GL45.glBindTextureUnit(2, color);
        GL45.glBindTextureUnit(3, weights);
        GL33.glBindSampler(2, linear);
        GL33.glBindSampler(3, linear);
        GL42.glBindImageTexture(0, down[0], 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
        dispatch(levelWidth[0], levelHeight[0]);
        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        if (PASS_TIMES) {
            GL33.glQueryCounter(stamps[ring][4], GL33.GL_TIMESTAMP);
        }
        // The other halvings, each level into the next.
        GL20.glUseProgram(downProgram);
        for (int level = 1; level < levels; level++) {
            GL45.glBindTextureUnit(2, down[level - 1]);
            GL42.glBindImageTexture(0, down[level], 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
            dispatch(levelWidth[level], levelHeight[level]);
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        }
        if (PASS_TIMES) {
            GL33.glQueryCounter(stamps[ring][5], GL33.GL_TIMESTAMP);
        }
        // Doublings: each level adds the tent of the one below it, the last standing as itself.
        GL20.glUseProgram(upProgram);
        GL20.glUniform1f(upRadius, radius);
        for (int level = levels - 2; level >= 0; level--) {
            GL45.glBindTextureUnit(2, level == levels - 2 ? down[levels - 1] : up[level + 1]);
            GL45.glBindTextureUnit(3, down[level]);
            GL42.glBindImageTexture(0, up[level], 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RGBA16F);
            dispatch(levelWidth[level], levelHeight[level]);
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
        }
        if (PASS_TIMES) {
            GL33.glQueryCounter(stamps[ring][6], GL33.GL_TIMESTAMP);
        }
        // The composite, in place on the output's picture.
        GL20.glUseProgram(compositeProgram);
        GL20.glUniform1f(compositeScale, GAIN / levels);
        GL45.glBindTextureUnit(2, levels > 1 ? up[0] : down[0]);
        GL42.glBindImageTexture(0, output.framebufferTexture, 0, false, 0, GL15.GL_READ_WRITE, GL11.GL_RGBA8);
        dispatch(outputWidth, outputHeight);
        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT
            | GL42.GL_FRAMEBUFFER_BARRIER_BIT | GL42.GL_TEXTURE_UPDATE_BARRIER_BIT);
        // Back to what the game's cache does not know to be changed: units 2 and 3 and image unit 0 were used.
        GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA8);
        for (int unit = 2; unit <= 3; unit++) {
            GL45.glBindTextureUnit(unit, 0);
            GL33.glBindSampler(unit, 0);
        }
        GL20.glUseProgram(0);
        GL33.glQueryCounter(stamps[ring][3], GL33.GL_TIMESTAMP);
        stampedGlow[ring] = true;
        glowClientNanos += System.nanoTime() - began;
        if (afterScale) {
            glowsAfterScale++;
        }
    }

    private static void dispatch(int w, int h) {
        GL43.glDispatchCompute((w + GROUP - 1) / GROUP, (h + GROUP - 1) / GROUP, 1);
    }

    /** One line for the timing log, or null before the stage first ran. */
    String summary() {
        if (glowingFrames + plainFrames == 0) {
            return null;
        }
        return String.format("emissive: mode %s, %d frames glowing, %d plain; GPU draws %.3f ms glowing, %.3f ms plain; "
                + "glow %.3f ms GPU, %.3f ms client, over %d frames, %d after the scaler; %d levels at %dx%d from %dx%d, "
                + "gain %.2f, brightness weighting %s",
            MODE, glowingFrames, plainFrames, mean(drawNanos[1], drawFrames[1]), mean(drawNanos[0], drawFrames[0]),
            mean(glowNanos, glowFrames), glowFrames == 0 ? 0.0 : glowClientNanos / 1e6 / glowFrames, glowFrames,
            glowsAfterScale, down.length, levelWidth.length > 0 ? levelWidth[0] : 0, levelHeight.length > 0 ? levelHeight[0] : 0,
            drawnWidth, drawnHeight, GAIN, BRIGHTNESS_WEIGHTED ? "on" : "off")
            + (passFrames == 0 ? "" : String.format("; glow passes, GPU over %d frames: first halving %.3f ms, halvings "
                + "%.3f, doublings %.3f, composite %.3f", passFrames, mean(passNanos[0], passFrames),
                mean(passNanos[1], passFrames), mean(passNanos[2], passFrames), mean(passNanos[3], passFrames)));
    }

    private static double mean(long nanos, long frames) {
        return frames == 0 ? 0.0 : nanos / 1e6 / frames;
    }

    private void remakeTargets(Framebuffer picture) {
        releaseTargets();
        drawnWidth = picture.framebufferWidth;
        drawnHeight = picture.framebufferHeight;
        pictureFramebuffer = picture.framebufferObject;
        pictureTexture = picture.framebufferTexture;
        pictureDepth = picture.depthBuffer;
        if (pictureFramebuffer <= 0 || pictureTexture <= 0) {
            refuse("the game draws the world without a framebuffer of its own");
            return;
        }
        if (GL45.glGetTextureLevelParameteri(pictureTexture, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT) != GL11.GL_RGBA8) {
            refuse("the picture's color is not RGBA8");
            return;
        }
        if (!programs()) {
            return;
        }
        weights = texture(GL30.GL_RG8, drawnWidth, drawnHeight);
        pictureAndWeights = GL45.glCreateFramebuffers();
        GL45.glNamedFramebufferTexture(pictureAndWeights, GL30.GL_COLOR_ATTACHMENT0, pictureTexture, 0);
        GL45.glNamedFramebufferTexture(pictureAndWeights, GL30.GL_COLOR_ATTACHMENT1, weights, 0);
        shareDepth(pictureAndWeights);
        GL45.glNamedFramebufferDrawBuffers(pictureAndWeights, new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
        weightsAlone = GL45.glCreateFramebuffers();
        GL45.glNamedFramebufferTexture(weightsAlone, GL30.GL_COLOR_ATTACHMENT0, weights, 0);
        shareDepth(weightsAlone);
        for (int framebuffer : new int[] {pictureAndWeights, weightsAlone}) {
            int status = GL45.glCheckNamedFramebufferStatus(framebuffer, GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                refuse("a framebuffer of the weights is incomplete (" + Integer.toHexString(status) + ")");
                return;
            }
        }
    }

    /**
     * Attaches to a framebuffer of the stage's what the picture's has for depth and stencil, a renderbuffer or a
     * texture, whatever the game or a mod gave it. Read from the driver here only, when the objects are made.
     */
    private void shareDepth(int framebuffer) {
        for (int attachment : new int[] {GL30.GL_DEPTH_ATTACHMENT, GL30.GL_STENCIL_ATTACHMENT}) {
            int type = GL45.glGetNamedFramebufferAttachmentParameteri(pictureFramebuffer, attachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int name = GL45.glGetNamedFramebufferAttachmentParameteri(pictureFramebuffer, attachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (type == GL30.GL_RENDERBUFFER) {
                GL45.glNamedFramebufferRenderbuffer(framebuffer, attachment, GL30.GL_RENDERBUFFER, name);
            } else if (type == GL11.GL_TEXTURE) {
                GL45.glNamedFramebufferTexture(framebuffer, attachment, name, 0);
            }
        }
    }

    private void remakeChain(int width, int height) {
        releaseChain();
        outputWidth = width;
        outputHeight = height;
        if (!programs()) {
            return;
        }
        down = new int[LEVELS];
        up = new int[LEVELS];
        levelWidth = new int[LEVELS];
        levelHeight = new int[LEVELS];
        for (int level = 0; level < LEVELS; level++) {
            levelWidth[level] = BloomChain.levelSide(width, level);
            levelHeight[level] = BloomChain.levelSide(height, level);
            down[level] = texture(GL30.GL_RGBA16F, levelWidth[level], levelHeight[level]);
            up[level] = level < LEVELS - 1 ? texture(GL30.GL_RGBA16F, levelWidth[level], levelHeight[level]) : 0;
        }
    }

    private boolean programs() {
        if (broken) {
            return false;
        }
        if (firstProgram != 0) {
            return true;
        }
        try {
            firstProgram = computeProgram("first halving", FIRST);
            downProgram = computeProgram("halving", DOWN);
            upProgram = computeProgram("doubling", UP);
            compositeProgram = computeProgram("composite", COMPOSITE);
            weightProgram = drawProgram("weights", WEIGHT_VERTEX, WEIGHT_FRAGMENT);
        } catch (IllegalStateException e) {
            broken = true;
            return false;
        }
        firstResolved = GL20.glGetUniformLocation(firstProgram, "resolved");
        firstBrightness = GL20.glGetUniformLocation(firstProgram, "brightnessWeighted");
        upRadius = GL20.glGetUniformLocation(upProgram, "radius");
        compositeScale = GL20.glGetUniformLocation(compositeProgram, "scale");
        weightTextured = GL20.glGetUniformLocation(weightProgram, "textured");
        weightKind = GL20.glGetUniformLocation(weightProgram, "kindWeights");
        GL45.glProgramUniform1i(weightProgram, GL20.glGetUniformLocation(weightProgram, "atlas"), 0);
        linear = GL45.glCreateSamplers();
        GL33.glSamplerParameteri(linear, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(linear, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(linear, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(linear, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return true;
    }

    private void refuse(String why) {
        Quadrature.LOGGER.error("Emissive geometry does not glow: {}.", why);
        releaseTargets();
        broken = true;
    }

    private void releaseTargets() {
        for (int framebuffer : new int[] {pictureAndWeights, weightsAlone}) {
            if (framebuffer != 0) {
                GL30.glDeleteFramebuffers(framebuffer);
            }
        }
        if (weights != 0) {
            GL11.glDeleteTextures(weights);
        }
        pictureAndWeights = 0;
        weightsAlone = 0;
        weights = 0;
    }

    private void releaseChain() {
        for (int[] textures : new int[][] {down, up}) {
            for (int texture : textures) {
                if (texture != 0) {
                    GL11.glDeleteTextures(texture);
                }
            }
        }
        down = new int[0];
        up = new int[0];
        levelWidth = new int[0];
        levelHeight = new int[0];
    }

    private static int texture(int format, int w, int h) {
        int texture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(texture, 1, format, w, h);
        return texture;
    }

    private static int computeProgram(String name, String source) {
        int shader = compile(name, GL43.GL_COMPUTE_SHADER, source);
        return linked(name, shader);
    }

    private static int drawProgram(String name, String vertexSource, String fragmentSource) {
        int vertex = compile(name, GL20.GL_VERTEX_SHADER, vertexSource);
        int fragment;
        try {
            fragment = compile(name, GL20.GL_FRAGMENT_SHADER, fragmentSource);
        } catch (IllegalStateException e) {
            GL20.glDeleteShader(vertex);
            throw e;
        }
        return linked(name, vertex, fragment);
    }

    private static int compile(String name, int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            Quadrature.LOGGER.error("Bloom: the {} program does not compile:{}{}", name, System.lineSeparator(),
                GL20.glGetShaderInfoLog(shader));
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(name);
        }
        return shader;
    }

    private static int linked(String name, int... shaders) {
        int program = GL20.glCreateProgram();
        for (int shader : shaders) {
            GL20.glAttachShader(program, shader);
        }
        GL20.glLinkProgram(program);
        for (int shader : shaders) {
            GL20.glDeleteShader(shader);
        }
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            Quadrature.LOGGER.error("Bloom: the {} program does not link:{}{}", name, System.lineSeparator(),
                GL20.glGetProgramInfoLog(program));
            GL20.glDeleteProgram(program);
            throw new IllegalStateException(name);
        }
        return program;
    }

    // The programs; their constants come from BloomChain, which tests the same arithmetic. Units 2 and 3 for what is
    // read, image unit 0 for what is written.

    /**
     * The first halving, into the first level: thirteen taps of the picture's color times the weight over black, each
     * a bilinear read of those products formed exactly from a tile of them in shared memory (an eight-by-eight group
     * of the level reads at most 22 by 22 texels of the picture: two picture texels a level texel or fewer); the
     * groups weighed by brightness where asked. The fourth channel: the luma of that times one less the ratio of the
     * weights over white and over black, read from the weight target at the same taps.
     */
    static final String FIRST = String.join("\n",
        "#version 460 core",
        "layout(local_size_x = 8, local_size_y = 8) in;",
        "layout(binding = 0, rgba16f) uniform writeonly image2D target;",
        "layout(binding = 2) uniform sampler2D color;",
        "layout(binding = 3) uniform sampler2D weights;",
        "uniform int resolved;",
        "uniform int brightnessWeighted;",
        BloomChain.glsl(),
        "shared vec3 tile[24][24];",
        "void main() {",
        "    ivec2 size = imageSize(target);",
        "    ivec2 drawn = textureSize(color, 0);",
        "    vec2 ratio = vec2(drawn) / vec2(size);",
        "    vec2 h = 0.5 * ratio;",
        "    ivec2 origin = ivec2(floor((vec2(gl_WorkGroupID.xy * 8u) + 0.5) * ratio - 2.0 * h - 0.5));",
        "    ivec2 last = drawn - 1;",
        "    for (uint i = gl_LocalInvocationIndex; i < 576u; i += 64u) {",
        "        ivec2 q = clamp(origin + ivec2(int(i % 24u), int(i / 24u)), ivec2(0), last);",
        "        vec4 c = texelFetch(color, q, 0);",
        "        float a = resolved != 0 ? c.a : texelFetch(weights, q, 0).r;",
        "        tile[i / 24u][i % 24u] = c.rgb * a * GREATEST_WEIGHT;",
        "    }",
        "    barrier();",
        "    ivec2 p = ivec2(gl_GlobalInvocationID.xy);",
        "    if (p.x >= size.x || p.y >= size.y) {",
        "        return;",
        "    }",
        "    vec2 center = (vec2(p) + 0.5) * ratio;",
        "    vec3 taps[13];",
        "    float overBlack = 0.0;",
        "    float overWhite = 0.0;",
        "    for (int t = 0; t < 13; t++) {",
        "        vec2 at = center + vec2(TAPS[t]) * h;",
        "        vec2 f = at - 0.5;",
        "        vec2 low = floor(f);",
        "        vec2 w = f - low;",
        "        ivec2 b0 = clamp(ivec2(low) - origin, ivec2(0), ivec2(23));",
        "        ivec2 b1 = clamp(ivec2(low) + 1 - origin, ivec2(0), ivec2(23));",
        "        taps[t] = mix(mix(tile[b0.y][b0.x], tile[b0.y][b1.x], w.x), mix(tile[b1.y][b0.x], tile[b1.y][b1.x], w.x), w.y);",
        "        vec2 m = texture(weights, at / vec2(drawn)).rg;",
        "        overBlack += TAP_WEIGHTS[t] * m.r;",
        "        overWhite += TAP_WEIGHTS[t] * m.g;",
        "    }",
        "    vec3 sum = vec3(0.0);",
        "    float total = 0.0;",
        "    for (int box = 0; box < 5; box++) {",
        "        ivec4 i = BOXES[box];",
        "        vec3 mean = (taps[i.x] + taps[i.y] + taps[i.z] + taps[i.w]) * 0.25;",
        "        float weight = BOX_WEIGHTS[box] / (brightnessWeighted != 0 ? 1.0 + luma(mean) : 1.0);",
        "        sum += mean * weight;",
        "        total += weight;",
        "    }",
        "    sum /= total;",
        "    float darkening = overBlack > 0.0 ? 1.0 - overWhite / overBlack : 0.0;",
        "    imageStore(target, p, vec4(sum, luma(sum) * darkening));",
        "}");

    /** A later halving: thirteen bilinear reads of the level above on all four channels, half a texel of this one apart. */
    static final String DOWN = String.join("\n",
        "#version 460 core",
        "layout(local_size_x = 8, local_size_y = 8) in;",
        "layout(binding = 0, rgba16f) uniform writeonly image2D target;",
        "layout(binding = 2) uniform sampler2D source;",
        BloomChain.glsl(),
        "void main() {",
        "    ivec2 p = ivec2(gl_GlobalInvocationID.xy);",
        "    ivec2 size = imageSize(target);",
        "    if (p.x >= size.x || p.y >= size.y) {",
        "        return;",
        "    }",
        "    vec2 uv = (vec2(p) + 0.5) / vec2(size);",
        "    vec2 h = 0.5 / vec2(size);",
        "    vec4 sum = vec4(0.0);",
        "    for (int t = 0; t < 13; t++) {",
        "        sum += TAP_WEIGHTS[t] * texture(source, uv + vec2(TAPS[t]) * h);",
        "    }",
        "    imageStore(target, p, sum);",
        "}");

    /** A doubling: its own level plus a three-by-three tent of the level below, its reads the radius apart there. */
    static final String UP = String.join("\n",
        "#version 460 core",
        "layout(local_size_x = 8, local_size_y = 8) in;",
        "layout(binding = 0, rgba16f) uniform writeonly image2D target;",
        "layout(binding = 2) uniform sampler2D lower;",
        "layout(binding = 3) uniform sampler2D current;",
        "uniform float radius;",
        BloomChain.glsl(),
        "void main() {",
        "    ivec2 p = ivec2(gl_GlobalInvocationID.xy);",
        "    ivec2 size = imageSize(target);",
        "    if (p.x >= size.x || p.y >= size.y) {",
        "        return;",
        "    }",
        "    vec2 uv = (vec2(p) + 0.5) / vec2(size);",
        "    vec2 t = radius / vec2(textureSize(lower, 0));",
        "    vec4 sum = texelFetch(current, p, 0);",
        "    for (int n = -1; n <= 1; n++) {",
        "        for (int m = -1; m <= 1; m++) {",
        "            sum += TENT[(n + 1) * 3 + (m + 1)] * texture(lower, uv + vec2(m, n) * t);",
        "        }",
        "    }",
        "    imageStore(target, p, sum);",
        "}");

    /** The composite, in place on the output's picture: the first level read between its texels, scaled, by the rule. */
    static final String COMPOSITE = String.join("\n",
        "#version 460 core",
        "layout(local_size_x = 8, local_size_y = 8) in;",
        "layout(binding = 0, rgba8) uniform image2D picture;",
        "layout(binding = 2) uniform sampler2D glow;",
        "uniform float scale;",
        BloomChain.glsl(),
        "void main() {",
        "    ivec2 p = ivec2(gl_GlobalInvocationID.xy);",
        "    ivec2 size = imageSize(picture);",
        "    if (p.x >= size.x || p.y >= size.y) {",
        "        return;",
        "    }",
        "    vec4 background = imageLoad(picture, p);",
        "    vec4 bloom = texture(glow, (vec2(p) + 0.5) / vec2(size)) * scale;",
        "    imageStore(picture, p, vec4(clamp(composite(background.rgb, bloom), 0.0, 1.0), background.a));",
        "}");

    /**
     * A contribution's weights: drawn by the same vertices as its color, placed by the fixed-function transform so the
     * two agree in depth, its cover the vertex's alpha times the atlas's where it is textured, which the game's alpha
     * test and the contribution's blend apply as they do to its color.
     */
    static final String WEIGHT_VERTEX = String.join("\n",
        "#version 460 compatibility",
        "out vec4 vertexColor;",
        "out vec2 atlasCoordinate;",
        "void main() {",
        "    gl_Position = ftransform();",
        "    vertexColor = gl_Color;",
        "    atlasCoordinate = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;",
        "}");

    static final String WEIGHT_FRAGMENT = String.join("\n",
        "#version 460 compatibility",
        "uniform sampler2D atlas;",
        "uniform int textured;",
        "uniform vec2 kindWeights;",
        "in vec4 vertexColor;",
        "in vec2 atlasCoordinate;",
        "layout(location = 0) out vec4 weight;",
        "void main() {",
        "    float cover = vertexColor.a * (textured != 0 ? texture(atlas, atlasCoordinate).a : 1.0);",
        "    weight = vec4(kindWeights, 0.0, cover);",
        "}");
}
