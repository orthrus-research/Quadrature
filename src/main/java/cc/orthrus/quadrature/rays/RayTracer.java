package cc.orthrus.quadrature.rays;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.scene.LightScene;
import cc.orthrus.quadrature.core.scene.PublishedScene;
import cc.orthrus.quadrature.core.scene.TraceFormat;
import cc.orthrus.quadrature.terrain.TerrainHook;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

import java.nio.IntBuffer;

/**
 * The trace through the light scene's bricks on the GPU, behind {@link RayTrace}. It reads the scene
 * from {@link TerrainHook}, keeps a bit for each state that blocks light in a buffer of its own, 8 KiB,
 * sent again when a state is first met, and puts back every binding it changes.
 */
public final class RayTracer implements RayTrace {

    /** {@code -Dquadrature.rays.fetch=texture}: the lookup and bricks read through their buffer textures. */
    static final boolean TEXTURES = "texture".equals(System.getProperty("quadrature.rays.fetch"));

    private static RayTracer shared;

    private final ComputeState state = new ComputeState();
    private int program;
    private boolean failed;
    private int blockingBuffer;
    private long statesSent = -1;
    private final IntBuffer blockingBits = BufferUtils.createIntBuffer(LightScene.STATES / 32);

    /** The one tracer, made on first use on the client thread. */
    public static RayTracer get() {
        if (shared == null) {
            shared = new RayTracer();
        }
        return shared;
    }

    private RayTracer() {
    }

    @Override
    public PublishedScene scene() {
        PublishedScene scene = TerrainHook.publishedScene();
        return scene == null || scene.lookupBuffer() == 0 || TerrainHook.lightScene() == null || !prepare() ? null : scene;
    }

    private boolean prepare() {
        if (program == 0 && !failed) {
            program = TraceProgram.link(TEXTURES ? "trace through textures" : "trace", TEXTURES
                ? TraceProgram.TRACE_TEXTURE_COMPUTE : TraceProgram.TRACE_COMPUTE);
            failed = program == 0;
            blockingBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(blockingBuffer, (long) LightScene.STATES / 8, GL44.GL_DYNAMIC_STORAGE_BIT);
            Quadrature.LOGGER.info("Ray trace ready: work groups of {}, the lookup and bricks read {}.", TraceProgram.GROUP,
                TEXTURES ? "through buffer textures" : "from storage blocks");
        }
        return program != 0;
    }

    /** The buffer of a bit for each state that blocks light, brought up to the scene. */
    int blockingBuffer() {
        LightScene light = TerrainHook.lightScene();
        if (light != null && light.statesDefined() != statesSent) {
            statesSent = light.statesDefined();
            blockingBits.clear();
            blockingBits.put(TraceFormat.blockingStates(light)).flip();
            GL45.glNamedBufferSubData(blockingBuffer, 0, blockingBits);
        }
        return blockingBuffer;
    }

    @Override
    public Timed trace(int rayBuffer, int resultBuffer, int count) {
        PublishedScene scene = scene();
        if (scene == null) {
            return null;
        }
        Timing timing = new Timing(count);
        GL33.glQueryCounter(timing.before, GL33.GL_TIMESTAMP);
        dispatch(scene, rayBuffer, resultBuffer, count);
        GL33.glQueryCounter(timing.after, GL33.GL_TIMESTAMP);
        return timing;
    }

    /** Traces without timing, as a step that prepares other rays. */
    void traceUntimed(int rayBuffer, int resultBuffer, int count) {
        PublishedScene scene = scene();
        if (scene != null) {
            dispatch(scene, rayBuffer, resultBuffer, count);
        }
    }

    private void dispatch(PublishedScene scene, int rayBuffer, int resultBuffer, int count) {
        int blocking = blockingBuffer();
        state.save();
        if (TEXTURES) {
            GL45.glBindTextureUnit(TraceProgram.LOOKUP_UNIT, scene.lookupTexture());
            GL45.glBindTextureUnit(TraceProgram.BRICK_UNIT, scene.brickTexture());
        } else {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, scene.lookupBuffer());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, scene.brickBuffer());
        }
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, rayBuffer);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, resultBuffer);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, blocking);
        GL41.glProgramUniform3i(program, 0, (scene.sizeX() - 1) / 2, (scene.sizeY() - 1) / 2, count);
        GL41.glProgramUniform3i(program, 1, Math.floorMod(scene.originX(), scene.sizeX()),
            Math.floorMod(scene.originY(), scene.sizeY()), Math.floorMod(scene.originZ(), scene.sizeZ()));
        GL41.glProgramUniform3i(program, 2, scene.sizeX(), scene.sizeY(), scene.sizeZ());
        GL20.glUseProgram(program);
        // Rays written by an earlier dispatch are read here, and results by a later one or read back.
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GL43.glDispatchCompute((count + TraceProgram.GROUP - 1) / TraceProgram.GROUP, 1, 1);
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        state.restore();
    }

    /** Two timestamps around a dispatch. */
    private static final class Timing implements Timed {
        final int before = GL15.glGenQueries();
        final int after = GL15.glGenQueries();
        final int count;
        double millis = Double.NaN;

        Timing(int count) {
            this.count = count;
        }

        @Override
        public boolean done() {
            if (Double.isNaN(millis)) {
                if (GL15.glGetQueryObjecti(after, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                    return false;
                }
                long start = GL33.glGetQueryObjectui64(before, GL15.GL_QUERY_RESULT);
                long end = GL33.glGetQueryObjectui64(after, GL15.GL_QUERY_RESULT);
                millis = (end - start) / 1e6;
                GL15.glDeleteQueries(before);
                GL15.glDeleteQueries(after);
            }
            return true;
        }

        @Override
        public double gpuMillis() {
            return millis;
        }

        @Override
        public int count() {
            return count;
        }
    }
}
