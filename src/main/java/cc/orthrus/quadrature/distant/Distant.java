package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.lod.BuiltTile;
import cc.orthrus.quadrature.core.lod.FaceRecord;
import cc.orthrus.quadrature.core.lod.Rings;
import cc.orthrus.quadrature.core.lod.TileCulling;
import cc.orthrus.quadrature.core.lod.TileDraws;
import cc.orthrus.quadrature.core.lod.TileKey;
import cc.orthrus.quadrature.core.lod.TileMesh;
import cc.orthrus.quadrature.core.lod.TileSchedule;
import cc.orthrus.quadrature.core.view.View;
import cc.orthrus.quadrature.depth.DepthCalls;
import cc.orthrus.quadrature.terrain.DriverMessageAccess;

import io.github.opencubicchunks.cubicchunks.api.client.CubeSource;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayList;

/**
 * Distant terrain, first frames: the generator's terrain preview is sampled into run tiles in rings
 * round the camera, meshed, stored in an arena of its own and drawn after near terrain's opaque
 * layers, with flat colors. What the box of loaded cubes holds is left to near terrain.
 *
 * <p>Tiles are built on a small pool of threads (a stand-in, see {@link DistantWorkers}), coarsest
 * level first and nearest first; everything else here runs on the client thread, once a world pass.
 *
 * <p>Off unless {@code -Dquadrature.distant=own}. Settings: {@code quadrature.distant.reach}, how far
 * ring 0 reaches in blocks (384); {@code quadrature.distant.levels}, how many levels (7, which
 * reaches 24.5 km); {@code quadrature.distant.world}, half the side of the world's square in blocks,
 * outside which no tile is built (unset: no bound; the preview does not say where its world ends).
 */
final class Distant {

    private static final int REACH = Integer.getInteger("quadrature.distant.reach", 384);
    private static final int LEVELS = Integer.getInteger("quadrature.distant.levels", 7);
    private static final long WORLD_HALF = Long.getLong("quadrature.distant.world", 0L);
    private static final boolean LOG = Boolean.getBoolean("quadrature.frameLog");
    private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;
    /** The client thread's time for storing tiles in a frame, as near terrain's for placing meshes. */
    private static final long PLACE_BUDGET_NANOS = 1_000_000L;
    /** The camera must move this far along an axis before the rings are selected again. */
    private static final int RESELECT_BLOCKS = 32;
    private static final int TIMERS = 8;

    /** A stored tile: where it lies in the arena and what a draw needs of it. */
    private static final class Resident {
        long block;
        int bytes;
        int page;
        int firstFace;
        int tintWord;
        int faces;
        int minimumY;
        int maximumY;
        final int[] groupOffset = new int[TileMesh.GROUPS];
        final int[] groupCount = new int[TileMesh.GROUPS];
    }

    private final ICubeSource source;
    private final Rings rings = new Rings(REACH, LEVELS);
    private final TileSchedule schedule = new TileSchedule();
    private final DistantMaterials materials = new DistantMaterials();
    private final DistantArena arena = new DistantArena();
    private final DistantProgram program = new DistantProgram();
    private final TileDraws draws = new TileDraws();
    private final View view = new View();
    private final Long2ObjectOpenHashMap<Resident> residents = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet inFlight = new LongOpenHashSet();
    private final float[] modelView = new float[16];
    private final float[] projection = new float[16];
    private final float[] nearBox = new float[6];
    private final int[] levelCount = new int[2];
    private DistantWorkers workers;
    private ICubicTerrainPreview preview;
    private long worldIdentity = -1;
    private int floorY;
    private int ceilingY;
    private long selectedX = Long.MIN_VALUE;
    private long selectedZ = Long.MIN_VALUE;
    private boolean failed;
    private boolean depthWarned;
    private long frame;

    // GPU time, from pairs of timestamps read a few frames later.
    private final int[] timerStart = new int[TIMERS];
    private final int[] timerEnd = new int[TIMERS];
    private boolean timersMade;

    // Counters for the timing log.
    private long lastLogNanos = System.nanoTime();
    private long tilesBuilt;
    private long tilesPlaced;
    private long tilesRefused;
    private long tilesFailed;
    private long tilesAbandoned;
    private long tilesEvicted;
    private long sampleNanos;
    private long meshNanos;
    private long placeNanos;
    private long mostPlaceNanos;
    private long lastFaces;
    private int lastDraws;
    private int lastTilesDrawn;
    private long gpuNanos;
    private long gpuSamples;
    private long mostGpuNanos;
    private long cpuNanos;
    private long cpuSamples;

    private Distant(ICubeSource source) {
        this.source = source;
    }

    /** Starts distant terrain if a cube source exists. */
    static Distant start() {
        ICubeSource source = CubeSource.get();
        if (source == null) {
            Quadrature.LOGGER.error("Distant terrain was requested, but no cube source is installed.");
            return null;
        }
        Quadrature.LOGGER.info("Distant terrain is on: {} levels, ring 0 reaches {} blocks, {} worker threads of "
            + "its own (a stand-in for Cubature's client executor), world bound {}.", LEVELS, REACH,
            DistantWorkers.THREADS, WORLD_HALF > 0 ? WORLD_HALF + " blocks from the origin" : "none");
        return new Distant(source);
    }

    /** One world pass: takes finished tiles, asks for more, and draws. */
    void frame(float partialTicks) {
        if (failed) {
            return;
        }
        try {
            long started = System.nanoTime();
            run(partialTicks);
            cpuNanos += System.nanoTime() - started;
            cpuSamples++;
        } catch (RuntimeException failure) {
            // A shader that does not compile, a driver refusal: distant terrain stops, the game goes on.
            failed = true;
            Quadrature.LOGGER.error("Distant terrain failed and is off for the rest of this run.", failure);
        }
    }

    private void run(float partialTicks) {
        frame++;
        DriverMessageAccess.install();
        if (!follow()) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        Entity viewer = minecraft.getRenderViewEntity();
        if (viewer == null || minecraft.world == null) {
            return;
        }
        fillView(viewer, partialTicks, minecraft);
        arena.beginFrame();
        takeEnded();
        takeBuilt();
        select();
        release();
        while (true) {
            long key = schedule.next(DistantWorkers.THREADS * 2);
            if (key == -1) {
                break;
            }
            inFlight.add(key);
            workers.submit(preview, key, schedule.generation());
        }
        materials.refresh();
        list();
        if (draws.commandCount() > 0) {
            DriverMessageAccess.enter("distant terrain");
            int timer = (int) (frame % TIMERS);
            readTimer(timer);
            GL33.glQueryCounter(timerStart[timer], GL33.GL_TIMESTAMP);
            lastDraws = program.draw(draws, arena, materials, modelView, projection, nearBox,
                minecraft.world.getSunBrightness(partialTicks));
            GL33.glQueryCounter(timerEnd[timer], GL33.GL_TIMESTAMP);
            DriverMessageAccess.leave();
        } else {
            lastDraws = 0;
        }
        lastFaces = draws.faces();
        arena.endFrame(frame);
        if (LOG && System.nanoTime() - lastLogNanos > LOG_INTERVAL_NANOS) {
            lastLogNanos = System.nanoTime();
            Quadrature.LOGGER.info(summary());
        }
    }

    /**
     * Keeps to the attached world and its terrain preview. A new world, or a preview that came or
     * went, lets every tile go and refuses whatever is still being built.
     *
     * @return whether there is a preview to sample
     */
    private boolean follow() {
        ICubicTerrainPreview now = source.attached() ? source.terrainPreview() : null;
        long identity = source.attached() ? source.worldIdentity() : -1;
        if (now == preview && identity == worldIdentity) {
            return preview != null;
        }
        forget();
        worldIdentity = identity;
        preview = now;
        if (now == null) {
            return false;
        }
        int floor = source.minCubeY() * 16;
        int ceiling = source.maxCubeY() * 16;
        if (ceiling - floor > FaceRecord.MAXIMUM_HEIGHT) {
            Quadrature.LOGGER.error("Distant terrain: the world is {} blocks tall and a tile holds {}; it stays off "
                + "in this world.", ceiling - floor, FaceRecord.MAXIMUM_HEIGHT);
            preview = null;
            return false;
        }
        if (workers == null || floor != floorY || ceiling != ceilingY) {
            floorY = floor;
            ceilingY = ceiling;
            // Threads of an earlier pool finish what they hold and then idle; their results are refused.
            workers = new DistantWorkers(materials, floor, ceiling);
        }
        Quadrature.LOGGER.info("Distant terrain: sampling the terrain preview of world {}, levels {} to {}.",
            identity, floor, ceiling);
        return true;
    }

    private void forget() {
        for (LongIterator it = inFlight.iterator(); it.hasNext();) {
            if (workers != null) {
                workers.abandon(it.nextLong());
            } else {
                it.nextLong();
            }
        }
        inFlight.clear();
        schedule.reset();
        release();
        selectedX = Long.MIN_VALUE;
        selectedZ = Long.MIN_VALUE;
    }

    private void fillView(Entity viewer, float partialTicks, Minecraft minecraft) {
        // As near terrain takes it: the world is drawn from the viewer's interpolated position, and
        // the matrices are the ones the driver holds for the world.
        double x = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * partialTicks;
        double y = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * partialTicks;
        double z = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * partialTicks;
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, modelView);
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projection);
        boolean reversed = DepthCalls.active();
        if (!reversed && !depthWarned) {
            depthWarned = true;
            Quadrature.LOGGER.warn("Distant terrain is drawn without reversed depth: an integer depth buffer cannot "
                + "tell distant faces apart, and they will flicker. Set -Dquadrature.reversedDepth=true.");
        }
        view.set(View.Purpose.MAIN, reversed ? View.Depth.REVERSED : View.Depth.CONVENTIONAL, x, y, z, x,
            y + viewer.getEyeHeight(), z, modelView, projection, minecraft.gameSettings.renderDistanceChunks,
            CubicChunksConfig.verticalCubeLoadDistance, true);
    }

    /** Tasks that ended without a tile: abandoned, withdrawn or failed. */
    private void takeEnded() {
        for (DistantWorkers.Ended end = workers.takeEnded(); end != null; end = workers.takeEnded()) {
            if (end.generation == schedule.generation()) {
                inFlight.remove(end.key);
            }
            workers.settled(end.key);
            // An abandoned tile that is wanted again by now goes back in the queue; a failed one does not.
            schedule.failed(end.key, end.generation, end.withdrawn);
            if (end.withdrawn) {
                tilesAbandoned++;
            } else {
                tilesFailed++;
            }
        }
    }

    /** Stores finished tiles, until the frame's time for it is spent. */
    private void takeBuilt() {
        long started = System.nanoTime();
        long spent = 0;
        while (spent < PLACE_BUDGET_NANOS) {
            BuiltTile tile = workers.takeBuilt();
            if (tile == null) {
                break;
            }
            long key = tile.key();
            if (tile.generation() == schedule.generation()) {
                inFlight.remove(key);
            }
            workers.settled(key);
            tilesBuilt++;
            sampleNanos += tile.sampleNanos();
            meshNanos += tile.meshNanos();
            if (schedule.built(key, tile.generation())) {
                long block = arena.place(tile);
                if (block < 0) {
                    // The arena is full: the tile is left out until it leaves the rings and returns.
                    schedule.failed(key, tile.generation(), false);
                    tilesRefused++;
                } else {
                    Resident resident = new Resident();
                    resident.block = block;
                    resident.bytes = tile.bytes();
                    resident.page = DistantArena.page(block);
                    resident.firstFace = (int) (arena.offset(block) / FaceRecord.BYTES);
                    resident.tintWord = (int) ((arena.offset(block) + (long) tile.faces() * FaceRecord.BYTES) / 4);
                    resident.faces = tile.faces();
                    resident.minimumY = tile.minimumY();
                    resident.maximumY = tile.maximumY();
                    for (int group = 0; group < TileMesh.GROUPS; group++) {
                        resident.groupOffset[group] = tile.groupOffset(group);
                        resident.groupCount[group] = tile.groupCount(group);
                    }
                    residents.put(key, resident);
                    schedule.placed(key);
                    tilesPlaced++;
                }
            } else {
                tilesAbandoned++;
            }
            workers.recycle(tile);
            spent = System.nanoTime() - started;
        }
        placeNanos += spent;
        mostPlaceNanos = Math.max(mostPlaceNanos, spent);
    }

    /** Selects the rings again once the camera has moved far enough, and abandons what left them. */
    private void select() {
        long x = Math.floorDiv((long) Math.floor(view.originX()), RESELECT_BLOCKS);
        long z = Math.floorDiv((long) Math.floor(view.originZ()), RESELECT_BLOCKS);
        if (x == selectedX && z == selectedZ) {
            return;
        }
        selectedX = x;
        selectedZ = z;
        long cameraX = (long) Math.floor(view.originX());
        long cameraZ = (long) Math.floor(view.originZ());
        if (WORLD_HALF > 0) {
            rings.select(cameraX, cameraZ, -WORLD_HALF, -WORLD_HALF, WORLD_HALF, WORLD_HALF);
        } else {
            rings.select(cameraX, cameraZ);
        }
        schedule.want(rings);
        for (LongIterator it = inFlight.iterator(); it.hasNext();) {
            long key = it.nextLong();
            if (!schedule.wanted(key)) {
                workers.abandon(key);
            }
        }
    }

    /** Gives back the arena blocks of tiles that left every ring and whose ground is covered again. */
    private void release() {
        ArrayList<Long> released = schedule.takeReleased();
        for (int i = 0; i < released.size(); i++) {
            Resident resident = residents.remove(released.get(i).longValue());
            if (resident != null) {
                arena.free(resident.block, resident.bytes, frame);
                tilesEvicted++;
            }
        }
        released.clear();
    }

    /** Writes the frame's records and commands: the stored tiles the view can see, less what is near. */
    private void list() {
        // Near terrain meshes a cube only among loaded neighbors, so the outermost loaded cubes
        // stay unmeshed: the box near terrain covers is the reach less one cube on every side.
        int across = Math.max(0, view.across() - 1);
        int upward = Math.max(0, view.upward() - 1);
        long boxMinX = (view.originCubeX() - across) * 16L;
        long boxMaxX = (view.originCubeX() + across + 1) * 16L;
        long boxMinY = (view.originCubeY() - upward) * 16L;
        long boxMaxY = (view.originCubeY() + upward + 1) * 16L;
        long boxMinZ = (view.originCubeZ() - across) * 16L;
        long boxMaxZ = (view.originCubeZ() + across + 1) * 16L;
        nearBox[0] = (float) (boxMinX - view.originX());
        nearBox[1] = (float) (boxMinY - view.originY());
        nearBox[2] = (float) (boxMinZ - view.originZ());
        nearBox[3] = (float) (boxMaxX - view.originX());
        nearBox[4] = (float) (boxMaxY - view.originY());
        nearBox[5] = (float) (boxMaxZ - view.originZ());
        double eyeX = view.originX();
        double eyeY = view.originY() + Minecraft.getMinecraft().getRenderViewEntity().getEyeHeight();
        double eyeZ = view.originZ();
        draws.begin(arena.pages());
        int drawn = 0;
        for (Long2ObjectOpenHashMap.Entry<Resident> entry : residents.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            Resident tile = entry.getValue();
            if (tile.faces == 0 || !schedule.drawn(key)) {
                continue;
            }
            if (TileCulling.inside(key, tile.minimumY, tile.maximumY, boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY,
                boxMaxZ)) {
                continue;
            }
            long side = TileKey.side(TileKey.level(key));
            long originX = TileKey.originX(key);
            long originZ = TileKey.originZ(key);
            if (!view.boxVisible(originX, tile.minimumY, originZ, originX + side, tile.maximumY, originZ + side)) {
                continue;
            }
            int facing = TileCulling.facing(key, tile.minimumY, tile.maximumY, eyeX, eyeY, eyeZ);
            int record = draws.record(originX - view.originX(), floorY - view.originY(), originZ - view.originZ(),
                TileKey.level(key), tile.tintWord, 1f);
            for (int layer = 0; layer < TileMesh.LAYERS; layer++) {
                for (int direction = 0; direction < FaceRecord.DIRECTIONS; direction++) {
                    if ((facing & 1 << direction) != 0) {
                        int group = TileMesh.group(layer, direction);
                        draws.add(TileDraws.drawLayer(layer), tile.page, tile.firstFace + tile.groupOffset[group],
                            tile.groupCount[group], record);
                    }
                }
            }
            drawn++;
        }
        draws.finish();
        lastTilesDrawn = drawn;
    }

    private void readTimer(int timer) {
        if (!timersMade) {
            timersMade = true;
            for (int i = 0; i < TIMERS; i++) {
                timerStart[i] = GL15.glGenQueries();
                timerEnd[i] = GL15.glGenQueries();
                // A query must be issued once before its result can be asked for.
                GL33.glQueryCounter(timerStart[i], GL33.GL_TIMESTAMP);
                GL33.glQueryCounter(timerEnd[i], GL33.GL_TIMESTAMP);
            }
            return;
        }
        // The pair about to be reused was issued eight frames ago; its result is ready or is dropped.
        if (GL15.glGetQueryObjecti(timerEnd[timer], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            long nanos = GL33.glGetQueryObjecti64(timerEnd[timer], GL15.GL_QUERY_RESULT)
                - GL33.glGetQueryObjecti64(timerStart[timer], GL15.GL_QUERY_RESULT);
            if (nanos >= 0) {
                gpuNanos += nanos;
                gpuSamples++;
                mostGpuNanos = Math.max(mostGpuNanos, nanos);
            }
        }
    }

    /** The timing log's lines for distant terrain. */
    private String summary() {
        StringBuilder line = new StringBuilder("Distant terrain: tiles wanted/stored by level");
        int wanted = 0;
        int stored = 0;
        for (int level = 0; level < LEVELS; level++) {
            schedule.count(level, levelCount);
            wanted += levelCount[0];
            stored += levelCount[1];
            line.append(String.format(" L%d %d/%d", level, levelCount[0], levelCount[1]));
        }
        line.append(String.format("; all %d/%d, being built %d, kept past the rings %d, resident %d", wanted, stored,
            schedule.building(), schedule.leaving(), residents.size()));
        line.append(String.format("%n  drawn: %d tiles, %d faces, %d draws; GPU %.3f ms mean, %.3f most (%d samples, "
                + "its own timer; the stage times count it under entities); client thread %.3f ms mean",
            lastTilesDrawn, lastFaces, lastDraws,
            gpuSamples == 0 ? 0.0 : gpuNanos / 1e6 / gpuSamples, mostGpuNanos / 1e6, gpuSamples,
            cpuSamples == 0 ? 0.0 : cpuNanos / 1e6 / cpuSamples));
        line.append(String.format("%n  built %d (sample %.1f ms, mesh %.2f ms a tile), stored %d, abandoned %d, "
                + "failed %d, refused for room %d, let go %d; storing %.3f ms a frame at most", tilesBuilt,
            tilesBuilt == 0 ? 0.0 : sampleNanos / 1e6 / tilesBuilt, tilesBuilt == 0 ? 0.0 : meshNanos / 1e6 / tilesBuilt,
            tilesPlaced, tilesAbandoned, tilesFailed, tilesRefused, tilesEvicted, mostPlaceNanos / 1e6));
        line.append(String.format("%n  arena: %d pages, %.1f MiB used of %.0f, %.1f MiB waiting on a fence; "
                + "materials %d", arena.pages(), arena.usedBytes() / 1048576.0, arena.capacityBytes() / 1048576.0,
            arena.pendingBytes() / 1048576.0, materials.size()));
        gpuNanos = 0;
        gpuSamples = 0;
        mostGpuNanos = 0;
        cpuNanos = 0;
        cpuSamples = 0;
        mostPlaceNanos = 0;
        return line.toString();
    }
}
