package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.stage.FrameBlock;
import cc.orthrus.quadrature.core.stage.StageManifest;
import cc.orthrus.quadrature.core.stage.StagePlan;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Runs what a stage's manifest says is to be run: makes its programs, assets and targets, and draws
 * its passes in order, each with its samplers bound where the manifest says and its outputs attached
 * where the manifest says. The live stage and the check of a stage both run through this, so what is
 * checked is what is run.
 *
 * <p>The folder is data. A program's source goes to the driver's compiler and nowhere else. The host
 * sets no uniform of a stage's program: where a sampler or the frame's block is bound is the program's
 * own to declare, and a program that declares otherwise than its manifest is refused, as is one whose
 * block the driver lays out otherwise than the host fills it.
 *
 * <p>Nothing here saves or restores what it binds: {@link Bindings} does that for whoever calls.
 */
final class StageRunner {

    /** What was bound to the units and the block's binding, to be put back. */
    static final class Bindings {
        private final int[] textures = new int[StagePlan.UNITS];
        private final int[] arrays = new int[StagePlan.UNITS];
        private final int[] volumes = new int[StagePlan.UNITS];
        private final int[] samplers = new int[StagePlan.UNITS];
        private final int[] blocks = new int[16];
        private final int uniformBuffer;

        Bindings() {
            for (int unit = 0; unit < StagePlan.UNITS; unit++) {
                textures[unit] = GL30.glGetIntegeri(GL11.GL_TEXTURE_BINDING_2D, unit);
                arrays[unit] = GL30.glGetIntegeri(GL30.GL_TEXTURE_BINDING_2D_ARRAY, unit);
                volumes[unit] = GL30.glGetIntegeri(GL12.GL_TEXTURE_BINDING_3D, unit);
                samplers[unit] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, unit);
            }
            for (int binding = 0; binding < blocks.length; binding++) {
                blocks[binding] = GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, binding);
            }
            uniformBuffer = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
        }

        void restore() {
            for (int unit = 0; unit < StagePlan.UNITS; unit++) {
                GL45.glBindTextureUnit(unit, 0);
                if (textures[unit] != 0) {
                    GL45.glBindTextureUnit(unit, textures[unit]);
                }
                if (arrays[unit] != 0) {
                    GL45.glBindTextureUnit(unit, arrays[unit]);
                }
                if (volumes[unit] != 0) {
                    GL45.glBindTextureUnit(unit, volumes[unit]);
                }
                GL33.glBindSampler(unit, samplers[unit]);
            }
            for (int binding = 0; binding < blocks.length; binding++) {
                GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, binding, blocks[binding]);
            }
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uniformBuffer);
        }
    }

    private final StageManifest manifest;
    private final StagePlan plan;
    private final String log;
    private final Map<String, Integer> programs = new HashMap<>();
    private final Map<StagePlan.Pass, Integer> passPrograms = new IdentityHashMap<>();
    private final Map<Integer, Integer> samplers = new HashMap<>();
    private final Map<String, Integer> assets = new HashMap<>();
    private final Map<String, int[]> targets = new HashMap<>();
    private final Map<StagePlan.Pass, int[]> framebuffers = new IdentityHashMap<>();
    private final Map<String, Integer> host = new HashMap<>();
    private final int block;
    private final int vertexArray;
    private int display;
    private int width;
    private int height;
    /** Which of a persistent target's two textures this frame writes. */
    private int parity;

    /**
     * @param manifest the folder's manifest, of a version that says what is to be run
     * @param known    the names of the textures the caller gives; a manifest that asks for another is refused
     * @param log      what begins each line logged, such as "Stage" or "Stage check"
     */
    StageRunner(StageManifest manifest, Set<String> known, String log) throws IOException {
        this.manifest = manifest;
        this.log = log;
        plan = manifest.plan();
        if (plan == null) {
            throw new IllegalStateException("the manifest is of version " + manifest.version() + ", which does not say what is "
                + "to be run; version " + StageManifest.PLANNED_FROM + " is run");
        }
        for (String name : plan.hostInputs().keySet()) {
            if (!known.contains(name)) {
                throw new IllegalStateException("the manifest asks the host for what it does not give: " + name);
            }
        }
        List<StagePlan.Pass> all = new ArrayList<>(plan.passes());
        all.addAll(plan.diagnosticPasses());
        boolean made = false;
        try {
            for (StagePlan.Pass pass : all) {
                if (pass.blending) {
                    throw new IllegalStateException("pass " + pass.name + " asks for blending and the manifest does not say how");
                }
                String key = pass.vertex + "|" + pass.fragment;
                Integer program = programs.get(key);
                if (program == null) {
                    program = link(log, pass.program, text(pass.vertex), text(pass.fragment));
                    programs.put(key, program);
                    frameBlock(pass, program);
                }
                samplersOf(pass, program);
                passPrograms.put(pass, program);
            }
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            for (StagePlan.Asset asset : plan.assets().values()) {
                assets.put(asset.name, asset(asset));
            }
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            block = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(block, FrameBlock.BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
            vertexArray = GL45.glCreateVertexArrays();
            made = true;
        } finally {
            if (!made) {
                delete();
            }
        }
    }

    StagePlan plan() {
        return plan;
    }

    private String text(String file) throws IOException {
        return new String(manifest.load(file), StandardCharsets.UTF_8);
    }

    /** A pass's samplers must be bound by the program where the manifest says: the host sets none. */
    private void samplersOf(StagePlan.Pass pass, int program) {
        for (StagePlan.Input input : pass.inputs) {
            int location = GL20.glGetUniformLocation(program, input.sampler);
            if (location < 0) {
                Quadrature.LOGGER.info("{}: pass {}: the program does not use {}.", log, pass.name, input.sampler);
                continue;
            }
            int unit = GL20.glGetUniformi(program, location);
            if (unit != input.unit) {
                throw new IllegalStateException("pass " + pass.name + ": the program binds " + input.sampler + " to unit " + unit
                    + " and the manifest to " + input.unit);
            }
        }
    }

    /**
     * Sets the frame's block as the driver lays it out for a program beside the layout the host fills,
     * logs it, and refuses the program where they differ.
     */
    private void frameBlock(StagePlan.Pass pass, int program) {
        Integer binding = pass.uniformBlocks.get(StagePlan.FRAME_BLOCK);
        int index = GL31.glGetUniformBlockIndex(program, StagePlan.FRAME_BLOCK);
        if (index == GL31.GL_INVALID_INDEX) {
            Quadrature.LOGGER.info("{}: program {}: it does not read the block {}.", log, pass.program, StagePlan.FRAME_BLOCK);
            return;
        }
        int bound = GL31.glGetActiveUniformBlocki(program, index, GL31.GL_UNIFORM_BLOCK_BINDING);
        int size = GL31.glGetActiveUniformBlocki(program, index, GL31.GL_UNIFORM_BLOCK_DATA_SIZE);
        int count = GL31.glGetActiveUniformBlocki(program, index, GL31.GL_UNIFORM_BLOCK_ACTIVE_UNIFORMS);
        StringBuilder members = new StringBuilder();
        List<String> wrong = new ArrayList<>();
        if (count > 0) {
            IntBuffer indices = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
            GL31.glGetActiveUniformBlockiv(program, index, GL31.GL_UNIFORM_BLOCK_ACTIVE_UNIFORM_INDICES, indices);
            for (int i = 0; i < count; i++) {
                int member = indices.get(i);
                String name = GL31.glGetActiveUniformName(program, member);
                int offset = GL31.glGetActiveUniformsi(program, member, GL31.GL_UNIFORM_OFFSET);
                int expected = FrameBlock.offsetOf(name);
                members.append(i == 0 ? "" : "; ").append(name).append(" type 0x")
                    .append(Integer.toHexString(GL31.glGetActiveUniformsi(program, member, GL31.GL_UNIFORM_TYPE)))
                    .append(" count ").append(GL31.glGetActiveUniformsi(program, member, GL31.GL_UNIFORM_SIZE))
                    .append(" at ").append(offset)
                    .append(", array stride ").append(GL31.glGetActiveUniformsi(program, member, GL31.GL_UNIFORM_ARRAY_STRIDE))
                    .append(", matrix stride ").append(GL31.glGetActiveUniformsi(program, member, GL31.GL_UNIFORM_MATRIX_STRIDE));
                if (expected < 0) {
                    wrong.add(name + " is no member the host fills");
                } else if (expected != offset) {
                    wrong.add(name + " lies at " + offset + " and the host fills it at " + expected);
                }
            }
        }
        if (size != plan.frameBlockBytes()) {
            wrong.add("the block is " + size + " bytes and the manifest's is " + plan.frameBlockBytes());
        }
        if (binding == null) {
            wrong.add("the manifest gives the pass no binding for the block");
        } else if (bound != binding) {
            wrong.add("the program reads the block from binding " + bound + " and the manifest says " + binding);
        }
        Quadrature.LOGGER.info("{}: program {}: the block {} as the driver has it: binding {}, {} bytes, {} members: {}.", log,
            pass.program, StagePlan.FRAME_BLOCK, bound, size, count, members);
        if (!wrong.isEmpty()) {
            Quadrature.LOGGER.error("{}: program {}: the block {} is not as the host fills it: {}.", log, pass.program,
                StagePlan.FRAME_BLOCK, String.join("; ", wrong));
            throw new IllegalStateException("program " + pass.program + " lays the frame's block out otherwise than the host fills it");
        }
    }

    private int asset(StagePlan.Asset asset) throws IOException {
        byte[] bytes = manifest.load(asset.file);
        long expected = 4L * asset.size[0] * asset.size[1] * (asset.dimensions == 3 ? asset.size[2] : 1);
        if (bytes.length != expected) {
            throw new IllegalStateException("asset " + asset.name + " is " + bytes.length + " bytes; its size gives " + expected);
        }
        ByteBuffer data = ByteBuffer.allocateDirect(bytes.length);
        data.put(bytes).flip();
        int greatest = Math.max(asset.size[0], Math.max(asset.size[1], asset.dimensions == 3 ? asset.size[2] : 1));
        int levels = asset.mipmaps ? 32 - Integer.numberOfLeadingZeros(greatest) : 1;
        int texture;
        if (asset.dimensions == 3) {
            texture = GL45.glCreateTextures(GL12.GL_TEXTURE_3D);
            GL45.glTextureStorage3D(texture, levels, GL11.GL_RGBA8, asset.size[0], asset.size[1], asset.size[2]);
            GL45.glTextureSubImage3D(texture, 0, 0, 0, 0, asset.size[0], asset.size[1], asset.size[2], GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, data);
        } else {
            texture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(texture, levels, GL11.GL_RGBA8, asset.size[0], asset.size[1]);
            GL45.glTextureSubImage2D(texture, 0, 0, 0, asset.size[0], asset.size[1], GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
        }
        if (asset.mipmaps) {
            GL45.glGenerateTextureMipmap(texture);
        }
        return texture;
    }

    /** The sampler object that reads as the manifest says, made the first time it is asked for. */
    int sampler(StagePlan.Sampler how, boolean mipmaps) {
        int key = how.key() | (mipmaps ? 1 << 8 : 0);
        Integer made = samplers.get(key);
        if (made != null) {
            return made;
        }
        int sampler = GL45.glCreateSamplers();
        boolean linear = how.filter == StagePlan.Filter.LINEAR;
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, mipmaps ? linear ? GL11.GL_LINEAR_MIPMAP_LINEAR
            : GL11.GL_NEAREST_MIPMAP_NEAREST : linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, linear ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        int wrap = how.wrap == StagePlan.Wrap.REPEAT ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE;
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, wrap);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, wrap);
        GL33.glSamplerParameteri(sampler, GL12.GL_TEXTURE_WRAP_R, wrap);
        boolean compare = how.comparison == StagePlan.Comparison.LESS_OR_EQUAL;
        GL33.glSamplerParameteri(sampler, GL14.GL_TEXTURE_COMPARE_MODE, compare ? GL30.GL_COMPARE_REF_TO_TEXTURE : GL11.GL_NONE);
        if (compare) {
            GL33.glSamplerParameteri(sampler, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
        }
        samplers.put(key, sampler);
        return sampler;
    }

    private static int format(String name) {
        switch (name) {
            case "RGBA8":
                return GL11.GL_RGBA8;
            case "RGBA16F":
                return GL30.GL_RGBA16F;
            case "RGBA32F":
                return GL30.GL_RGBA32F;
            case "R8":
                return GL30.GL_R8;
            case "RG8":
                return GL30.GL_RG8;
            case "R16F":
                return GL30.GL_R16F;
            case "RG16F":
                return GL30.GL_RG16F;
            case "R32F":
                return GL30.GL_R32F;
            case "R11F_G11F_B10F":
                return GL30.GL_R11F_G11F_B10F;
            default:
                throw new IllegalStateException("a format that is not made: " + name);
        }
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /**
     * Makes the stage's targets for a window's size, anew where they were made before. Every target
     * starts as zeros.
     */
    void size(int newWidth, int newHeight) {
        deleteTargets();
        width = newWidth;
        height = newHeight;
        parity = 0;
        for (StagePlan.Target target : plan.targets().values()) {
            int[] textures = new int[target.persistent ? 2 : 1];
            for (int i = 0; i < textures.length; i++) {
                textures[i] = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
                GL45.glTextureStorage2D(textures[i], 1, format(target.format), StagePlan.side(width, target.sizeDivisor),
                    StagePlan.side(height, target.sizeDivisor));
                GL44.glClearTexImage(textures[i], 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
            }
            targets.put(target.name, textures);
        }
        display = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(display, 1, GL11.GL_RGBA8, width, height);
        GL44.glClearTexImage(display, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        for (StagePlan.Pass pass : passPrograms.keySet()) {
            int[] pair = new int[2];
            for (int which = 0; which < 2; which++) {
                int framebuffer = GL45.glCreateFramebuffers();
                pair[which] = framebuffer;
                int greatest = 0;
                for (StagePlan.Output output : pass.outputs) {
                    greatest = Math.max(greatest, output.location);
                }
                int[] buffers = new int[greatest + 1];
                for (StagePlan.Output output : pass.outputs) {
                    GL45.glNamedFramebufferTexture(framebuffer, GL30.GL_COLOR_ATTACHMENT0 + output.location,
                        written(output.target, which), 0);
                    buffers[output.location] = GL30.GL_COLOR_ATTACHMENT0 + output.location;
                }
                GL45.glNamedFramebufferDrawBuffers(framebuffer, buffers);
                int status = GL45.glCheckNamedFramebufferStatus(framebuffer, GL30.GL_FRAMEBUFFER);
                if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("the framebuffer of pass " + pass.name + " is incomplete: 0x"
                        + Integer.toHexString(status));
                }
            }
            framebuffers.put(pass, pair);
        }
    }

    /** The texture a target's writers write in a frame of a parity. */
    private int written(String target, int which) {
        if (target.equals(StagePlan.DISPLAY)) {
            return display;
        }
        int[] textures = targets.get(target);
        return textures[textures.length == 2 ? which : 0];
    }

    /** The texture of a target as this frame writes it, or of the display; for reading back before {@link #endFrame}. */
    int texture(String target) {
        return written(target, parity);
    }

    /** The texture the passes that write the display write: what the host presents. */
    int display() {
        return display;
    }

    /** Gives a texture of the host's for the passes run from now on. */
    void host(String name, int texture) {
        host.put(name, texture);
    }

    /** Writes the frame's block: {@link FrameBlock#BYTES} bytes from the buffer's position. */
    void block(ByteBuffer bytes) {
        GL45.glNamedBufferSubData(block, 0, bytes);
    }

    /** What the driver holds of the frame's block, little-endian. */
    ByteBuffer blockHeld() {
        ByteBuffer held = ByteBuffer.allocateDirect(FrameBlock.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        GL45.glGetNamedBufferSubData(block, 0, held);
        return held;
    }

    /** Sets the targets that ask for it to zeros: for a frame that drops history. */
    void reset() {
        for (StagePlan.Target target : plan.targets().values()) {
            if (target.zeroOnReset) {
                for (int texture : targets.get(target.name)) {
                    GL44.glClearTexImage(texture, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer) null);
                }
            }
        }
    }

    /**
     * Draws passes in order. The caller has disabled what a pass has none of (the depth test, culling,
     * the alpha test, the scissor, encoding on write) and set every color channel to be written; this
     * binds each pass's inputs, block, outputs and program, sets its viewport, and draws.
     *
     * @param passes the passes of every frame, or diagnostic ones
     * @param after  called after each pass is drawn, or null
     */
    void run(List<StagePlan.Pass> passes, Consumer<StagePlan.Pass> after) {
        GL30.glBindVertexArray(vertexArray);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthMask(false);
        for (StagePlan.Pass pass : passes) {
            for (StagePlan.Input input : pass.inputs) {
                int texture;
                int sampler;
                switch (input.source) {
                    case HOST: {
                        Integer given = host.get(input.name);
                        if (given == null || given == 0) {
                            throw new IllegalStateException("the host has not given " + input.name);
                        }
                        texture = given;
                        sampler = sampler(plan.hostInputs().get(input.name).sampler, false);
                        break;
                    }
                    case ASSET: {
                        StagePlan.Asset asset = plan.assets().get(input.name);
                        texture = assets.get(input.name);
                        sampler = sampler(asset.sampler, asset.mipmaps);
                        break;
                    }
                    case TARGET:
                        texture = written(input.name, parity);
                        sampler = sampler(plan.targets().get(input.name).sampler, false);
                        break;
                    default:
                        texture = written(input.name, 1 - parity);
                        sampler = sampler(plan.targets().get(input.name).sampler, false);
                        break;
                }
                GL45.glBindTextureUnit(input.unit, texture);
                GL33.glBindSampler(input.unit, sampler);
            }
            Integer binding = pass.uniformBlocks.get(StagePlan.FRAME_BLOCK);
            if (binding != null) {
                GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, binding, block);
            }
            int divisor = plan.divisor(pass);
            int framebuffer = framebuffers.get(pass)[parity];
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL11.glViewport(0, 0, StagePlan.side(width, divisor), StagePlan.side(height, divisor));
            if (pass.clear) {
                float[] zeros = {0f, 0f, 0f, 0f};
                for (StagePlan.Output output : pass.outputs) {
                    GL45.glClearNamedFramebufferfv(framebuffer, GL11.GL_COLOR, output.location, zeros);
                }
            }
            GL20.glUseProgram(passPrograms.get(pass));
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, pass.vertices);
            if (after != null) {
                after.accept(pass);
            }
        }
    }

    /** The frame is over: what a persistent target holds becomes its history. */
    void endFrame() {
        parity ^= 1;
    }

    private void deleteTargets() {
        for (int[] pair : framebuffers.values()) {
            for (int framebuffer : pair) {
                GL30.glDeleteFramebuffers(framebuffer);
            }
        }
        framebuffers.clear();
        for (int[] textures : targets.values()) {
            for (int texture : textures) {
                GL11.glDeleteTextures(texture);
            }
        }
        targets.clear();
        if (display != 0) {
            GL11.glDeleteTextures(display);
            display = 0;
        }
    }

    /** Deletes everything made here. */
    void delete() {
        deleteTargets();
        GL20.glUseProgram(0);
        for (int program : programs.values()) {
            GL20.glDeleteProgram(program);
        }
        programs.clear();
        for (int texture : assets.values()) {
            GL11.glDeleteTextures(texture);
        }
        assets.clear();
        for (int sampler : samplers.values()) {
            GL33.glDeleteSamplers(sampler);
        }
        samplers.clear();
        if (block != 0) {
            GL15.glDeleteBuffers(block);
        }
        if (vertexArray != 0) {
            GL30.glDeleteVertexArrays(vertexArray);
        }
    }

    /** Compiles and links a program from source that is the folder's. Errors are logged whole. */
    static int link(String log, String name, String vertexSource, String fragmentSource) {
        int vertex = compile(log, name + " vertex", GL20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(log, name + " fragment", GL20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            String message = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            Quadrature.LOGGER.error("{}: the {} program does not link:\n{}", log, name, message);
            throw new IllegalStateException("the " + name + " program does not link");
        }
        return program;
    }

    private static int compile(String log, String name, int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String message = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            Quadrature.LOGGER.error("{}: the {} stage does not compile:\n{}", log, name, message);
            throw new IllegalStateException("the " + name + " stage does not compile");
        }
        return shader;
    }
}
