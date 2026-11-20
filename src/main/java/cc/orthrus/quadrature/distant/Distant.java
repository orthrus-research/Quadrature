package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.lod.BuiltTile;
import cc.orthrus.quadrature.core.lod.Cover;
import cc.orthrus.quadrature.core.view.ShadedGate;
import cc.orthrus.quadrature.core.view.StateGuard;
import cc.orthrus.quadrature.core.view.ShadingTargets;
import cc.orthrus.quadrature.core.lod.FaceRecord;
import cc.orthrus.quadrature.core.lod.FarEdge;
import cc.orthrus.quadrature.core.lod.NearCover;
import cc.orthrus.quadrature.core.lod.Travel;
import cc.orthrus.quadrature.core.lod.Rings;
import cc.orthrus.quadrature.core.lod.TileCulling;
import cc.orthrus.quadrature.core.lod.TileDraws;
import cc.orthrus.quadrature.core.lod.TileKey;
import cc.orthrus.quadrature.core.lod.TileMesh;
import cc.orthrus.quadrature.core.lod.TileSchedule;
import cc.orthrus.quadrature.core.terrain.CoverageMap;
import cc.orthrus.quadrature.core.view.View;
import cc.orthrus.quadrature.depth.DepthCalls;
import cc.orthrus.quadrature.terrain.DriverMessageAccess;
import cc.orthrus.quadrature.terrain.TerrainHook;

import io.github.opencubicchunks.cubicchunks.api.client.CubeSource;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import io.github.opencubicchunks.cubicchunks.api.worldgen.ICubicTerrainPreview;
import io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
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

    private static final int REACH = DistantSettings.REACH;
    private static final int LEVELS = DistantSettings.LEVELS;
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
        /** The frame it was stored in, from which it fades in. */
        long placedFrame;
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
    /** The pass into near terrain's targets, as one piece, and what it leaves as found. */
    private final Runnable targetWriter = this::writeTargets;
    private final StateGuard writerGuard = new StateGuard(WriterState.all());
    private final WriterState writerState = new WriterState(DistantProgram.COVER_UNIT, DistantProgram.IMAGES_UNIT);
    private final WriterAudit writerAudit = WriterAudit.ENABLED ? new WriterAudit() : null;
    private float writerPartialTicks;
    /** Passes that listed, those in which near terrain's band was read and its origin, and the most the origin was apart. */
    private long joinPasses;
    private long joinRead;
    private long joinOrigins;
    private double joinApart;
    private boolean writerFading;
    private boolean wrote;
    private final long[] boxBlocks = new long[6];
    private final DistantCover cover = new DistantCover();
    private final TravelCapture travel = new TravelCapture();
    /** Which stored tile draws what while tiles arrive: see {@link Cover}. */
    private final Cover arrival = new Cover(rings, new Cover.Tiles() {
        @Override
        public boolean wanted(long key) {
            return schedule.wanted(key);
        }

        @Override
        public boolean shown(long key) {
            return Distant.this.shown(key);
        }

        @Override
        public boolean kept(long key) {
            return schedule.kept(key) && residents.containsKey(key);
        }
    });
    private final LongArrayList spared = new LongArrayList();
    private int lastShadedDraws;
    /** The band the join fades over this frame, start then end, then where it is measured from, or null. */
    private float[] joinBand;
    private final float[] joinValues = new float[4];
    private double joinX;
    private double joinZ;
    /** For the draw: a bit for each tile of each level's square that is shown, and where the squares lie. */
    private final int[] shownMasks = new int[8 * Cover.maskWords(2 * REACH / 64)];
    private final float[] squareOrigins = new float[8 * 2];
    private long tilesSpared;
    /** Near terrain's coverage map this frame, or null when the box is used in its place. */
    private CoverageMap covered;
    private float[] imageFade = {DistantSettings.DETAIL_START, DistantSettings.DETAIL_END};
    /** The camera the rings were last selected for: the holes lie where that selection put them. */
    private long ringsCameraX;
    private long ringsCameraZ;
    /** How far along an axis the rings last selected are drawn to: published through the hook. */
    private long drawnTo;
    private final float[] holes = new float[8 * 4];
    private final float[] bandWidths = new float[8];
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
    /** The faces each timer's frame drew, and their sum over the samples read, so a report's mean faces is that of the frames timed. */
    private final long[] timerFaces = new long[TIMERS];
    private long gpuFaces;
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
    /** Tiles the near box holds whole, drawn only for the box view. */
    private int lastTilesInBox;
    private long gpuNanos;
    private long gpuSamples;
    private long mostGpuNanos;
    private long cpuNanos;
    private long cpuSamples;

    private Distant(ICubeSource source) {
        this.source = source;
        rings.overlap(DistantSettings.CROSSFADE);
        rings.fill(DistantSettings.FILL);
        rings.nearFirst(DistantSettings.NEAR_FIRST);
        if (FrameTailLog.ENABLED) {
            MinecraftForge.EVENT_BUS.register(new FrameTailLog(() -> workers == null ? 0 : workers.running()));
        }
    }

    /** Starts distant terrain if a cube source exists. */
    static Distant start() {
        ICubeSource source = CubeSource.get();
        if (source == null) {
            Quadrature.LOGGER.error("Distant terrain was requested, but no cube source is installed.");
            return null;
        }
        Quadrature.LOGGER.info("Distant terrain is on: {} levels, ring 0 reaches {} blocks, world bound {}.", LEVELS,
            REACH, WORLD_HALF > 0 ? WORLD_HALF + " blocks from the origin" : "none");
        Quadrature.LOGGER.info("Distant terrain: fog {}; debug view {}.",
            DistantSettings.FOG ? "shared with near terrain, " + DistantSettings.FOG_START + " to "
                + DistantSettings.FOG_END + " blocks" : "the game's on near terrain, none on distant terrain",
            DistantSettings.DEBUG == DistantSettings.DEBUG_BOX ? "box (magenta where the near box holds ground and "
                + "near terrain drew nothing in front)"
                : DistantSettings.DEBUG == DistantSettings.DEBUG_LEVEL ? "level (a color a level, no fog)" : "off");
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
            DistantHook.sampling(false);
            // A shader that does not compile, a driver refusal: distant terrain stops, the game goes on.
            failed = true;
            Quadrature.LOGGER.error("Distant terrain failed and is off for the rest of this run.", failure);
        }
    }

    private void run(float partialTicks) {
        frame++;
        DriverMessageAccess.install();
        boolean following = follow();
        DistantHook.sampling(following);
        if (!following) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        Entity viewer = minecraft.getRenderViewEntity();
        if (viewer == null || minecraft.world == null) {
            return;
        }
        fillView(viewer, partialTicks, minecraft);
        boolean captured = travel.frame(viewer);
        arena.beginFrame();
        workers.drain();
        takeEnded();
        takeBuilt();
        select();
        if (travel.awaitsWhole() && whole(viewer)) {
            travel.whole();
            captured = true;
        }
        DistantHook.drawnTo(drawnTo);
        DistantHook.nearestEdge(nearestEdge());
        release();
        while (true) {
            // A fill tile whose ground finer tiles draw already is not built.
            long key = DistantSettings.FILL ? schedule.next(workers.threads() * 2, arrival::spare)
                : schedule.next(workers.threads() * 2);
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
            timerFaces[timer] = draws.faces();
            boolean fading = DistantSettings.CROSSFADE && DistantSettings.DEBUG != DistantSettings.DEBUG_BOX;
            if (fading) {
                bands();
            }
            squares();
            // On the shaded path the water waits until the targets are written and then draws
            // without writing depth. Off it, one draw holds all three layers as before.
            program.water(DistantSettings.SHADED ? DistantProgram.WATER_LEFT : DistantProgram.WATER_WITH);
            lastDraws = program.draw(draws, arena, materials, modelView, projection, nearBox,
                minecraft.world.getSunBrightness(partialTicks), cover,
                (float) (cover.firstBlockX() - view.originX()), (float) (cover.firstBlockZ() - view.originZ()),
                imageFade, fading ? holes : null, bandWidths, LEVELS, shownMasks, squareOrigins, rings.across(), joinBand);
            GL33.glQueryCounter(timerEnd[timer], GL33.GL_TIMESTAMP);
            if (DistantSettings.SHADED) {
                writerPartialTicks = partialTicks;
                writerFading = fading;
                targetWriter.run();
            }
            DistantHook.shaded(DistantSettings.SHADED && wrote);
            if (DistantSettings.SHADED) {
                program.water(DistantProgram.WATER_ALONE);
                lastDraws += program.draw(draws, arena, materials, modelView, projection, nearBox,
                    minecraft.world.getSunBrightness(partialTicks), cover,
                    (float) (cover.firstBlockX() - view.originX()), (float) (cover.firstBlockZ() - view.originZ()),
                    imageFade, fading ? holes : null, bandWidths, LEVELS, shownMasks, squareOrigins, rings.across(),
                    joinBand);
                program.water(DistantProgram.WATER_WITH);
            }
            if (captured && DistantSettings.SHADED_IMAGES && DistantHook.shaded()) {
                TargetImages.write(TerrainHook.shadingTargets(), travel.lastName());
            }
            DriverMessageAccess.leave();
        } else {
            lastDraws = 0;
        }
        lastFaces = draws.faces();
        arena.endFrame(frame);
        if (captured) {
            // Beside a travel capture: what was stored and covered when its frame was drawn.
            Quadrature.LOGGER.info(summary());
        }
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
            // Tasks of an earlier set finish what they hold; their results are refused.
            workers = new DistantWorkers(materials, floor, ceiling, source.previewWork());
            if (workers.governed()) {
                Quadrature.LOGGER.info("Distant terrain: tiles are built on Cubature's client preview pool, {} threads, "
                    + "{} at once to start.", workers.threads(), workers.running());
            } else {
                Quadrature.LOGGER.warn("Distant terrain: the cube source has no client preview pool; tiles are built on "
                    + "{} threads of distant terrain's own (-Dquadrature.distant.threads).", workers.threads());
            }
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
        travel.forget();
        joinPasses = 0;
        joinRead = 0;
        joinOrigins = 0;
        joinApart = 0;
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

    /**
     * Whether the viewer's place is whole, for the travel capture. Of the cube source this asks
     * {@code settled(20)}: no cube arrived, left or changed, the held box did not change and no
     * client light work was left queued for 20 client ticks, and the server has said it sent
     * every cube of the held box, where it states one. That speaks of cubes only, so the tiles
     * are counted here.
     */
    private boolean whole(Entity viewer) {
        int wanted = 0;
        int stored = 0;
        for (int level = 0; level < LEVELS; level++) {
            schedule.count(level, levelCount);
            wanted += levelCount[0];
            stored += levelCount[1];
        }
        return Travel.whole(source.settled(20), source.heldBox(), (int) Math.floor(viewer.posX / 16),
            (int) Math.floor(viewer.posY / 16), (int) Math.floor(viewer.posZ / 16), wanted, stored,
            schedule.building());
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
                    resident.placedFrame = frame;
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
        ringsCameraX = cameraX;
        ringsCameraZ = cameraZ;
        if (WORLD_HALF > 0) {
            rings.select(cameraX, cameraZ, -WORLD_HALF, -WORLD_HALF, WORLD_HALF, WORLD_HALF);
            drawnTo = rings.drawnTo(cameraX, cameraZ, -WORLD_HALF, -WORLD_HALF, WORLD_HALF, WORLD_HALF);
        } else {
            rings.select(cameraX, cameraZ);
            drawnTo = rings.drawnTo();
        }
        schedule.want(rings);
        for (LongIterator it = inFlight.iterator(); it.hasNext();) {
            long key = it.nextLong();
            if (!schedule.wanted(key)) {
                workers.abandon(key);
            }
        }
    }

    /** How far the nearest side of the outermost selected level's square is from the viewer ({@link FarEdge}). */
    private float nearestEdge() {
        int level = LEVELS - 1;
        if (WORLD_HALF > 0) {
            level = rings.coarsestNeeded(ringsCameraX, ringsCameraZ, -WORLD_HALF, -WORLD_HALF, WORLD_HALF, WORLD_HALF);
        }
        long side = TileKey.side(level);
        long minX = rings.squareX(level) * side;
        long minZ = rings.squareZ(level) * side;
        long maxX = minX + rings.across() * side;
        long maxZ = minZ + rings.across() * side;
        return WORLD_HALF > 0
            ? FarEdge.distance(view.originX(), view.originZ(), minX, minZ, maxX, maxZ, -WORLD_HALF, -WORLD_HALF,
                WORLD_HALF, WORLD_HALF)
            : FarEdge.distance(view.originX(), view.originZ(), minX, minZ, maxX, maxZ);
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
        // What near terrain covers. With its coverage map (-Dquadrature.coverage=true): the chunk
        // columns whose surface it has meshed, so ground it has not reached yet stays distant
        // terrain's and gives way column by column. Without: the box of cubes it meshes, which is
        // the cubes the server keeps sent less their outer shell, by the cube source's held box
        // or, from a server that states none, by the view distances. Either way only over the
        // heights its cubes span.
        CoverageMap map = TerrainHook.coverage();
        covered = cover.update(map) ? map : null;
        int firstCubeX = covered == null ? 0 : (int) (cover.firstBlockX() >> 4);
        int firstCubeZ = covered == null ? 0 : (int) (cover.firstBlockZ() >> 4);
        // Images fade outside what near terrain reaches, whatever the view distance.
        int loaded = NearCover.box(source.heldBox(), view.originCubeX(), view.originCubeY(), view.originCubeZ(),
            view.across(), view.upward(), boxBlocks);
        imageFade = NearCover.imageFade(DistantSettings.DETAIL_START, DistantSettings.DETAIL_END,
            (NearCover.meshedReach(loaded) + 1) * 16f, REACH);
        long boxMinX = boxBlocks[0];
        long boxMinY = boxBlocks[1];
        long boxMinZ = boxBlocks[2];
        long boxMaxX = boxBlocks[3];
        long boxMaxY = boxBlocks[4];
        long boxMaxZ = boxBlocks[5];
        nearBox[0] = (float) (boxMinX - view.originX());
        nearBox[1] = (float) (boxMinY - view.originY());
        nearBox[2] = (float) (boxMinZ - view.originZ());
        nearBox[3] = (float) (boxMaxX - view.originX());
        nearBox[4] = (float) (boxMaxY - view.originY());
        nearBox[5] = (float) (boxMaxZ - view.originZ());
        double eyeX = view.originX();
        double eyeY = view.originY() + Minecraft.getMinecraft().getRenderViewEntity().getEyeHeight();
        double eyeZ = view.originZ();
        float[] band = DistantHook.joinBand();
        joinPasses++;
        if (band == null) {
            joinBand = null;
        } else {
            joinRead++;
            // The band, and where its distance is measured from, from the view's origin.
            double[] origin = DistantHook.joinOrigin();
            joinX = origin == null ? view.originX() : origin[0];
            joinZ = origin == null ? view.originZ() : origin[2];
            joinValues[0] = band[0];
            joinValues[1] = band[1];
            joinValues[2] = (float) (joinX - view.originX());
            joinValues[3] = (float) (joinZ - view.originZ());
            if (origin != null) {
                joinOrigins++;
                joinApart = Math.max(joinApart, Math.max(Math.abs(joinX - view.originX()), Math.abs(joinZ - view.originZ())));
            }
            joinBand = joinValues;
        }
        draws.begin(arena.pages());
        int drawn = 0;
        int inBox = 0;
        for (Long2ObjectOpenHashMap.Entry<Resident> entry : residents.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            Resident tile = entry.getValue();
            if (tile.faces == 0 || !schedule.drawn(key)) {
                continue;
            }
            // Of two tiles kept past their ring over one place, the coarser draws.
            if (DistantSettings.FILL && schedule.kept(key) && arrival.keptHidden(key)) {
                continue;
            }
            // A fill tile all of whose ground finer tiles draw is of no use: it is let go below.
            if (DistantSettings.FILL && !schedule.kept(key) && arrival.spare(key)) {
                spared.add(key);
                continue;
            }
            long tileSide = TileKey.side(TileKey.level(key));
            boolean hidden = covered == null
                ? TileCulling.inside(key, tile.minimumY, tile.maximumY, boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY,
                    boxMaxZ)
                : tile.minimumY >= boxMinY && tile.maximumY <= boxMaxY && NearCover.covered(covered.states(),
                    covered.side(), firstCubeX, firstCubeZ, TileKey.originX(key), TileKey.originZ(key),
                    TileKey.originX(key) + tileSide, TileKey.originZ(key) + tileSide);
            // With the join's fade, a tile near terrain covers is still drawn where it reaches the
            // band: there it shows in the pixels near terrain gives up.
            if (hidden && joinBand != null && !NearCover.beforeJoin(TileKey.originX(key), TileKey.originZ(key),
                    TileKey.originX(key) + tileSide, TileKey.originZ(key) + tileSide, joinX, joinZ,
                    joinBand[0])) {
                hidden = false;
            }
            if (hidden) {
                // The box view draws the tiles the box holds too: their faces are what it shows.
                if (DistantSettings.DEBUG != DistantSettings.DEBUG_BOX) {
                    continue;
                }
                inBox++;
            }
            long side = TileKey.side(TileKey.level(key));
            long originX = TileKey.originX(key);
            long originZ = TileKey.originZ(key);
            if (!view.boxVisible(originX, tile.minimumY, originZ, originX + side, tile.maximumY, originZ + side)) {
                continue;
            }
            int facing = TileCulling.facing(key, tile.minimumY, tile.maximumY, eyeX, eyeY, eyeZ);
            // A tile kept past its ring is drawn whole; one just stored comes in over a few frames.
            float shows = schedule.kept(key) ? -1f : !DistantSettings.CROSSFADE ? 1f
                : Math.min(1f, (frame - tile.placedFrame + 1) / (float) DistantSettings.ARRIVAL_FRAMES);
            int record = draws.record(originX - view.originX(), floorY - view.originY(), originZ - view.originZ(),
                TileKey.level(key), tile.tintWord, shows, 0);
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
        // Fill tiles that have given all their ground up are let go: their storage is free again.
        for (int i = 0; i < spared.size(); i++) {
            long key = spared.getLong(i);
            Resident resident = residents.remove(key);
            if (resident != null) {
                schedule.retire(key);
                arena.free(resident.block, resident.bytes, frame);
                tilesSpared++;
            }
        }
        spared.clear();
        lastTilesDrawn = drawn;
        lastTilesInBox = inBox;
    }

    /**
     * Draws the opaque layers into near terrain's targets for shading, where it made them in this
     * world pass: after its last cutout layer and before anything reads them. One piece, to be
     * handed to near terrain as a writer of its targets: it takes nothing but what the frame's
     * list already holds, binds the targets itself, sets every switch it depends on (the depth
     * test nearer-or-equal with depth written, no alpha test, no blending; the draw turns
     * culling off), and leaves every binding and switch it touched as it found them
     * ({@link WriterState}). Nothing is cleared. {@link #wrote} says afterwards whether it drew.
     */
    private void writeTargets() {
        wrote = false;
        ShadingTargets targets = TerrainHook.shadingTargets();
        long pass = TerrainHook.frame();
        if (!ShadedGate.draws(targets, pass)) {
            // No targets, or they are not this world pass's: nothing to add to.
            return;
        }
        // Near terrain's material table, brought up to date with the rows distant terrain's
        // materials asked for; 0 where own terrain is off or the targets are not drawn.
        int table = TerrainHook.materialsBuffer();
        Minecraft minecraft = Minecraft.getMinecraft();
        if (table == 0 || minecraft.world == null || draws.commandCount() == 0) {
            return;
        }
        boolean audited = writerAudit != null && writerAudit.due();
        if (audited) {
            writerAudit.before();
        }
        writerGuard.save(writerState);
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, targets.framebuffer());
            GL11.glViewport(0, 0, targets.width(), targets.height());
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(DepthCalls.active() ? GL11.GL_GEQUAL : GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            lastShadedDraws = program.drawTargets(draws, arena, materials, modelView, projection, nearBox,
                minecraft.world.getSunBrightness(writerPartialTicks), cover,
                (float) (cover.firstBlockX() - view.originX()), (float) (cover.firstBlockZ() - view.originZ()),
                imageFade, writerFading ? holes : null, bandWidths, LEVELS, shownMasks, squareOrigins, rings.across(),
                joinBand, table, (int) (pass & 0x7FFFFFFF));
            wrote = true;
        } finally {
            writerGuard.restore(writerState);
        }
        if (audited) {
            writerAudit.after();
        }
    }

    /** Which tiles of each level's square are shown this frame, and where each square begins. */
    private void squares() {
        arrival.masks(shownMasks);
        for (int level = 0; level < LEVELS && level < 8; level++) {
            long side = TileKey.side(level);
            squareOrigins[level * 2] = (float) (rings.squareX(level) * side - view.originX());
            squareOrigins[level * 2 + 1] = (float) (rings.squareZ(level) * side - view.originZ());
        }
    }

    /** Where each level's hole lies this frame, as the last selection put it, and its band's width. */
    private void bands() {
        for (int level = 1; level < LEVELS && level < 8; level++) {
            long startX = rings.holeStart(level, ringsCameraX);
            long startZ = rings.holeStart(level, ringsCameraZ);
            long side = rings.holeSide(level);
            holes[level * 4] = (float) (startX - view.originX());
            holes[level * 4 + 1] = (float) (startZ - view.originZ());
            holes[level * 4 + 2] = (float) (startX + side - view.originX());
            holes[level * 4 + 3] = (float) (startZ + side - view.originZ());
            bandWidths[level] = TileKey.side(level);
        }
    }

    /** Whether a tile is stored, wanted by the present rings, and past its fading in. */
    private boolean shown(long key) {
        Resident tile = residents.get(key);
        return tile != null && schedule.drawn(key) && !schedule.kept(key)
            && frame - tile.placedFrame + 1 >= DistantSettings.ARRIVAL_FRAMES;
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
                gpuFaces += timerFaces[timer];
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
                + "its own timer; the stage times count it under entities; %d faces mean over the frames timed); client "
                + "thread %.3f ms mean",
            lastTilesDrawn, lastFaces, lastDraws,
            gpuSamples == 0 ? 0.0 : gpuNanos / 1e6 / gpuSamples, mostGpuNanos / 1e6, gpuSamples,
            gpuSamples == 0 ? 0 : gpuFaces / gpuSamples,
            cpuSamples == 0 ? 0.0 : cpuNanos / 1e6 / cpuSamples));
        line.append(!DistantSettings.SHADED ? String.format("%n  shaded path: off")
            : String.format("%n  shaded path: on; opaque layers %s near terrain's targets, %d draws",
                DistantHook.shaded() ? "drawn into" : "not drawn into (none made this frame)", lastShadedDraws));
        line.append(joinBand == null ? String.format("%n  join fade: off")
            : String.format("%n  join fade: distant terrain fades in from %.3f to %.3f blocks over near terrain's columns%s; "
                    + "measured from %.4f %.4f, which is %+.4f %+.4f from this view's origin %.4f %.4f%s",
                joinBand[0], joinBand[1], DistantSettings.JOIN_FADE_DEBUG != null ? " (debug band, whatever near terrain does)" : "",
                joinX, joinZ, joinX - view.originX(), joinZ - view.originZ(), view.originX(), view.originZ(),
                DistantHook.joinOrigin() == null ? " (near terrain gave no origin: this view's is used)" : ""));
        if (DistantSettings.JOIN_FADE) {
            line.append(String.format("%n  join fade: near terrain's band was read in %d of %d passes since the world was "
                    + "joined and its origin in %d; the origin was at most %.4f blocks from this view's along x or z",
                joinRead, joinPasses, joinOrigins, joinApart));
        }
        line.append(DistantSettings.FILL
            ? String.format("%n  fill tiles: on, each level's hole is drawn from that level until finer tiles are stored; "
                + "%d let go once covered; order %s", tilesSpared,
                DistantSettings.NEAR_FIRST ? "near first (the coarsest fill tiles, then from the finest level)"
                    : "coarsest level first")
            : String.format("%n  fill tiles: off"));
        line.append(DistantSettings.CROSSFADE
            ? String.format("%n  crossfade between levels: on, a band one coarser tile wide; tiles fade in over %d frames",
                DistantSettings.ARRIVAL_FRAMES)
            : String.format("%n  crossfade between levels: off"));
        line.append(covered == null
            ? String.format("%n  near terrain's cover: the box of cubes it meshes (no coverage map)")
            : String.format("%n  near terrain's cover: its coverage map, %d of %d columns meshed, %d waiting, %d with no "
                + "surface, %d not loaded; images fade from %.0f to %.0f blocks", covered.count(CoverageMap.SURFACE_MESHED),
                covered.side() * covered.side(), covered.count(CoverageMap.SURFACE_WAITING),
                covered.count(CoverageMap.NO_SURFACE), covered.count(CoverageMap.NOT_LOADED), imageFade[0],
                imageFade[1]));
        if (DistantSettings.DEBUG == DistantSettings.DEBUG_BOX) {
            line.append(String.format("%n  box view: %d of the tiles drawn lie wholly in the near box and are drawn "
                + "for this view only; every layer is drawn twice", lastTilesInBox));
        }
        line.append(String.format("%n  %s: %d threads, %d at once", workers.governed() ? "Cubature's preview pool"
            : "own threads", workers.threads(), workers.running()));
        line.append(String.format("%n  built %d (sample %.1f ms, mesh %.2f ms a tile), stored %d, abandoned %d, "
                + "failed %d, refused for room %d, let go %d; storing %.3f ms a frame at most", tilesBuilt,
            tilesBuilt == 0 ? 0.0 : sampleNanos / 1e6 / tilesBuilt, tilesBuilt == 0 ? 0.0 : meshNanos / 1e6 / tilesBuilt,
            tilesPlaced, tilesAbandoned, tilesFailed, tilesRefused, tilesEvicted, mostPlaceNanos / 1e6));
        line.append(String.format("%n  arena: %d pages, %.1f MiB used of %.0f, %.1f MiB waiting on a fence; "
                + "materials %d", arena.pages(), arena.usedBytes() / 1048576.0, arena.capacityBytes() / 1048576.0,
            arena.pendingBytes() / 1048576.0, materials.size()));
        gpuNanos = 0;
        gpuSamples = 0;
        gpuFaces = 0;
        mostGpuNanos = 0;
        cpuNanos = 0;
        cpuSamples = 0;
        mostPlaceNanos = 0;
        return line.toString();
    }
}
