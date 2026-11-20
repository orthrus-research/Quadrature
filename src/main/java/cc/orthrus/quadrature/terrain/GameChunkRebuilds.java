package cc.orthrus.quadrature.terrain;

import java.util.concurrent.atomic.LongAdder;

import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.world.World;

/**
 * The game's own chunk rebuilds under own terrain (PLAN item 12a): which layers the game still builds, and
 * what its rebuilds cost.
 *
 * <p>Where own terrain draws the three opaque layers of a world, the game's meshes of those layers are never
 * drawn, so its rebuild leaves them out: its per-block layer test answers false for them, and the layers
 * are never begun, rendered or uploaded. The visibility graph and the block entity lists are found before
 * that test and are found as before. The rule is decided once a rebuild, against the world the render chunk
 * belongs to, so a rebuild for a world own terrain does not serve, as when the game reloads its renderers for
 * a new world before the cube source follows it, keeps every layer. {@code -Dquadrature.terrain.gameOpaque=build}
 * restores the game's full rebuild for comparison.
 *
 * <p>Called from the game's chunk-mesh threads and, for the rebuilds it does at once near the player, from
 * the client thread.
 */
public final class GameChunkRebuilds {

    /** Whether the game still builds the opaque layers own terrain draws ({@code quadrature.terrain.gameOpaque=build}). */
    static final boolean BUILD_OPAQUE = "build".equals(System.getProperty("quadrature.terrain.gameOpaque"));

    private static final LongAdder WORKER_REBUILDS = new LongAdder();
    private static final LongAdder WORKER_NANOS = new LongAdder();
    private static final LongAdder CLIENT_REBUILDS = new LongAdder();
    private static final LongAdder CLIENT_NANOS = new LongAdder();
    private static final LongAdder LEFT_OUT = new LongAdder();

    private GameChunkRebuilds() {
    }

    /** Whether a rebuild of a render chunk of this world leaves out the opaque layers. */
    public static boolean leavesOutOpaque(World world) {
        return !BUILD_OPAQUE && TerrainHook.drawsOpaqueOf(world);
    }

    /** Whether a layer is one of the three own terrain draws. */
    public static boolean opaque(BlockRenderLayer layer) {
        return layer == BlockRenderLayer.SOLID || layer == BlockRenderLayer.CUTOUT_MIPPED || layer == BlockRenderLayer.CUTOUT;
    }

    /** Counts one finished rebuild and the time it took. */
    public static void rebuilt(long nanos, boolean leftOut) {
        if (Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            CLIENT_REBUILDS.increment();
            CLIENT_NANOS.add(nanos);
        } else {
            WORKER_REBUILDS.increment();
            WORKER_NANOS.add(nanos);
        }
        if (leftOut) {
            LEFT_OUT.increment();
        }
    }

    /** One line for the timing log, counted from the start; null before the game has rebuilt anything. */
    public static String summary() {
        long workers = WORKER_REBUILDS.sum();
        long client = CLIENT_REBUILDS.sum();
        if (workers + client == 0) {
            return null;
        }
        long workerNanos = WORKER_NANOS.sum();
        long clientNanos = CLIENT_NANOS.sum();
        return String.format("game chunk rebuilds: %d on its threads, %.1f ms in all (%.3f ms each); %d on the client "
                + "thread, %.1f ms in all (%.3f ms each); %d with the opaque layers left out%s",
            workers, workerNanos / 1e6, workers == 0 ? 0.0 : workerNanos / 1e6 / workers, client, clientNanos / 1e6,
            client == 0 ? 0.0 : clientNanos / 1e6 / client, LEFT_OUT.sum(), BUILD_OPAQUE ? " (the game builds them, as asked)" : "");
    }
}
