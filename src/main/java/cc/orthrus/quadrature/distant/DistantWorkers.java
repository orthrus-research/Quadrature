package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.lod.BuiltTile;
import cc.orthrus.quadrature.core.lod.RunMesher;
import cc.orthrus.quadrature.core.lod.RunTile;
import cc.orthrus.quadrature.core.lod.TileKey;
import cc.orthrus.quadrature.core.lod.TileMesh;

import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.api.worldgen.TerrainPreviewWithdrawnException;

import net.minecraft.block.state.IBlockState;
import net.minecraft.world.biome.Biome;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The threads that sample and mesh distant tiles. A stand-in: Quadrature starts no threads of its
 * own for terrain work, and this work belongs on Cubature's client work executor, which does not
 * exist yet. Until it does, distant terrain keeps a small pool of its own, two threads unless
 * {@code -Dquadrature.distant.threads} says otherwise, at the lowest priority.
 *
 * <p>A task samples one tile with its apron from the terrain preview, joins its buried strata,
 * meshes it, and leaves the result in a queue the client thread empties. Each thread keeps one
 * tile, mesher and mesh for all its tasks.
 */
final class DistantWorkers {

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

    private final ExecutorService pool;
    private final ConcurrentLinkedQueue<BuiltTile> built = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BuiltTile> spare = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Ended> ended = new ConcurrentLinkedQueue<>();
    private final java.util.Set<Long> abandoned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final DistantMaterials materials;
    private final int floorY;
    private final int ceilingY;
    private final ThreadLocal<Scratch> scratch;
    private volatile boolean failureLogged;

    DistantWorkers(DistantMaterials materials, int floorY, int ceilingY) {
        this.materials = materials;
        this.floorY = floorY;
        this.ceilingY = ceilingY;
        scratch = ThreadLocal.withInitial(() -> new Scratch(this.floorY, this.ceilingY, this.materials));
        AtomicInteger made = new AtomicInteger();
        pool = Executors.newFixedThreadPool(THREADS, task -> {
            Thread thread = new Thread(task, "Quadrature distant terrain #" + made.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    /** Builds one tile from a preview; its result, or its end, is found later in the queues. */
    void submit(ICubicTerrainPreview preview, long key, long generation) {
        pool.execute(() -> run(preview, key, generation));
    }

    private void run(ICubicTerrainPreview preview, long key, long generation) {
        try {
            Scratch own = scratch.get();
            int level = TileKey.level(key);
            int cell = 1 << level;
            int originX = Math.toIntExact(TileKey.originX(key));
            int originZ = Math.toIntExact(TileKey.originZ(key));
            long started = System.nanoTime();
            ICubicTerrainPreview.Sampler sampler = preview.createTerrainPreviewSampler();
            own.tile.reset(level, originX, originZ);
            own.lastState = null;
            // Each cell is sampled at its middle, with the cell's width as its footprint.
            for (int z = -1; z <= RunTile.CELLS; z++) {
                if (abandoned.contains(key)) {
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
            built.add(result);
        } catch (TerrainPreviewWithdrawnException withdrawn) {
            // The world is closing or was replaced: the tile is dropped and nothing is wrong.
            ended.add(new Ended(key, generation, true));
        } catch (RuntimeException | Error failure) {
            if (!failureLogged) {
                failureLogged = true;
                Quadrature.LOGGER.error("Distant terrain: tile level " + TileKey.level(key) + " at "
                    + TileKey.x(key) + ", " + TileKey.z(key) + " failed; later failures are counted, not logged.",
                    failure);
            }
            ended.add(new Ended(key, generation, false));
        }
    }

    /** Tells the task building a tile to stop at its next row; its end is reported like any other. */
    void abandon(long key) {
        abandoned.add(key);
    }

    /** Forgets a request to stop, once the tile's task has ended some other way. */
    void settled(long key) {
        abandoned.remove(key);
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
        if (spare.size() < 2 * THREADS + 2) {
            spare.add(tile);
        }
    }
}
