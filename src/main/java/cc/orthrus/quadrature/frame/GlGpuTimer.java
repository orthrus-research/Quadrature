package cc.orthrus.quadrature.frame;

import cc.orthrus.quadrature.core.frame.GpuTimer;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Times stages with OpenGL timer queries. Each frame uses its own row of queries from a ring a few
 * frames deep; a row's results are read when the ring comes back round to it, and only where the driver
 * reports them available, so reading never waits for the GPU.
 */
final class GlGpuTimer implements GpuTimer {

    /** Frames between issuing a query and reading it. Drivers usually answer within one or two. */
    private static final int RING = 4;

    private final int stageCount;
    private final int[][] queries;
    private final boolean[][] issued;
    private int frame = -1;
    private boolean active;
    private long dropped;

    GlGpuTimer(int stageCount) {
        this.stageCount = stageCount;
        queries = new int[RING][stageCount];
        issued = new boolean[RING][stageCount];
        for (int[] row : queries) {
            for (int i = 0; i < stageCount; i++) {
                row[i] = GL15.glGenQueries();
            }
        }
    }

    @Override
    public void beginFrame(Sink sink) {
        frame = (frame + 1) % RING;
        // This row was issued RING frames ago: take what is ready and drop the rest.
        for (int stage = 0; stage < stageCount; stage++) {
            if (!issued[frame][stage]) {
                continue;
            }
            issued[frame][stage] = false;
            int query = queries[frame][stage];
            if (GL15.glGetQueryObjecti(query, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                sink.gpuNanos(stage, GL33.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT));
            } else {
                dropped++;
            }
        }
    }

    @Override
    public void begin(int stageIndex) {
        if (frame < 0 || active || issued[frame][stageIndex]) {
            // A stage entered twice in one frame keeps its first measurement; a query cannot be reissued
            // before it is read.
            return;
        }
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[frame][stageIndex]);
        issued[frame][stageIndex] = true;
        active = true;
    }

    @Override
    public void end() {
        if (active) {
            GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
            active = false;
        }
    }

    /** Results that were not ready when their turn came. */
    long dropped() {
        return dropped;
    }
}
