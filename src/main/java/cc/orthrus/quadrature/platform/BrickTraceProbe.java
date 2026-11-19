package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.core.scene.PublishedScene;
import cc.orthrus.quadrature.core.scene.TraceFormat;
import cc.orthrus.quadrature.core.view.View;
import cc.orthrus.quadrature.rays.RayTrace;
import cc.orthrus.quadrature.rays.RayTracer;
import cc.orthrus.quadrature.terrain.TerrainHook;

import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

/**
 * The tier benchmark's indirect-trace probe, over the trace through the light scene's bricks
 * ({@link RayTracer}): the batch of rays MEDIUM's indirect light casts, 16 by 8 by 16 probes 8 blocks
 * apart about the eye, each casting 32 rays 64 blocks long in directions spread over the sphere. The
 * rays do not follow the render resolution. Needs the scene on the GPU
 * ({@code -Dquadrature.scene=true -Dquadrature.scene.gpu=true}); where it is absent, or the trace's
 * program does not build, the probe is not ready and the benchmark runs without it.
 */
final class BrickTraceProbe implements TraceProbe {

    /** Whether the scene the trace reads is asked for at all. */
    static final boolean REQUESTED = Boolean.getBoolean("quadrature.scene") && Boolean.getBoolean("quadrature.scene.gpu");

    private static final int ACROSS = 16;
    private static final int UP = 8;
    private static final int SPACING = 8;
    private static final int PER_PROBE = 32;
    private static final float LENGTH = 64;
    private static final int COUNT = ACROSS * UP * ACROSS * PER_PROBE;
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));

    private final float[] rays = new float[COUNT * TraceFormat.RAY_FLOATS];
    private int rayBuffer;
    private int resultBuffer;
    private final double[] filledFrom = {Double.NaN, Double.NaN, Double.NaN};

    @Override
    public String name() {
        return "the brick trace, MEDIUM's probes (16x8x16, 32 rays of 64 blocks)";
    }

    @Override
    public boolean ready() {
        RayTracer tracer = RayTracer.get();
        // Asking for the scene builds the program the first time, so whether it failed is known after.
        return tracer.scene() != null && !tracer.failed() && TerrainHook.mainView() != null;
    }

    @Override
    public long dispatch(int width, int height) {
        RayTracer tracer = RayTracer.get();
        PublishedScene scene = tracer.scene();
        View view = TerrainHook.mainView();
        if (scene == null || tracer.failed() || view == null) {
            return 0;
        }
        if (rayBuffer == 0) {
            rayBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(rayBuffer, (long) COUNT * RayTrace.RAY_BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
            resultBuffer = GL45.glCreateBuffers();
            GL45.glNamedBufferStorage(resultBuffer, (long) COUNT * RayTrace.RESULT_BYTES, 0);
        }
        // Relative to the lowest corner of the box's origin cube, as the trace reads rays.
        double x = view.originX() - scene.originX() * 16.0;
        double y = view.originY() - scene.originY() * 16.0;
        double z = view.originZ() - scene.originZ() * 16.0;
        if (x != filledFrom[0] || y != filledFrom[1] || z != filledFrom[2]) {
            fill(x, y, z);
            GL45.glNamedBufferSubData(rayBuffer, 0, rays);
            filledFrom[0] = x;
            filledFrom[1] = y;
            filledFrom[2] = z;
        }
        RayTrace.Timed timed = tracer.trace(rayBuffer, resultBuffer, COUNT);
        return timed == null ? 0 : COUNT;
    }

    /** The probes about a place, each with its directions turned by its own angle so no two probes cast alike. */
    private void fill(double x, double y, double z) {
        int at = 0;
        int probe = 0;
        for (int i = 0; i < ACROSS; i++) {
            for (int j = 0; j < UP; j++) {
                for (int k = 0; k < ACROSS; k++, probe++) {
                    float px = (float) (x + (i - (ACROSS - 1) / 2.0) * SPACING);
                    float py = (float) (y + (j - (UP - 1) / 2.0) * SPACING);
                    float pz = (float) (z + (k - (ACROSS - 1) / 2.0) * SPACING);
                    double turn = probe * 0.6180339887498949 * 2 * Math.PI;
                    for (int n = 0; n < PER_PROBE; n++, at += TraceFormat.RAY_FLOATS) {
                        double dy = 1 - (n + 0.5) * 2 / PER_PROBE;
                        double r = Math.sqrt(1 - dy * dy);
                        double angle = n * GOLDEN_ANGLE + turn;
                        TraceFormat.ray(rays, at, px, py, pz, LENGTH, (float) (Math.cos(angle) * r), (float) dy,
                            (float) (Math.sin(angle) * r));
                    }
                }
            }
        }
    }
}
