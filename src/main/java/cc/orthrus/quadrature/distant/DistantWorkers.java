package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.lod.BuiltTile;
import cc.orthrus.quadrature.core.lod.RunMesher;
import cc.orthrus.quadrature.core.lod.RunTile;
import cc.orthrus.quadrature.core.lod.TileKey;
import cc.orthrus.quadrature.core.lod.TileMesh;

import io.github.opencubicchunks.cubicchunks.api.client.IClientPreviewWork;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewWithdrawnException;

import net.minecraft.block.state.IBlockState;
import net.minecraft.world.biome.Biome;

import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The work that samples and meshes distant tiles, and where it runs. Quadrature starts no threads
 * of its own for terrain work: tiles are built on Cubature's client preview pool
 * ({@code ICubeSource.previewWork()}), which keeps the threads, a sampler for each, and a governor
 * that lets fewer run while frames or the integrated server's ticks run long.
 *
 * <p>The fallback, for a cube source that has no such pool: a small pool of distant terrain's own,
 * two threads unless {@code -Dquadrature.distant.threads} says otherwise, at the lowest priority.
 * That setting has no effect while Cubature's pool is used.
 *
 * <p>A task samples one tile with its apron from the terrain preview, joins its buried strata,
 * meshes it, and leaves the result in a queue the client thread empties. Each thread keeps one
 * tile, mesher and mesh for all its tasks.
 */
final class DistantWorkers {

    /** The fallback pool's size. */
    static final int THREADS = Math.max(1, Math.min(8, Integer.getInteger("quadrature.distant.threads", 2)));

    /** A task's outcome when it has no tile to hand over. */
    static final class Ended {
        final long key;
        final long generation;
        /** Whether the preview was withdrawn or the tile abandoned, which is no fault of the tile's. */
        final boolean withdrawn;

        Ended(long key, long generation, boolean withdrawn) {
            this.key = key;
            this.generation = generation;
            this.withdrawn = withdrawn;
        }
    }

    private static final class Scratch implements ICubicTerrainPreview.ColumnConsumer {
        final RunTile tile;
        final RunMesher mesher = new RunMesher();
        final TileMesh mesh = new TileMesh();
        final DistantMaterials materials;
        IBlockState lastState;
        int lastMaterial;
        byte lastKind;

        Scratch(int floorY, int ceilingY, DistantMaterials materials) {
            tile = new RunTile(floorY, ceilingY);
            this.materials = materials;
        }

        @Override
        public void begin(Biome biome) {
            tile.beginColumn(Biome.getIdForBiome(biome));
        }

        @Override
        public void addRun(int minimumY, int maximumYExclusive, IBlockState state) {
            if (state != lastState) {
                lastState = state;
                lastKind = DistantMaterials.kind(state);
                lastMaterial = lastKind == RunTile.AIR ? 0 : materials.index(state);
            }
            tile.addRun(minimumY, maximumYExclusive, lastMaterial, lastKind);
        }
    }

    /**
     * A tile handed to Cubature's pool. Whatever ends it, the tile's end is told once: by its job,
     * or by the client thread when the task was cancelled or withdrawn before its job could.
     */
    private final class Request implements IClientPreviewWork.Job {
        final long key;
        final long generation;
        final AtomicBoolean told = new AtomicBoolean();
        IClientPreviewWork.Task task;

        Request(long key, long generation) {
            this.key = key;
            this.generation = generation;
        }

        @Override
        public void run(ICubicTerrainPreview.Sampler sampler, IClientPreviewWork.Task running) {
            DistantWorkers.this.run(sampler, key, generation, this, running);
        }
    }

    /** Cubature's pool, or null when the cube source has none and the fallback is used. */
    private final IClientPreviewWork work;
    private ExecutorService pool;
    /** Client thread only: the tiles on Cubature's pool whose end has not been taken yet. */
    private final HashMap<Long, Request> requests = new HashMap<>();
    private long order;
    private final ConcurrentLinkedQueue<BuiltTile> built = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BuiltTile> spare = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Ended> ended = new ConcurrentLinkedQueue<>();
    private final java.util.Set<Long> abandoned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final DistantMaterials materials;
    private final int floorY;
    private final int ceilingY;
    private final ThreadLocal<Scratch> scratch;
    private volatile boolean failureLogged;

    /**
     * @param work Cubature's client preview pool, or null to use the fallback pool
     */
    DistantWorkers(DistantMaterials materials, int floorY, int ceilingY, IClientPreviewWork work) {
        this.materials = materials;
        this.floorY = floorY;
        this.ceilingY = ceilingY;
        this.work = work;
        scratch = ThreadLocal.withInitial(() -> new Scratch(this.floorY, this.ceilingY, this.materials));
    }

    /** Whether tiles are built on Cubature's pool. */
    boolean governed() {
        return work != null;
    }

    /** The threads tiles are built on. */
    int threads() {
        return work != null ? work.threads() : THREADS;
    }

    /** How many tiles Cubature's governor lets run at once now; the fallback runs all its threads. */
    int running() {
        return work != null ? work.running() : THREADS;
    }

    /**
     * Builds one tile from a preview; its result, or its end, is found later in the queues. Tiles
     * are handed in in the schedule's order, which is the order they run in.
     */
    void submit(ICubicTerrainPreview preview, long key, long generation) {
        if (work != null) {
            Request request = new Request(key, generation);
            requests.put(key, request);
            request.task = work.submit(order++, request);
            return;
        }
        if (pool == null) {
            AtomicInteger made = new AtomicInteger();
            pool = Executors.newFixedThreadPool(THREADS, task -> {
                Thread thread = new Thread(task, "Quadrature distant terrain #" + made.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
        }
        pool.execute(() -> run(preview.createTerrainPreviewSampler(), key, generation, null, null));
    }

    /**
     * Once a frame, on the client thread: takes what Cubature's pool has ended and tells the end of
     * tiles whose tasks will never run. A task is withdrawn without running when the preview it was
     * handed in under has gone; the tile is told as abandoned, and is asked for again if still wanted.
     */
    void drain() {
        if (work == null) {
            return;
        }
        for (IClientPreviewWork.Task task = work.poll(); task != null; task = work.poll()) {
            if (task.state() == IClientPreviewWork.FAILED && task.job() instanceof Request) {
                // A job tells its own failures; this is one that escaped it.
                Request request = (Request) task.job();
                if (request.told.compareAndSet(false, true)) {
                    ended.add(new Ended(request.key, request.generation, false));
                }
            }
        }
        if (requests.isEmpty()) {
            return;
        }
        for (Request request : requests.values()) {
            if (request.task != null && request.task.state() == IClientPreviewWork.WITHDRAWN
                && request.told.compareAndSet(false, true)) {
                ended.add(new Ended(request.key, request.generation, true));
            }
        }
    }

    private void run(ICubicTerrainPreview.Sampler sampler, long key, long generation, Request request,
        IClientPreviewWork.Task task) {
        try {
            Scratch own = scratch.get();
            int level = TileKey.level(key);
            int cell = 1 << level;
            int originX = Math.toIntExact(TileKey.originX(key));
            int originZ = Math.toIntExact(TileKey.originZ(key));
            long started = System.nanoTime();
            own.tile.reset(level, originX, originZ);
            own.lastState = null;
            // Each cell is sampled at its middle, with the cell's width as its footprint.
            for (int z = -1; z <= RunTile.CELLS; z++) {
                if (task != null) {
                    if (task.cancelled()) {
                        // Cancelled, or its preview gone: the client thread tells its end if this cannot.
                        tell(request, new Ended(key, generation, true));
                        return;
                    }
                    // Where the governor has lowered how many tiles may run, this one waits its turn.
                    task.checkpoint();
                } else if (abandoned.contains(key)) {
                    // The tile left every ring while it was being sampled: stop here.
                    abandoned.remove(key);
                    ended.add(new Ended(key, generation, true));
                    return;
                }
                for (int x = -1; x <= RunTile.CELLS; x++) {
                    sampler.sampleColumn(originX + x * cell + cell / 2, originZ + z * cell + cell / 2, cell, own);
                }
            }
            own.tile.finish();
            long sampled = System.nanoTime();
            own.tile.collapseBuried();
            own.mesher.mesh(own.tile, own.mesh);
            BuiltTile result = spare.poll();
            if (result == null) {
                result = new BuiltTile();
            }
            result.set(key, generation, own.mesh, sampled - started, System.nanoTime() - sampled);
            if (request == null || request.told.compareAndSet(false, true)) {
                built.add(result);
            } else {
                spare.add(result);
            }
        } catch (TerrainPreviewWithdrawnException | InterruptedException withdrawn) {
            // The world is closing or was replaced: the tile is dropped and nothing is wrong.
            tell(request, new Ended(key, generation, true));
        } catch (RuntimeException | Error failure) {
            if (!failureLogged) {
                failureLogged = true;
                Quadrature.LOGGER.error("Distant terrain: tile level " + TileKey.level(key) + " at "
                    + TileKey.x(key) + ", " + TileKey.z(key) + " failed; later failures are counted, not logged.",
                    failure);
            }
            tell(request, new Ended(key, generation, false));
        }
    }

    /** Tells a tile's end, once: a tile on Cubature's pool may already have been told by the client thread. */
    private void tell(Request request, Ended end) {
        if (request == null || request.told.compareAndSet(false, true)) {
            ended.add(end);
        }
    }

    /**
     * Stops a tile that is no longer wanted; its end is reported like any other. On Cubature's pool
     * its task is cancelled, so one still waiting never runs and one that is running stops at its
     * next row. On the fallback pool the task is told to stop at its next row.
     */
    void abandon(long key) {
        if (work == null) {
            abandoned.add(key);
            return;
        }
        Request request = requests.get(key);
        if (request != null && request.task != null) {
            request.task.cancel();
            if (request.told.compareAndSet(false, true)) {
                ended.add(new Ended(key, request.generation, true));
            }
        }
    }

    /** Forgets a tile's task, once its end has been taken. */
    void settled(long key) {
        abandoned.remove(key);
        requests.remove(key);
    }

    /** The next finished tile, or null. Give it back with {@link #recycle} once it is stored. */
    BuiltTile takeBuilt() {
        return built.poll();
    }

    /** The next task that ended without a tile, or null. */
    Ended takeEnded() {
        return ended.poll();
    }

    void recycle(BuiltTile tile) {
        if (spare.size() < 2 * threads() + 2) {
            spare.add(tile);
        }
    }
}
