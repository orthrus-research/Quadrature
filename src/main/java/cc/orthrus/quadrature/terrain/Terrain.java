package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.terrain.ArenaAllocator;
import cc.orthrus.quadrature.core.terrain.CubeConnectivity;
import cc.orthrus.quadrature.core.terrain.CubeKey;
import cc.orthrus.quadrature.core.terrain.CubeList;
import cc.orthrus.quadrature.core.terrain.CubeMatrix;
import cc.orthrus.quadrature.core.terrain.CubeSlots;
import cc.orthrus.quadrature.core.terrain.CubeVisitor;
import cc.orthrus.quadrature.core.terrain.DrawCommands;
import cc.orthrus.quadrature.core.terrain.Visibility;
import cc.orthrus.quadrature.core.scene.LightScene;
import cc.orthrus.quadrature.core.terrain.CoverageMap;
import cc.orthrus.quadrature.core.terrain.FaceRecord;
import cc.orthrus.quadrature.core.terrain.SearchEdge;
import cc.orthrus.quadrature.core.terrain.SurfaceBand;
import cc.orthrus.quadrature.core.scene.PublishedScene;
import cc.orthrus.quadrature.core.view.LightBox;
import cc.orthrus.quadrature.core.view.LightLayerPlan;
import cc.orthrus.quadrature.core.view.LightTiles;
import cc.orthrus.quadrature.core.view.ShadingTargets;
import cc.orthrus.quadrature.core.view.ShadowLayers;
import cc.orthrus.quadrature.core.view.ShadowMaps;
import cc.orthrus.quadrature.core.view.View;

import io.github.opencubicchunks.cubicchunks.api.client.CubeSource;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSourceListener;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.BitSet;

import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;

/**
 * Near terrain, first form: every loaded cube is meshed from a snapshot of the cube source with the
 * game's own block models, on the client thread within a time budget, nearest first, and the three
 * opaque layers are drawn from those meshes in place of the game's draw. Translucent terrain and
 * block entities are still the game's.
 *
 * <p>What is drawn is what a view sees. Each frame the player's view is taken from the game and the
 * cubes it sees are found once, by a search outward from the eye through the faces of cubes that see
 * each other; the three layers draw from that list.
 *
 * <p>Meshes lie in an arena of pages shared by the three layers, and a layer is drawn with one
 * indirect draw for each page, from commands written each frame for each view.
 *
 * <p>Off unless {@code -Dquadrature.terrain=own}. Everything here runs on the client thread.
 */
public final class Terrain
    implements ICubeSourceListener, TerrainHook.Drawer, Visibility.Cubes, Visibility.CubeSet {

    private static final long BUILD_BUDGET_NANOS = 4_000_000L;
    /** The client thread's time for placing meshes in a frame; meshing stops for the frame once it is spent. */
    private static final long PLACE_BUDGET_NANOS = 1_000_000L;
    private static final int CANDIDATES = 24;
    private static final double NEAR_BLOCKS_SQUARED = 576.0;
    /**
     * A diagnostic, off unless {@code -Dquadrature.terrain.visibility=all}: every meshed cube in the
     * view's volume and reach is drawn, with no search, to set beside the search's frame and cost.
     */
    private static final boolean DRAW_ALL = "all".equals(System.getProperty("quadrature.terrain.visibility"));
    /**
     * A diagnostic, off unless {@code -Dquadrature.terrain.storage=buffers}: every mesh keeps buffers of
     * its own, as before the arena existed, to set beside the arena's frame, memory and cost.
     */
    private static final boolean OWN_BUFFERS = "buffers".equals(System.getProperty("quadrature.terrain.storage"));
    /**
     * A diagnostic, off unless {@code -Dquadrature.terrain.draw=direct}: one draw for each cube and
     * layer, its matrix in a uniform, to set beside indirect drawing. Meshes with buffers of their own
     * can be drawn no other way.
     */
    private static final boolean DIRECT = OWN_BUFFERS || "direct".equals(System.getProperty("quadrature.terrain.draw"));
    /**
     * Off unless {@code -Dquadrature.shadow=true}: the view along the light is drawn, for depth alone,
     * into a target of Quadrature's own. Nothing samples it yet, so the player's image is the same with
     * it on. It needs indirect draws.
     */
    private static final boolean SHADOW = Boolean.getBoolean("quadrature.shadow") && !DIRECT;
    /**
     * A diagnostic, off unless {@code -Dquadrature.shadow.image=true}: once for each place the player
     * stands still at, with nothing waiting to be meshed, the shadow depth is written as an image.
     */
    private static final boolean SHADOW_IMAGE = SHADOW && Boolean.getBoolean("quadrature.shadow.image");
    /**
     * A diagnostic, off unless {@code -Dquadrature.shadow.check=true}: every {@value #SHADOW_CHECK_FRAMES}
     * frames, for each layer with nothing due, the front's whole box is drawn into the scratch in one go
     * and set beside the layer drawn tile by tile; the texels that differ are logged.
     */
    private static final boolean SHADOW_CHECK = SHADOW && Boolean.getBoolean("quadrature.shadow.check");
    private static final int SHADOW_CHECK_FRAMES = 600;
    private long shadowChecks;
    private long shadowCheckFailures;
    private static final int STILL_FRAMES = 960;

    private final ICubeSource source;
    private final Long2ObjectOpenHashMap<CubeMesh> meshes = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    /**
     * Off unless {@code -Dquadrature.scene=true}: the cells of every cube that is meshed are kept for
     * lighting, with what each block state does to light.
     */
    private static final boolean SCENE = Boolean.getBoolean("quadrature.scene");
    private final LightScene scene = SCENE ? new LightScene() : null;
    /**
     * Off unless {@code -Dquadrature.coverage=true}: for every chunk column of the box, whether its
     * surface is loaded and meshed, worked out when what is held changes and no oftener than every
     * twenty frames.
     */
    private static final boolean COVERAGE = Boolean.getBoolean("quadrature.coverage");
    /**
     * A diagnostic, off unless {@code -Dquadrature.faces.count=true}: counts over every cube meshed the
     * quads that a record for a whole face could hold.
     */
    private static final boolean FACE_CENSUS = Boolean.getBoolean("quadrature.faces.count");
    private final long[] faceCensus = new long[3];
    private static final int COVERAGE_FRAMES = 20;
    /** Once this many quads are sorted, fewer than this part of them as records is logged as a failure. */
    private static final long FACE_SHARE_QUADS = 1_000_000;
    private static final double FACE_SHARE_LEAST = 0.5;
    private final CoverageMap coverage = COVERAGE ? new CoverageMap() : null;
    private final SearchEdge searchEdge = new SearchEdge();
    /** Cubes meshed as they now stand, whether or not they draw anything. */
    private final LongOpenHashSet settled = new LongOpenHashSet();
    private long settledVersion;
    private long coverageStamp = -1;
    private long coverageFrame = -COVERAGE_FRAMES;
    private long coverageNanos;
    private long lastCoverageNanos;
    private long mostCoverageNanos;
    private long coverages;
    private long emptinessAsked;
    /** The time a pass of coverage may spend asking the cube source for surface heights. */
    private static final long HEIGHT_NANOS_A_PASS = 150_000;
    private long coverageScanNanos;
    private long coverageBandNanos;
    private long coverageAskNanos;
    /** After this many passes the map is filled anew whatever changed, as a guard against a change that was not noted. */
    private static final int COVERAGE_ANEW_EVERY = 64;
    /** A diagnostic, off unless {@code -Dquadrature.coverage.check=true}: each map brought up to date is set beside one filled anew. */
    private static final boolean COVERAGE_CHECK = COVERAGE && Boolean.getBoolean("quadrature.coverage.check");
    private final it.unimi.dsi.fastutil.longs.LongArrayList coverageTouched = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private boolean coverageAnew = true;
    private long coveragesAnew;
    private CoverageMap coverageReference;
    private long coverageChecks;
    private long coverageCheckFailures;
    private long heightsAtMesh;
    private long heightsInPass;
    private long heightCapTrips;
    private SurfaceHeights surfaceHeights;
    /**
     * Off unless {@code -Dquadrature.scene.gpu=true}, which needs the scene: the scene is copied to
     * buffers a shader can read, a bounded amount a frame.
     */
    private static final boolean SCENE_GPU = SCENE && Boolean.getBoolean("quadrature.scene.gpu");
    /**
     * Off unless {@code -Dquadrature.scene.states=true}, which needs the scene's buffers: each cell's
     * block state is copied there too, and what each state does to light.
     */
    private static final boolean SCENE_STATES = SCENE_GPU && Boolean.getBoolean("quadrature.scene.states");
    /** A diagnostic, off unless {@code -Dquadrature.scene.check=true}: the buffers are read back and set beside the scene. */
    private static final boolean SCENE_CHECK = SCENE_GPU && Boolean.getBoolean("quadrature.scene.check");
    private final SceneBuffers sceneBuffers = SCENE_GPU ? new SceneBuffers(scene, SCENE_STATES, SCENE_CHECK) : null;
    private final CubeMesher mesher = new CubeMesher(scene);
    private final TerrainProgram program = new TerrainProgram();
    private final TerrainArena arena = OWN_BUFFERS ? null : CubeMesher.TARGETS && !"direct".equals(
        System.getProperty("quadrature.terrain.draw")) ? new TerrainArena(TerrainArena.WIDE_PAGE_BYTES,
        TerrainArena.WIDE_GRANULE_BYTES, FaceBuilder.WIDE_STRIDE) : new TerrainArena();
    /** A slot for every loaded cube, holding where its mesh lies in the arena. */
    private final CubeSlots slots = new CubeSlots();
    private final DrawCommands mainList = new DrawCommands();
    private final DrawCommands lightList = new DrawCommands();
    /**
     * Off unless {@code -Dquadrature.faces=true}, which needs indirect draws: whole faces are held as
     * records of 32 bytes in pages of their own and drawn by a vertex stage that builds each corner
     * from its record; every other quad keeps the game's vertices and is drawn as before.
     */
    private static final boolean FACES = CubeMesher.FACES && !DIRECT;
    private final TerrainArena faceArena = FACES
        ? new TerrainArena(TerrainArena.RECORD_PAGE_BYTES, TerrainArena.RECORD_GRANULE_BYTES) : null;
    private final CubeSlots faceSlots = new CubeSlots();
    private final DrawCommands mainFaceList = new DrawCommands();
    private final DrawCommands lightFaceList = new DrawCommands();
    private final java.nio.ByteBuffer[] keptData = new java.nio.ByteBuffer[CubeMesh.LAYERS];
    private final int[] keptCounts = new int[CubeMesh.LAYERS];
    private java.nio.ByteBuffer faceStaging = java.nio.ByteBuffer.allocateDirect(1 << 16)
        .order(java.nio.ByteOrder.nativeOrder());
    private long spritesSeen = -1;
    private int faceDrawsThisFrame;
    /**
     * Off unless {@code -Dquadrature.gbuffer=true}, which needs the face stream and reversed depth: the
     * opaque layers are also drawn into four targets and a depth of Quadrature's own, for a shading
     * stage to read. The frame the player sees is drawn as before.
     */
    private static final boolean TARGETS = FACES && CubeMesher.TARGETS;
    /** A diagnostic, off unless {@code -Dquadrature.gbuffer.image=true}: the targets are written as images where the player stands still. */
    private static final boolean TARGET_IMAGES = TARGETS && Boolean.getBoolean("quadrature.gbuffer.image");
    private final GBufferTarget targets = TARGETS ? new GBufferTarget() : null;
    private final ShadingTargets shadingTargets = TARGETS ? new ShadingTargets() : null;
    private long materialsSeen = -1;
    private boolean targetsRefused;
    private long targetFrames;
    private int targetDraws;
    private long targetStill;
    private long targetStillAt = Long.MIN_VALUE;
    private long targetImagedAt = Long.MIN_VALUE;
    private final ShadowTarget shadow = SHADOW ? new ShadowTarget() : null;
    /** What is published of the depth drawn along the light, or null when it is not drawn. */
    private final ShadowMaps shadowMaps = SHADOW
        ? new ShadowMaps(ShadowTarget.LAYERS, ShadowTarget.SIDE, ShadowTarget.BYTES) : null;
    /** The direction toward the sun, or the moon while the sun is down, this frame. */
    private final double[] towardLight = new double[3];
    /** Counts meshes placed and released, as the cube report gives it. */
    private long meshVersion;
    /** The cosine of the angle the light may turn before a layer is drawn along it anew; 0.1 degree unless set. */
    private static final double SHADOW_TURN_COSINE = Math.cos(Math.toRadians(
        Double.parseDouble(System.getProperty("quadrature.shadow.turn", "0.1"))));
    /**
     * About how many frames a layer of the light's depth drawn anew is drawn over, and the share of a
     * layer's cost a frame may spend drawing tiles again for changed meshes: 8 unless
     * {@code quadrature.shadow.spread} is set.
     */
    private static final int SHADOW_SPREAD = Math.max(1, Integer.getInteger("quadrature.shadow.spread", 8));
    /** The layers of the light's depth: the near one first where it is drawn, then the wide one. */
    private final LightLayer[] lightLayers = SHADOW ? lightLayers() : new LightLayer[0];
    private final int[] lightRange = new int[4];
    /** The alpha at or below which the game drops a fragment of a cutout layer, as last seen set. */
    private float cutoutReference = 0.1f;
    private long shadowNanos;
    private long lastShadowNanos;
    private long mostShadowNanos;
    /** Frames that drew any of the light's depth, and frames that published it. */
    private long shadowFrames;
    private long lightFrames;
    /** The CPU time of the slowest frame that drew since the last summary, and that frame's index. */
    private long mostShadowNanosSince;
    private long mostShadowFrameSince;
    private long shadowCpuOverOne;
    /**
     * The light's CPU time this frame, in parts: sweeping the boxes, counting and choosing tiles, issuing the
     * tiles' clears, writing commands, issuing the draws, issuing the copies that publish a new box, and
     * setting and putting back the state around them with the timer queries. Summed over the frames that drew, and kept for the slowest frame since the
     * last summary.
     */
    private final long[] lightParts = new long[7];
    private final long[] lightPartsTotal = new long[7];
    private final long[] lightPartsWorst = new long[7];
    private long stillFrames;
    private long stillAt = Long.MIN_VALUE;
    private long imagedAt = Long.MIN_VALUE;
    /** Meshes the main view sees that are not in the arena, drawn one by one after the indirect draws. */
    private final java.util.ArrayList<CubeMesh> loose = new java.util.ArrayList<>();
    private final float[] matrix = new float[CubeMatrix.LENGTH];
    private int looseMeshes;
    /** Every loaded cube, with the faces of it that see each other: all of them until it is meshed. */
    private final Long2ShortOpenHashMap links = new Long2ShortOpenHashMap();
    private final View mainView = new View();
    private final View searchedView = new View();
    private final Visibility visible = new Visibility();
    /** Counts changes to {@link #links}; a search holds while it and the view stand still. */
    private long tableVersion;
    private long searchedVersion = -1;
    private final long[] nearest = new long[CANDIDATES];
    private final double[] nearestDistance = new double[CANDIDATES];
    private boolean attached;

    // Diagnostics, each absent unless its property is set.
    private final SelectionComparison comparison = SelectionComparison.ENABLED ? new SelectionComparison() : null;
    private final LongOpenHashSet drawnCubes = comparison != null ? new LongOpenHashSet() : null;
    private final LongOpenHashSet selectedCubes = SelectionComparison.GAME_SELECTION != 0
        || ScreenProbe.INSTANCE != null ? new LongOpenHashSet() : null;

    // Counters for the timing log.
    private long built;
    private long buildNanos;
    private long emptied;
    private long waiting;
    private long vertices;
    private long bytes;
    private long drawsThisFrame;
    private long drawnVerticesThisFrame;
    private long frames;
    private long searches;
    private long searchNanos;
    private long reused;
    private long fellBack;
    private long lastSearchNanos;
    private long placed;
    private long placeNanos;
    private long placeNanosThisFrame;
    private long lastPlaceNanos;
    private long mostPlaceNanos;
    private int placedThisFrame;
    private long placedBytesThisFrame;
    private long largestPlaceNanosThisFrame;
    private long madeInTheWayNanosThisFrame;
    private long mostMadeInTheWayNanos;
    /** What the frame that spent the most time placing held. */
    private int worstPlaced;
    private long worstPlacedBytes;
    private long worstLargestPlaceNanos;
    private long stoppedByPlacing;
    private static final long SLOW_PLACE_NANOS = 500_000L;
    private int slowPlacings;
    private long slotNanos;
    private long refused;
    private long lastCommandNanos;

    private Terrain(ICubeSource source) {
        this.source = source;
        links.defaultReturnValue((short) -1);
    }

    /** Starts reading the cube source, if it exists and own terrain was asked for. */
    static void start() {
        if (!TerrainHook.requested()) {
            return;
        }
        ICubeSource source = CubeSource.get();
        if (source == null) {
            Quadrature.LOGGER.error("Own terrain was requested, but no cube source is installed.");
            return;
        }
        Terrain terrain = new Terrain(source);
        source.setListener(terrain);
        TerrainHook.install(terrain);
        Quadrature.LOGGER.info("Own terrain is on: opaque layers are drawn from cube snapshots.");
    }

    @Override
    public boolean active() {
        return attached;
    }

    // Events from the cube source.

    @Override
    public void worldAttached(long worldIdentity) {
        clear();
        attached = true;
        source.forEachLoaded((x, y, z) -> {
            dirty.add(CubeKey.of(x, y, z));
            links.put(CubeKey.of(x, y, z), CubeConnectivity.ALL);
            slots.acquire(CubeKey.of(x, y, z));
        });
        tableVersion++;
        coverageAnew = true;
        if (shadow != null) {
            // Behind the screen that waits for the world: the light's targets are made and their memory
            // taken now, so the first frame that draws them does not pay for it.
            Quadrature.LOGGER.info("Shadow target prepared for the world in {} ms.", String.format("%.1f", shadow.prepare()));
        }
    }

    @Override
    public void worldDetached(long worldIdentity) {
        clear();
        attached = false;
    }

    @Override
    public void cubeLoaded(int x, int y, int z) {
        long key = CubeKey.of(x, y, z);
        touch(key);
        dirty.add(key);
        // Until it is meshed nothing is known of it, so it hides nothing.
        if (!links.containsKey(key)) {
            links.put(key, CubeConnectivity.ALL);
            slots.acquire(key);
            tableVersion++;
        }
    }

    @Override
    public void cubeUnloaded(int x, int y, int z) {
        long key = CubeKey.of(x, y, z);
        touch(key);
        dirty.remove(key);
        release(meshes.remove(key));
        links.remove(key);
        slots.release(key);
        releaseFaceSlot(key);
        tableVersion++;
        unsettle(key);
        if (scene != null) {
            scene.remove(x, y, z);
        }
    }

    private void releaseFaceSlot(long key) {
        if (faceSlots.find(key) >= 0) {
            faceSlots.release(key);
        }
    }

    private void unsettle(long key) {
        if (settled.remove(key)) {
            settledVersion++;
        }
    }

    @Override
    public void cubeChanged(int x, int y, int z, int kinds, int minX, int minY, int minZ, int maxX, int maxY,
        int maxZ) {
        touch(CubeKey.of(x, y, z));
        dirty.add(CubeKey.of(x, y, z));
        unsettle(CubeKey.of(x, y, z));
    }

    private void clear() {
        for (CubeMesh mesh : meshes.values()) {
            release(mesh);
        }
        meshes.clear();
        dirty.clear();
        settled.clear();
        settledVersion++;
        if (scene != null) {
            scene.clear();
        }
        for (LongIterator it = links.keySet().iterator(); it.hasNext();) {
            long held = it.nextLong();
            slots.release(held);
            releaseFaceSlot(held);
        }
        links.clear();
        tableVersion++;
        coverageAnew = true;
        coverageTouched.clear();
        if (surfaceHeights != null) {
            // Another world's columns are other columns: no answer carries over.
            surfaceHeights.clear();
        }
        // Another world's light falls on other terrain: every box is forgotten and the published depth cleared.
        for (LightLayer layer : lightLayers) {
            layer.plan.reset();
        }
        if (shadow != null) {
            shadow.clearPublished();
        }
    }

    private static LightLayer[] lightLayers() {
        LightLayer wide = new LightLayer(ShadowTarget.LAYOUT.wideLayer(), "wide", false, SHADOW_TURN_COSINE, SHADOW_SPREAD);
        if (!ShadowTarget.LAYOUT.near()) {
            return new LightLayer[] {wide};
        }
        return new LightLayer[] {new LightLayer(ShadowTarget.LAYOUT.nearLayer(), "near", true, SHADOW_TURN_COSINE,
            SHADOW_SPREAD), wide};
    }

    /** A cube's mesh was placed, replaced or released: every layer of the light's depth draws its tiles again. */
    private void lightChanged(int cubeX, int cubeY, int cubeZ) {
        for (LightLayer layer : lightLayers) {
            layer.plan.changed(cubeX, cubeY, cubeZ);
        }
    }

    private void release(CubeMesh mesh) {
        if (mesh != null) {
            meshVersion++;
            lightChanged(mesh.x, mesh.y, mesh.z);
            vertices -= mesh.vertices();
            bytes -= mesh.bytes();
            if (mesh.faceBlock >= 0) {
                int slot = faceSlots.find(CubeKey.of(mesh.x, mesh.y, mesh.z));
                if (slot >= 0) {
                    faceSlots.clear(slot);
                }
                faceArena.freeBytes(mesh.faceBlock, mesh.faceBytes, frames);
                mesh.faceBlock = -1;
            }
            if (mesh.inArena()) {
                int slot = slots.find(CubeKey.of(mesh.x, mesh.y, mesh.z));
                if (slot >= 0) {
                    slots.clear(slot);
                }
                arena.free(mesh, frames);
            } else if (!FACES) {
                if (arena != null) {
                    looseMeshes--;
                }
                mesh.delete();
            }
        }
    }

    // Building.

    /** Meshes the nearest cubes that need it, until the frame's budget is spent. */
    private void build(double cameraX, double cameraY, double cameraZ) {
        if (!attached || dirty.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + BUILD_BUDGET_NANOS;
        while (!dirty.isEmpty()) {
            int count = selectNearest(cameraX, cameraY, cameraZ);
            for (int i = 0; i < count; i++) {
                long key = nearest[i];
                dirty.remove(key);
                rebuild(key);
                if (placeNanosThisFrame >= PLACE_BUDGET_NANOS) {
                    // The cubes left stay as they are, old mesh and all, and come first next frame.
                    stoppedByPlacing++;
                    return;
                }
                if (System.nanoTime() > deadline) {
                    return;
                }
            }
        }
    }

    /** Fills {@link #nearest} with the dirty cubes closest to the camera, closest first. */
    private int selectNearest(double cameraX, double cameraY, double cameraZ) {
        int count = 0;
        for (LongIterator it = dirty.iterator(); it.hasNext();) {
            long key = it.nextLong();
            double distance = CubeKey.distanceSquared(key, cameraX, cameraY, cameraZ);
            if (count == CANDIDATES && distance >= nearestDistance[count - 1]) {
                continue;
            }
            int at = count < CANDIDATES ? count++ : count - 1;
            while (at > 0 && nearestDistance[at - 1] > distance) {
                nearest[at] = nearest[at - 1];
                nearestDistance[at] = nearestDistance[at - 1];
                at--;
            }
            nearest[at] = key;
            nearestDistance[at] = distance;
        }
        return count;
    }

    private void rebuild(long key) {
        touch(key);
        int x = CubeKey.x(key);
        int y = CubeKey.y(key);
        int z = CubeKey.z(key);
        if (!source.isLoaded(x, y, z)) {
            release(meshes.remove(key));
            links.remove(key);
            slots.release(key);
            releaseFaceSlot(key);
            tableVersion++;
            unsettle(key);
            if (scene != null) {
                scene.remove(x, y, z);
            }
            return;
        }
        if (!surrounded(x, y, z)) {
            // As in the game, a cube that cannot be meshed now keeps the mesh it has.
            if (COVERAGE && !meshes.containsKey(key) && !settled.contains(key)) {
                // Whether it is empty can still be known, and coverage needs it: the highest cubes held
                // never have a cube above them, and are the air over the ground.
                if (surfaceHeights == null) {
                    surfaceHeights = new SurfaceHeights(source);
                }
                boolean empty;
                if (surfaceHeights.tellsContent()) {
                    empty = surfaceHeights.empty(x, y, z);
                } else {
                    ICubeSnapshot held = source.snapshot(x, y, z);
                    empty = held.centerEmpty();
                    held.release();
                }
                emptinessAsked++;
                if (empty) {
                    settled.add(key);
                    settledVersion++;
                }
            }
            return;
        }
        release(meshes.remove(key));
        ICubeSnapshot snapshot = source.snapshot(x, y, z);
        if (snapshot.centerEmpty()) {
            emptied++;
            snapshot.release();
            setLinks(key, CubeConnectivity.ALL);
            if (settled.add(key)) {
                settledVersion++;
            }
            if (scene != null) {
                scene.putUniform(x, y, z, LightScene.AIR);
            }
            return;
        }
        long start = System.nanoTime();
        boolean any = mesher.build(snapshot, x, y, z);
        snapshot.release();
        setLinks(key, mesher.links());
        if (FACE_CENSUS) {
            mesher.census(faceCensus);
        }
        if (settled.add(key)) {
            settledVersion++;
        }
        if (scene != null) {
            scene.put(x, y, z, mesher.cells(), mesher.blocking());
        }
        if (COVERAGE && any) {
            // A column's surface heights are asked for here, beside the mesh of a cube of it that draws
            // something, and again when the source's version of the column has moved: once or a few
            // times a column, each a small part of a mesh's own time. The coverage pass then only
            // looks at what is kept.
            if (surfaceHeights == null) {
                surfaceHeights = new SurfaceHeights(source);
            }
            if (surfaceHeights.present() && !surfaceHeights.known(x, z)) {
                surfaceHeights.ask(x, z);
                heightsAtMesh++;
            }
        }
        CubeMesh mesh = null;
        if (any) {
            long placing = System.nanoTime();
            mesh = FACES ? placeFaces(key, x, y, z) : arena == null ? null : arena.place(x, y, z, mesher, frames);
            if (FACES) {
                slotNanos = 0;
            } else if (mesh == null) {
                // No arena, or no room in it: the mesh keeps buffers of its own and is drawn by itself.
                mesh = CubeMesh.owned(x, y, z, mesher);
                if (arena != null) {
                    refused++;
                    looseMeshes++;
                }
            } else {
                long slotting = System.nanoTime();
                slots.set(slots.acquire(key), mesh.block, mesh.blockBytes, mesh.first(0), mesh.count(0),
                    mesh.first(1), mesh.count(1), mesh.first(2), mesh.count(2));
                slotNanos = System.nanoTime() - slotting;
            }
            placing = System.nanoTime() - placing;
            if (placing >= SLOW_PLACE_NANOS && arena != null && slowPlacings++ < 100) {
                // Where a long placement's time went: finding room, the driver's writes, the slot, and the rest.
                long made = arena.takeCreateNanos();
                Quadrature.LOGGER.info("Terrain: slow placement, {} ms: mesh {} of the run, {} bytes, frame {}; finding "
                        + "room {} ms, the driver's writes {} ms, the slot {} ms, making a page {} ms, the rest {} ms",
                    String.format("%.3f", placing / 1e6), placed + 1, mesh.bytes(), frames,
                    String.format("%.3f", arena.lastAllocateNanos() / 1e6), String.format("%.3f", arena.lastWriteNanos() / 1e6),
                    String.format("%.3f", slotNanos / 1e6), String.format("%.3f", made / 1e6),
                    String.format("%.3f", (placing - arena.lastAllocateNanos() - arena.lastWriteNanos() - slotNanos - made) / 1e6));
                placing -= made;
                madeInTheWayNanosThisFrame += made;
            }
            if (arena != null) {
                // A page made because none was ready is the driver's time, counted apart.
                long made = arena.takeCreateNanos();
                placing -= made;
                madeInTheWayNanosThisFrame += made;
            }
            placeNanosThisFrame += placing;
            placeNanos += placing;
            placed++;
            placedThisFrame++;
            placedBytesThisFrame += mesh.bytes();
            largestPlaceNanosThisFrame = Math.max(largestPlaceNanosThisFrame, placing);
        }
        mesher.done();
        buildNanos += System.nanoTime() - start;
        built++;
        if (mesh != null) {
            meshVersion++;
            lightChanged(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key));
            mesh.placedAt = meshVersion;
            meshes.put(key, mesh);
            vertices += mesh.vertices();
            bytes += mesh.bytes();
        }
    }

    /**
     * Places the cube the mesher last built as face records and kept vertices: the records in a block
     * of the arena that holds records, the kept vertices in a block of the arena that holds vertices.
     */
    private CubeMesh placeFaces(long key, int x, int y, int z) {
        FaceBuilder built = mesher.faceBuilder();
        int kept = 0;
        int records = 0;
        for (int layer = 0; layer < CubeMesh.LAYERS; layer++) {
            keptCounts[layer] = built.vertexCount(layer);
            keptData[layer] = null;
            if (keptCounts[layer] > 0) {
                keptData[layer] = built.vertices(layer).duplicate();
                keptData[layer].flip();
            }
            kept += keptCounts[layer];
            records += built.recordCount(layer);
        }
        CubeMesh mesh = null;
        if (kept > 0) {
            mesh = arena.place(x, y, z, keptData, keptCounts, frames);
            if (mesh == null) {
                refused++;
            }
        }
        if (mesh == null) {
            mesh = new CubeMesh(x, y, z);
        }
        if (mesh.inArena()) {
            slots.set(slots.acquire(key), mesh.block, mesh.blockBytes, mesh.first(0), mesh.count(0), mesh.first(1),
                mesh.count(1), mesh.first(2), mesh.count(2));
        } else {
            int slot = slots.find(key);
            if (slot >= 0) {
                slots.clear(slot);
            }
        }
        if (records > 0) {
            int bytes = records * FaceRecord.BYTES;
            if (faceStaging.capacity() < bytes) {
                faceStaging = java.nio.ByteBuffer.allocateDirect(Math.max(bytes, faceStaging.capacity() * 2))
                    .order(java.nio.ByteOrder.nativeOrder());
            }
            faceStaging.clear();
            java.nio.IntBuffer words = faceStaging.asIntBuffer();
            for (int layer = 0; layer < CubeMesh.LAYERS; layer++) {
                words.put(built.records(layer), 0, built.recordCount(layer) * FaceRecord.WORDS);
            }
            long block = faceArena.placeBytes(faceStaging, bytes, frames);
            if (block < 0) {
                refused++;
            } else {
                mesh.faceBlock = block;
                mesh.faceBytes = bytes;
                int first = (int) (faceArena.offsetOf(block) / FaceRecord.BYTES);
                for (int layer = 0; layer < CubeMesh.LAYERS; layer++) {
                    mesh.faceFirsts[layer] = first;
                    mesh.faceCounts[layer] = built.recordCount(layer);
                    first += built.recordCount(layer);
                }
                // A record is drawn as four vertices, so a slot holds vertices: four to a record.
                faceSlots.set(faceSlots.acquire(key), block, bytes, mesh.faceFirsts[0] * 4, mesh.faceCounts[0] * 4,
                    mesh.faceFirsts[1] * 4, mesh.faceCounts[1] * 4, mesh.faceFirsts[2] * 4, mesh.faceCounts[2] * 4);
            }
        }
        return mesh;
    }

    /** Writes what a view draws from face records: a record and commands for every cube it sees that has any. */
    private void commandFaces(DrawCommands list, CubeList seen, View view) {
        list.begin(faceArena.pages());
        double originX = view.originX();
        double originY = view.originY();
        double originZ = view.originZ();
        float[] modelView = view.modelView();
        for (int i = 0, count = seen.count(); i < count; i++) {
            long key = seen.key(i);
            int slot = faceSlots.find(key);
            if (slot < 0 || faceSlots.block(slot) < 0) {
                continue;
            }
            CubeMatrix.compose(modelView, (float) ((CubeKey.x(key) << 4) - originX),
                (float) ((CubeKey.y(key) << 4) - originY), (float) ((CubeKey.z(key) << 4) - originZ), matrix, 0);
            int index = list.record(matrix, 0);
            int page = ArenaAllocator.page(faceSlots.block(slot));
            for (int layer = 0; layer < CubeSlots.LAYERS; layer++) {
                int vertices = faceSlots.count(slot, layer);
                if (vertices > 0) {
                    list.add(layer, page, faceSlots.first(slot, layer), vertices, index);
                }
            }
        }
        list.finish();
    }

    /**
     * The game's own rule for when a cube is meshed, kept so both draw the same cubes: beyond 24
     * blocks from the player, only once the six cubes sharing a face with it are loaded. A cube that
     * waits is meshed when the missing neighbor arrives, since that arrival reaches it as a change.
     * The outermost row of what the client holds always lacks a neighbor and is never meshed. The test
     * is made when the cube comes up for meshing, not afterwards: a mesh made while the neighbors were
     * there, or while the player was near, stays when that stops being true.
     */
    private boolean surrounded(int x, int y, int z) {
        net.minecraft.entity.Entity player = net.minecraft.client.Minecraft.getMinecraft().player;
        if (player != null) {
            double dx = (x << 4) + 8 - Math.floor(player.posX);
            double dy = (y << 4) + 8 - Math.floor(player.posY);
            double dz = (z << 4) + 8 - Math.floor(player.posZ);
            if (dx * dx + dy * dy + dz * dz <= NEAR_BLOCKS_SQUARED) {
                return true;
            }
        }
        boolean all = source.isLoaded(x - 1, y, z) && source.isLoaded(x + 1, y, z) && source.isLoaded(x, y - 1, z)
            && source.isLoaded(x, y + 1, z) && source.isLoaded(x, y, z - 1) && source.isLoaded(x, y, z + 1);
        if (!all) {
            waiting++;
        }
        return all;
    }

    // Seeing.

    @Override
    public int links(int cubeX, int cubeY, int cubeZ) {
        return links.get(CubeKey.of(cubeX, cubeY, cubeZ));
    }

    /** The cubes that hold a mesh: what a view with no eye inside the world chooses among. */
    @Override
    public void each(CubeVisitor visitor) {
        for (CubeMesh mesh : meshes.values()) {
            visitor.cube(mesh.x, mesh.y, mesh.z);
        }
    }

    /**
     * Works the coverage map out again, if what is held has changed or the player stands in another
     * cube, and not within twenty frames of the last time.
     */
    private void cover() {
        if (surfaceHeights == null) {
            surfaceHeights = new SurfaceHeights(source);
        }
        long stamp = meshVersion * 31 + tableVersion * 17 + settledVersion;
        boolean moved = mainView.originCubeX() != coverage.originCubeX() || mainView.originCubeZ() != coverage.originCubeZ()
            || mainView.originCubeY() != coverageOriginY;
        if (stamp == coverageStamp && !moved || frames - coverageFrame < COVERAGE_FRAMES) {
            return;
        }
        long start = System.nanoTime();
        coverageStamp = stamp;
        coverageFrame = frames;
        coverageOriginY = mainView.originCubeY();
        // The map is filled anew when the box is another, when everything changed at once, and now and
        // then as a guard; otherwise only the cubes that changed since the last time are told of.
        boolean anew = coverageAnew || coverages % COVERAGE_ANEW_EVERY == 0
            || !coverage.holds(mainView.originCubeX(), mainView.originCubeY(), mainView.originCubeZ(), mainView.across(),
                mainView.upward());
        int lowX = mainView.originCubeX() - mainView.across();
        int lowZ = mainView.originCubeZ() - mainView.across();
        if (anew) {
            coverageAnew = false;
            coveragesAnew++;
            coverage.begin(mainView.originCubeX(), mainView.originCubeY(), mainView.originCubeZ(), mainView.across(),
                mainView.upward());
            for (LongIterator it = links.keySet().iterator(); it.hasNext();) {
                long key = it.nextLong();
                coverage.cube(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key), coverageState(key));
            }
        } else {
            coverage.update();
            for (int i = 0, count = coverageTouched.size(); i < count; i++) {
                long key = coverageTouched.getLong(i);
                coverage.cube(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key), coverageState(key));
            }
        }
        coverageTouched.clear();
        int side = coverage.side();
        long scanned = System.nanoTime();
        if (surfaceHeights.present()) {
            // Where the cube source has said where a column's surface lies, the column is judged by that.
            if (anew) {
                for (int z = 0; z < side; z++) {
                    for (int x = 0; x < side; x++) {
                        long band = surfaceHeights.band(lowX + x, lowZ + z);
                        if (band != SurfaceBand.NONE) {
                            coverage.band(lowX + x, lowZ + z, SurfaceBand.lowest(band), SurfaceBand.highest(band));
                        }
                    }
                }
                surfaceHeights.answered().clear();
            } else {
                // Only the columns answered since the last time can have another band.
                it.unimi.dsi.fastutil.longs.LongArrayList answered = surfaceHeights.answered();
                for (int i = 0, count = answered.size(); i < count; i++) {
                    long column = answered.getLong(i);
                    int columnX = (int) (column >> 32);
                    int columnZ = (int) column;
                    long band = surfaceHeights.band(columnX, columnZ);
                    if (band == SurfaceBand.NONE) {
                        coverage.noBand(columnX, columnZ);
                    } else {
                        coverage.band(columnX, columnZ, SurfaceBand.lowest(band), SurfaceBand.highest(band));
                    }
                }
                answered.clear();
            }
        }
        long banded = System.nanoTime();
        coverage.finish(source.maxCubeY());
        long finished = System.nanoTime();
        if (COVERAGE_CHECK && !anew) {
            checkCoverage(lowX, lowZ);
            finished = System.nanoTime();
        }
        if (surfaceHeights.present()) {
            // A guard: columns are asked about beside their meshes, so here only one whose version moved
            // with no mesh of it made since, or that was meshed before coverage began, is left to ask.
            // Columns are asked about only where the scan found ground among the cubes held: elsewhere
            // the source has no loaded answer, and asking is wasted. The nearest come first, ring by
            // ring outward, and asking stops for this pass once it has taken its share of time.
            int center = mainView.across();
            long deadline = finished + HEIGHT_NANOS_A_PASS;
            asking:
            for (int ring = 0; ring <= center; ring++) {
                for (int z = center - ring; z <= center + ring; z++) {
                    boolean edge = z == center - ring || z == center + ring;
                    for (int x = center - ring; x <= center + ring; x += edge || ring == 0 ? 1 : 2 * ring) {
                        if (coverage.state(lowX + x, lowZ + z) >= CoverageMap.SURFACE_WAITING
                            && !surfaceHeights.known(lowX + x, lowZ + z)) {
                            surfaceHeights.ask(lowX + x, lowZ + z);
                            heightsInPass++;
                            if (System.nanoTime() >= deadline) {
                                heightCapTrips++;
                                break asking;
                            }
                        }
                    }
                }
            }
        }
        lastCoverageNanos = System.nanoTime() - start;
        coverageScanNanos += scanned - start + finished - banded;
        coverageBandNanos += banded - scanned;
        coverageAskNanos += start + lastCoverageNanos - finished;
        coverageNanos += lastCoverageNanos;
        mostCoverageNanos = Math.max(mostCoverageNanos, lastCoverageNanos);
        coverages++;
    }

    private int coverageOriginY;

    /** What the coverage map is told of a cube: meshed, known to draw nothing, held, or no longer held. */
    private byte coverageState(long key) {
        return meshes.containsKey(key) ? CoverageMap.CUBE_MESHED : settled.contains(key) ? CoverageMap.CUBE_EMPTY
            : links.containsKey(key) ? CoverageMap.CUBE_HELD : CoverageMap.CUBE_GONE;
    }

    /** Notes that a cube arrived, left or was meshed anew, for the coverage map and the heights kept for its column. */
    private void touch(long key) {
        if (COVERAGE) {
            coverageTouched.add(key);
            if (surfaceHeights != null) {
                surfaceHeights.touched(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key));
            }
        }
    }

    /** A diagnostic: fills a second map anew and sets it beside the one brought up to date. */
    private void checkCoverage(int lowX, int lowZ) {
        if (coverageReference == null) {
            coverageReference = new CoverageMap();
        }
        coverageReference.begin(mainView.originCubeX(), mainView.originCubeY(), mainView.originCubeZ(), mainView.across(),
            mainView.upward());
        for (LongIterator it = links.keySet().iterator(); it.hasNext();) {
            long key = it.nextLong();
            coverageReference.cube(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key), coverageState(key));
        }
        int side = coverage.side();
        if (surfaceHeights.present()) {
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    long band = surfaceHeights.band(lowX + x, lowZ + z);
                    if (band != SurfaceBand.NONE) {
                        coverageReference.band(lowX + x, lowZ + z, SurfaceBand.lowest(band), SurfaceBand.highest(band));
                    }
                }
            }
        }
        coverageReference.finish(source.maxCubeY());
        coverageChecks++;
        if (!java.util.Arrays.equals(coverageReference.states(), coverage.states())) {
            int differing = 0;
            for (int i = 0; i < coverage.states().length; i++) {
                differing += coverage.states()[i] != coverageReference.states()[i] ? 1 : 0;
            }
            coverageCheckFailures++;
            if (coverageCheckFailures <= 20) {
                Quadrature.LOGGER.error("Coverage check: the map brought up to date differs from one filled anew in {} of {} "
                    + "columns, pass {}.", differing, coverage.states().length, coverages);
            }
        }
    }
    private void setLinks(long key, short joined) {
        if (links.put(key, joined) != joined) {
            tableVersion++;
        }
    }

    /**
     * Takes the frame's view from the game, meshes what the budget allows, and finds what the view
     * sees. A search is kept while neither the view nor any cube's links change, as when standing.
     */
    @Override
    public View mainView(double partialTicks, Entity viewer) {
        MainView.fill(mainView, partialTicks, viewer);
        frames++;
        DriverMessages.install();
        if (arena != null) {
            arena.beginFrame();
        }
        if (FACES) {
            faceArena.beginFrame();
            if (faceArena.pages() == 0) {
                faceArena.prepare();
                faceArena.takeCreateNanos();
            }
        }
        if (arena != null && arena.pages() == 0) {
            arena.prepare();
            arena.takeCreateNanos();
        }
        placeNanosThisFrame = 0;
        placedThisFrame = 0;
        placedBytesThisFrame = 0;
        largestPlaceNanosThisFrame = 0;
        madeInTheWayNanosThisFrame = 0;
        build(mainView.originX(), mainView.originY(), mainView.originZ());
        if (arena != null) {
            long flushing = System.nanoTime();
            arena.flush();
            flushing = System.nanoTime() - flushing;
            placeNanosThisFrame += flushing;
            placeNanos += flushing;
        }
        if (sceneBuffers != null) {
            sceneBuffers.update(mainView.originCubeX(), mainView.originCubeY(), mainView.originCubeZ(), mainView.across(),
                mainView.upward());
        }
        lastPlaceNanos = placeNanosThisFrame;
        if (placeNanosThisFrame > mostPlaceNanos) {
            mostPlaceNanos = placeNanosThisFrame;
            worstPlaced = placedThisFrame;
            worstPlacedBytes = placedBytesThisFrame;
            worstLargestPlaceNanos = largestPlaceNanosThisFrame;
        }
        mostMadeInTheWayNanos = Math.max(mostMadeInTheWayNanos, madeInTheWayNanosThisFrame);
        long start = System.nanoTime();
        if (DRAW_ALL) {
            visible.sweep(this, mainView);
        } else if (searchedVersion == tableVersion && mainView.sameVolume(searchedView)) {
            reused++;
        } else if (visible.search(this, mainView) < 0) {
            // The eye is outside the view's own reach: nothing is known, so nothing is left out.
            visible.sweep(this, mainView);
            searchedVersion = -1;
            fellBack++;
        } else {
            searchedVersion = tableVersion;
            searchedView.copy(mainView);
            searches++;
            searchNanos += System.nanoTime() - start;
        }
        lastSearchNanos = System.nanoTime() - start;
        searchEdge.set(!DRAW_ALL && searchedVersion >= 0, visible.edgeFaces(), visible.edgeSteps(),
            visible.unloadedEntered());
        if (COVERAGE) {
            cover();
        }

        // The light's boxes: a layer begins a new one where the light has turned, the player has gone on or
        // nothing is drawn yet. What each layer has due is drawn after the player's commands below.
        if (SHADOW) {
            lightFrame(partialTicks);
        }

        if (selectedCubes != null) {
            // The game has finished its search by the time it draws the first layer.
            SelectionComparison.selected(selectedCubes);
        }
        if (!DIRECT) {
            start = System.nanoTime();
            command(mainList, visible, mainView, true);
            program.upload(mainList);
            lastCommandNanos = System.nanoTime() - start;
            for (int page = 0; page < mainList.pages(); page++) {
                if (mainList.runCount(0, page) + mainList.runCount(1, page) + mainList.runCount(2, page) > 0) {
                    arena.drawn(page, frames);
                }
            }
            if (FACES) {
                commandFaces(mainFaceList, visible, mainView);
                program.uploadFaces(mainFaceList, 0);
                FaceBuilder built = mesher.faceBuilder();
                if (spritesSeen != built.spritesVersion()) {
                    spritesSeen = built.spritesVersion();
                    program.uploadSprites(built.spriteRects(), built.sprites());
                }
            }
            if (TARGETS) {
                program.vertexStride(FaceBuilder.WIDE_STRIDE);
                Materials materials = mesher.materials();
                if (materialsSeen != materials.table().version()) {
                    materialsSeen = materials.table().version();
                    program.uploadMaterials(materials.table().bytes());
                }
            }
            if (SHADOW) {
                drawLight();
            }
        }
        return mainView;
    }

    /**
     * Starts the light's frame: the direction toward the light, and for each layer whether a new box is
     * begun ({@link LightLayerPlan#frame}). Meshes placed and released this frame are reported already.
     */
    private void lightFrame(double partialTicks) {
        MainView.towardLight(partialTicks, towardLight);
        double wide = ShadowLayers.wideSide(mainView.across(), mainView.upward());
        for (LightLayer layer : lightLayers) {
            layer.plan.frame(towardLight[0], towardLight[1], towardLight[2], mainView.originX(), mainView.originY(),
                mainView.originZ(), layer.near ? ShadowLayers.NEAR_SIDE : wide, wide, ShadowTarget.SIDE);
        }
    }

    /**
     * Draws what each layer of the light's depth has due this frame and publishes every layer: first the
     * front's dirty tiles in place, then the next tiles of a box being drawn into the scratch, which is
     * copied over the front once it is whole. A frame with nothing due draws nothing and sweeps nothing;
     * every layer is published all the same, through a view made from its box for this frame's origin.
     */
    private void drawLight() {
        long start = System.nanoTime();
        shadowMaps.beginFrame(mainView.originX(), mainView.originY(), mainView.originZ(), towardLight[0],
            towardLight[1], towardLight[2]);
        java.util.Arrays.fill(lightParts, 0);
        boolean began = false;
        for (LightLayer layer : lightLayers) {
            layer.lastDraws = 0;
            if (layer.plan.frontDirty()) {
                began = beginLight(began);
                drawTiles(layer, false);
            }
            if (layer.plan.next() != null) {
                began = beginLight(began);
                drawTiles(layer, true);
            } else if (SHADOW_CHECK && lightFrames % SHADOW_CHECK_FRAMES == SHADOW_CHECK_FRAMES - 1
                && !layer.plan.frontDirty() && layer.plan.front() != null) {
                began = beginLight(began);
                checkLayer(layer);
            }
        }
        if (began) {
            long mark = System.nanoTime();
            shadow.end();
            program.end();
            DriverMessages.leave();
            lightParts[6] += System.nanoTime() - mark;
        }
        for (LightLayer layer : lightLayers) {
            LightLayerPlan plan = layer.plan;
            LightBox box = plan.front() != null ? plan.front() : plan.next();
            if (box == null) {
                continue;
            }
            // Until a box is drawn whole the published layer is clear, so nothing is in shadow.
            View.throughLight(layer.published, mainView, box);
            shadowMaps.layer(layer.index, layer.published, box, plan.takeContentChanged(), plan.takeLightChanged());
        }
        lightFrames++;
        if (began) {
            shadowFrames++;
            lastShadowNanos = System.nanoTime() - start;
            shadowNanos += lastShadowNanos;
            mostShadowNanos = Math.max(mostShadowNanos, lastShadowNanos);
            if (lastShadowNanos > 1_000_000) {
                shadowCpuOverOne++;
            }
            for (int part = 0; part < lightParts.length; part++) {
                lightPartsTotal[part] += lightParts[part];
            }
            if (lastShadowNanos > mostShadowNanosSince) {
                mostShadowNanosSince = lastShadowNanos;
                mostShadowFrameSince = lightFrames;
                System.arraycopy(lightParts, 0, lightPartsWorst, 0, lightParts.length);
            }
        }
        image();
    }

    /** Sets the state the light's depth is drawn in, once a frame, before the first layer that draws. */
    private boolean beginLight(boolean began) {
        if (!began) {
            long mark = System.nanoTime();
            DriverMessages.enter("shadow");
            shadow.begin(lightFrames);
            lightParts[6] += System.nanoTime() - mark;
            shadowMaps.objects(shadow.texture(), shadow.compareSampler(), shadow.depthSampler());
            shadowMaps.polygonOffset(ShadowTarget.POLYGON_OFFSET[0], ShadowTarget.POLYGON_OFFSET[1]);
        }
        return true;
    }

    /**
     * Draws a layer's tiles due this frame, of the front in place or of the box being drawn into the
     * scratch: the box is swept for the cubes it holds, each tile is given the count of drawable cubes
     * that may cover it, and the tiles the plan chooses are cleared and every cube that may cover any of
     * them is drawn whole. Faces are drawn by the same indirect draws as the player's view, each cube
     * under the same matrix composition, and cutout layers are tested against the block texture's
     * alpha. Entities are not drawn.
     */
    private void drawTiles(LightLayer layer, boolean next) {
        LightLayerPlan plan = layer.plan;
        LightBox box = next ? plan.next() : plan.front();
        View view = View.throughLight(next ? layer.next : layer.front, mainView, box);
        long mark = System.nanoTime();
        layer.swept.sweep(this, view);
        long now = System.nanoTime();
        lightParts[0] += now - mark;
        mark = now;
        layer.clearCandidates();
        for (int i = 0, count = layer.swept.count(); i < count; i++) {
            long key = layer.swept.key(i);
            if (drawable(key) && box.texelsOf(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key), lightRange)) {
                layer.candidate(key, lightRange);
                LightTiles.count(layer.costs, lightRange, box.texels());
            }
        }
        BitSet tiles = next ? plan.chooseNext(layer.costs) : plan.chooseFront(layer.costs);
        layer.chosen.clear();
        for (int i = 0, count = layer.candidates.count(); i < count; i++) {
            System.arraycopy(layer.ranges, i * 4, lightRange, 0, 4);
            if (LightTiles.covers(tiles, lightRange, box.texels())) {
                layer.chosen.add(layer.candidates.key(i));
            }
        }
        now = System.nanoTime();
        lightParts[1] += now - mark;
        mark = now;
        shadow.attach(next, layer.index);
        shadow.clear(layer.rectangles, LightTiles.rectangles(tiles, box.texels(), layer.rectangles));
        now = System.nanoTime();
        lightParts[2] += now - mark;
        mark = now;
        if (layer.chosen.count() > 0) {
            command(lightList, layer.chosen, view, false);
            if (FACES) {
                commandFaces(lightFaceList, layer.chosen, view);
            }
            now = System.nanoTime();
            lightParts[3] += now - mark;
            mark = now;
            layer.lastDraws += drawDepthLayers(lightList, lightFaceList, view);
            now = System.nanoTime();
            lightParts[4] += now - mark;
            mark = now;
        }
        if (next) {
            layer.nextCubes += layer.chosen.count();
            if (plan.nextDrawn(tiles)) {
                shadow.publish(layer.index);
                plan.publish();
            }
        } else {
            layer.frontCubes += layer.chosen.count();
            plan.frontDrawn(tiles);
        }
        lightParts[5] += System.nanoTime() - mark;
    }

    /**
     * The check: draws the front's whole box into the scratch at once, every cube it holds, and sets it
     * beside the layer as drawn tile by tile. A box being drawn later clears each tile before drawing
     * it, so what is left in the scratch does no harm.
     */
    private void checkLayer(LightLayer layer) {
        LightBox box = layer.plan.front();
        View view = View.throughLight(layer.front, mainView, box);
        layer.swept.sweep(this, view);
        layer.chosen.clear();
        for (int i = 0, count = layer.swept.count(); i < count; i++) {
            long key = layer.swept.key(i);
            if (drawable(key) && box.texelsOf(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key), lightRange)) {
                layer.chosen.add(key);
            }
        }
        shadow.attach(true, layer.index);
        layer.rectangles[0] = 0;
        layer.rectangles[1] = 0;
        layer.rectangles[2] = ShadowTarget.SIDE;
        layer.rectangles[3] = ShadowTarget.SIDE;
        shadow.clear(layer.rectangles, 1);
        if (layer.chosen.count() > 0) {
            command(lightList, layer.chosen, view, false);
            if (FACES) {
                commandFaces(lightFaceList, layer.chosen, view);
            }
            drawDepthLayers(lightList, lightFaceList, view);
        }
        long[] result = shadow.compareWithScratch(layer.index);
        shadowChecks++;
        if (result[1] > 0) {
            shadowCheckFailures++;
        }
        // Tiles are drawn through matrices made from the origins of the frames they were drawn in, so a few
        // texels at the edge of a face may round the other way; a difference past a thousandth of the span
        // is something drawn that should not be, or missing.
        Quadrature.LOGGER.info("Light's depth check, {} layer: drawn whole from {} cubes beside the layer drawn by tiles, "
            + "{} of {} texels differ by more than a step of depth and {} by more than a thousandth of the span, the "
            + "largest by {} millionths of it ({} checks, {} with a difference past a thousandth).",
            layer.name, layer.chosen.count(), result[0], (long) ShadowTarget.SIDE * ShadowTarget.SIDE, result[1], result[2],
            shadowChecks, shadowCheckFailures);
        if (result[1] > 0) {
            Quadrature.LOGGER.error("LIGHT'S DEPTH CHECK FAILED: the {} layer drawn by tiles differs from the box drawn whole "
                + "in {} texels by more than a thousandth of the depth's span.", layer.name, result[1]);
        }
    }

    /** Whether a cube has a mesh in the arena, of vertices or of face records, that a light's view can draw. */
    private boolean drawable(long key) {
        int slot = slots.find(key);
        if (slot >= 0 && slots.block(slot) >= 0) {
            return true;
        }
        if (!FACES) {
            return false;
        }
        int faces = faceSlots.find(key);
        return faces >= 0 && faceSlots.block(faces) >= 0;
    }

    /** The diagnostic image, once for each place the player stands still at with nothing waiting. */
    private void image() {
        if (!SHADOW_IMAGE) {
            return;
        }
        long place = CubeKey.of((int) Math.floor(mainView.originX()), (int) Math.floor(mainView.originY()),
            (int) Math.floor(mainView.originZ()));
        if (place != stillAt || !dirty.isEmpty()) {
            stillAt = place;
            stillFrames = 0;
        } else if (++stillFrames == STILL_FRAMES && imagedAt != place) {
            imagedAt = place;
            for (int layer = 0; layer < ShadowTarget.LAYERS; layer++) {
                shadow.writeImage((int) Math.floor(mainView.originX()) + "_" + (int) Math.floor(mainView.originY()) + "_"
                    + (int) Math.floor(mainView.originZ()), layer);
            }
        }
    }

    /** Draws the opaque layers of one view's lists into the layer of the light's depth bound. */
    private int drawDepthLayers(DrawCommands list, DrawCommands faceList, View view) {
        program.uploadDepth(list);
        int draws = program.drawDepth(list, BlockRenderLayer.SOLID.ordinal(), arena, view.projection(), -1f);
        draws += program.drawDepth(list, BlockRenderLayer.CUTOUT_MIPPED.ordinal(), arena, view.projection(), cutoutReference);
        draws += program.drawDepth(list, BlockRenderLayer.CUTOUT.ordinal(), arena, view.projection(), cutoutReference);
        if (FACES) {
            program.uploadFaces(faceList, 1);
            draws += program.drawDepthFaces(faceList, BlockRenderLayer.SOLID.ordinal(), faceArena, view.projection(), -1f);
            draws += program.drawDepthFaces(faceList, BlockRenderLayer.CUTOUT_MIPPED.ordinal(), faceArena,
                view.projection(), cutoutReference);
            draws += program.drawDepthFaces(faceList, BlockRenderLayer.CUTOUT.ordinal(), faceArena, view.projection(),
                cutoutReference);
        }
        return draws;
    }

    /**
     * Writes what a view draws this frame: a record with its matrix for every cube it sees that has a
     * mesh in the arena, and a command for each layer the mesh has. Meshes outside the arena are set
     * aside for the main view to draw one by one.
     */
    private void command(DrawCommands list, CubeList seen, View view, boolean main) {
        list.begin(arena.pages());
        boolean record = main && comparison != null;
        if (main) {
            loose.clear();
            drawnVerticesThisFrame = 0;
            if (record) {
                drawnCubes.clear();
            }
        }
        double originX = view.originX();
        double originY = view.originY();
        double originZ = view.originZ();
        float[] modelView = view.modelView();
        for (int i = 0, count = seen.count(); i < count; i++) {
            long key = seen.key(i);
            int slot = slots.find(key);
            if (slot < 0) {
                continue;
            }
            long block = slots.block(slot);
            CubeMesh apart = block < 0 && looseMeshes > 0 ? meshes.get(key) : null;
            if (block < 0 && apart == null) {
                if (record && FACES) {
                    // A cube drawn from face records alone is drawn all the same.
                    int faces = faceSlots.find(key);
                    if (faces >= 0 && faceSlots.block(faces) >= 0) {
                        drawnCubes.add(key);
                    }
                }
                continue;
            }
            if (main && SelectionComparison.GAME_SELECTION != 0 && selectedCubes.contains(key)
                != (SelectionComparison.GAME_SELECTION == SelectionComparison.ONLY_SELECTED)) {
                continue;
            }
            if (record) {
                drawnCubes.add(key);
            }
            if (apart != null) {
                if (main) {
                    loose.add(apart);
                }
                continue;
            }
            CubeMatrix.compose(modelView, (float) ((CubeKey.x(key) << 4) - originX),
                (float) ((CubeKey.y(key) << 4) - originY), (float) ((CubeKey.z(key) << 4) - originZ), matrix, 0);
            int index = list.record(matrix, 0);
            int page = ArenaAllocator.page(block);
            for (int layer = 0; layer < CubeSlots.LAYERS; layer++) {
                int vertices = slots.count(slot, layer);
                if (vertices > 0) {
                    list.add(layer, page, slots.first(slot, layer), vertices, index);
                    if (main) {
                        drawnVerticesThisFrame += vertices;
                    }
                }
            }
        }
        list.finish();
    }
    /** For the selection comparison: what is known here of a cube the game draws and this did not. */
    private String explain(long key) {
        boolean seen = false;
        for (int i = 0, count = visible.count(); i < count && !seen; i++) {
            seen = visible.key(i) == key;
        }
        return (meshes.containsKey(key) ? "a mesh is held" : "no mesh is held")
            + (links.get(key) < 0 ? ", the cube is not loaded" : ", the cube is loaded")
            + (dirty.contains(key) ? ", it waits to be meshed" : "")
            + (seen ? ", the view sees it" : ", the view does not see it");
    }

    // Drawing.

    /**
     * Draws one opaque layer of what a view sees. For the player's view the block texture filtering,
     * alpha test, fog and projection are the state the game has set for that layer; a view of
     * Quadrature's own would have to set them. The light map is this stage's own business: see below.
     */
    @Override
    public void draw(BlockRenderLayer layer, View view) {
        double cameraX = view.originX();
        double cameraY = view.originY();
        double cameraZ = view.originZ();
        if (layer == BlockRenderLayer.SOLID) {
            drawsThisFrame = 0;
            if (DIRECT) {
                drawnVerticesThisFrame = 0;
            }
        }
        // The game uploads its light map every tick, which resets that texture to unfiltered sampling,
        // and puts the filter back only here, inside the draw this stage replaces. Without it a face
        // whose light coordinate lies on a texel boundary flickers between two light levels.
        net.minecraft.client.Minecraft.getMinecraft().entityRenderer.enableLightmap();
        if (SHADOW && layer == BlockRenderLayer.CUTOUT && org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_ALPHA_TEST)) {
            // The game's own threshold for a cutout layer, read where the game has set it. The light's depth
            // drops cutout texels by it, so a new one draws every tile again.
            float reference = org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_ALPHA_TEST_REF);
            if (reference != cutoutReference) {
                cutoutReference = reference;
                for (LightLayer light : lightLayers) {
                    light.plan.redrawAll();
                }
            }
        }
        DriverMessages.enter("terrain " + layer);
        program.begin(view);
        int index = layer.ordinal();
        boolean record = comparison != null && layer == BlockRenderLayer.SOLID;
        if (DIRECT) {
            if (record) {
                drawnCubes.clear();
            }
            for (int i = 0, seen = visible.count(); i < seen; i++) {
                long key = visible.key(i);
                CubeMesh mesh = meshes.get(key);
                if (mesh == null) {
                    continue;
                }
                int count = mesh.count(index);
                if (count == 0 && !record) {
                    continue;
                }
                if (SelectionComparison.GAME_SELECTION != 0 && selectedCubes.contains(key)
                    != (SelectionComparison.GAME_SELECTION == SelectionComparison.ONLY_SELECTED)) {
                    continue;
                }
                if (record) {
                    drawnCubes.add(key);
                }
                if (count == 0) {
                    continue;
                }
                program.draw(mesh, index, (float) ((mesh.x << 4) - cameraX), (float) ((mesh.y << 4) - cameraY),
                    (float) ((mesh.z << 4) - cameraZ));
                drawsThisFrame++;
                drawnVerticesThisFrame += count;
            }
        } else {
            // One draw for each page that holds any of this layer, however many cubes are seen.
            drawsThisFrame += program.drawIndirect(mainList, index, arena);
            if (FACES) {
                drawsThisFrame += program.drawFaces(mainFaceList, index, faceArena);
            }
            for (int i = 0, apart = loose.size(); i < apart; i++) {
                CubeMesh mesh = loose.get(i);
                if (mesh.count(index) > 0) {
                    program.draw(mesh, index, (float) ((mesh.x << 4) - cameraX), (float) ((mesh.y << 4) - cameraY),
                        (float) ((mesh.z << 4) - cameraZ));
                    drawsThisFrame++;
                    drawnVerticesThisFrame += mesh.count(index);
                }
            }
        }
        if (ScreenProbe.INSTANCE != null && layer == BlockRenderLayer.SOLID && dirty.isEmpty()) {
            ScreenProbe.INSTANCE.probe(meshes, selectedCubes, source, mesher, cameraX, cameraY, cameraZ);
        }
        if (ScreenProbe.INSTANCE != null) {
            ScreenProbe.INSTANCE.sample(layer.toString(), layer == BlockRenderLayer.CUTOUT);
        }
        if (TARGETS && !DIRECT) {
            if (!cc.orthrus.quadrature.depth.DepthCalls.active()) {
                if (!targetsRefused) {
                    targetsRefused = true;
                    Quadrature.LOGGER.warn("The shading targets need reversed depth, which is off: they are not drawn.");
                }
            } else {
                // The same commands and records as the frame's own draw, into targets of Quadrature's own.
                boolean first = layer == BlockRenderLayer.SOLID;
                targets.begin(first, frames);
                if (first) {
                    targetDraws = 0;
                    targetFrames++;
                }
                targetDraws += program.drawTargets(mainList, mainFaceList, index, arena, faceArena, first ? -1f : 0.5f);
                targets.end();
                if (layer == BlockRenderLayer.CUTOUT) {
                    shadingTargets.set(targets.framebuffer(), targets.texture(0), targets.texture(1), targets.texture(2), targets.texture(3),
                        targets.depthTexture(), targets.width(), targets.height(), targets.bytes(), targets.generation(),
                        targets.drawnFrame());
                    // The shading stage handed over in a folder runs after this stage, as one of its own. Where it
                    // is on, the images are written after it has run, so that its picture and the targets' are
                    // of one frame.
                    if (TARGET_IMAGES || cc.orthrus.quadrature.frame.StageHost.IMAGES) {
                        long place = CubeKey.of((int) Math.floor(cameraX), (int) Math.floor(cameraY), (int) Math.floor(cameraZ));
                        if (place != targetStillAt || !dirty.isEmpty()) {
                            targetStillAt = place;
                            targetStill = 0;
                        } else if (++targetStill == STILL_FRAMES + 240 && targetImagedAt != place) {
                            targetImagedAt = place;
                            String where = (int) Math.floor(cameraX) + "_" + (int) Math.floor(cameraY) + "_" + (int) Math.floor(cameraZ);
                            if (cc.orthrus.quadrature.frame.StageHost.REQUESTED) {
                                imagesDue = where;
                            } else if (TARGET_IMAGES) {
                                targets.writeImages(where);
                            }
                        }
                    }
                }
            }
        }
        program.end();
        DriverMessages.leave();
        net.minecraft.client.Minecraft.getMinecraft().entityRenderer.disableLightmap();
        if (arena != null && layer == BlockRenderLayer.CUTOUT) {
            // The frame's last terrain draw: what was freed up to now may be reused once this has run.
            arena.endFrame(frames);
            // After the draws, so that making a page is never in the way of placing a mesh.
            arena.prepare();
            arena.takeCreateNanos();
            if (FACES) {
                faceArena.endFrame(frames);
                faceArena.prepare();
                faceArena.takeCreateNanos();
            }
        }
        if (record && dirty.isEmpty()) {
            comparison.compare(drawnCubes, cameraX, cameraY, cameraZ, view.across(), view.upward(), this::explain);
        }
    }
    @Override
    public ShadowMaps shadowMaps() {
        return shadowMaps;
    }

    @Override
    public LightScene lightScene() {
        return scene;
    }

    @Override
    public CoverageMap coverage() {
        return coverage;
    }

    @Override
    public ShadingTargets shadingTargets() {
        return shadingTargets;
    }

    @Override
    public cc.orthrus.quadrature.core.material.MaterialTable materials() {
        return mesher.materials() == null ? null : mesher.materials().table();
    }

    @Override
    public long frame() {
        return frames;
    }

    @Override
    public int materialsBuffer() {
        if (!TARGETS) {
            return 0;
        }
        // Rows may have been filled since the frame's own upload: what is handed out holds them.
        Materials materials = mesher.materials();
        if (materialsSeen != materials.table().version()) {
            materialsSeen = materials.table().version();
            program.uploadMaterials(materials.table().bytes());
        }
        return program.materialBuffer();
    }
    @Override
    public int material(net.minecraft.block.state.IBlockState state) {
        return mesher.material(state);
    }

    @Override
    public SearchEdge searchEdge() {
        return searchEdge;
    }

    @Override
    public PublishedScene publishedScene() {
        return sceneBuffers == null ? null : sceneBuffers.published();
    }

    /**
     * How far near terrain reaches from the viewer, twice over: by the cubes held, which is what a
     * stage is told, and by what is meshed now, which is the square of chunk columns around the
     * viewer's own in which every column holds a mesh. Worked out only for the log.
     */
    private String edgeSummary() {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet columns = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (LongIterator it = meshes.keySet().iterator(); it.hasNext();) {
            long key = it.nextLong();
            columns.add((long) CubeKey.x(key) << 32 | CubeKey.z(key) & 0xFFFFFFFFL);
        }
        int originX = mainView.originCubeX();
        int originZ = mainView.originCubeZ();
        int across = mainView.across();
        // The first ring outward that has a column with no mesh: the square within it is whole.
        int whole = 0;
        rings:
        for (int ring = 0; ring <= across; ring++) {
            for (int z = -ring; z <= ring; z++) {
                boolean side = z == -ring || z == ring;
                for (int x = -ring; x <= ring; x += side || ring == 0 ? 1 : 2 * ring) {
                    if (!columns.contains((long) (originX + x) << 32 | originZ + z & 0xFFFFFFFFL)) {
                        break rings;
                    }
                }
            }
            whole = ring + 1;
        }
        return String.format("; near terrain's half side %.0f blocks by the cubes held and %.0f by the columns meshed",
            cc.orthrus.quadrature.core.view.NearEdge.blocks(originX, originZ, across, mainView.originX(), mainView.originZ()),
            whole == 0 ? 0f : cc.orthrus.quadrature.core.view.NearEdge.blocks(originX, originZ, whole, mainView.originX(),
                mainView.originZ()));
    }

    private long auditCornersLogged;

    /** The name of the images to write once the shading stage has run in this frame, or null. */
    private String imagesDue;

    @Override
    public boolean imagesDue() {
        return imagesDue != null;
    }

    @Override
    public String cubeReport(double x, double y, double z) {
        int cx = (int) Math.floor(x) >> 4;
        int cy = (int) Math.floor(y) >> 4;
        int cz = (int) Math.floor(z) >> 4;
        long key = CubeKey.of(cx, cy, cz);
        CubeMesh mesh = meshes.get(key);
        int slot = slots.find(key);
        int faceSlot = FACES ? faceSlots.find(key) : -1;
        StringBuilder out = new StringBuilder(String.format("cube %d %d %d", cx, cy, cz));
        if (mesh == null) {
            out.append(dirty.contains(key) ? ", no mesh, waiting to be meshed" : ", no mesh");
        } else {
            out.append(String.format(", mesh placed at version %d (now %d)%s, vertex slot %s, record slot %s", mesh.placedAt,
                meshVersion, mesh.inArena() ? "" : FACES ? "" : ", loose (outside the arena)",
                slot < 0 ? "none" : "block " + slots.block(slot),
                faceSlot < 0 ? "none" : "block " + faceSlots.block(faceSlot)));
            if (dirty.contains(key)) {
                out.append(", waiting to be meshed again");
            }
        }
        for (LightLayer layer : lightLayers) {
            out.append(String.format("; %s layer: %d tiles dirty, %d tiles of a new box to draw, its last sweep %s", layer.name,
                layer.plan.dirtyTiles(), layer.plan.pendingTiles(), listed(layer.swept, key) ? "lists it" : "does not list it"));
        }
        out.append(listed(visible, key) ? "; the main view sees it" : "; the main view does not see it");
        return out.toString();
    }

    private static boolean listed(CubeList seen, long key) {
        for (int i = 0, count = seen.count(); i < count; i++) {
            if (seen.key(i) == key) {
                return true;
            }
        }
        return false;
    }

    /** The cubes the wide layer's last sweep found, or -1 where the light's depth is not drawn. */
    @Override
    public int lightViewCubes() {
        return lightLayers.length == 0 ? -1 : lightLayers[lightLayers.length - 1].swept.count();
    }

    /** Writes the images that waited for the shading stage: the targets' and the stage's picture, of one frame. */
    @Override
    public void writeImagesDue() {
        String where = imagesDue;
        if (where == null) {
            return;
        }
        imagesDue = null;
        if (TARGET_IMAGES) {
            targets.writeImages(where);
        }
        if (cc.orthrus.quadrature.frame.StageHost.IMAGES) {
            cc.orthrus.quadrature.frame.StageHost.writeImage(where);
        }
    }

    @Override
    public String summary() {
        String line = String.format("terrain: %d meshes, %d waiting, %d built (%.3f ms each), %d empty, "
                + "%d left for a neighbor, %.1f MiB, %d M vertices held; last frame %d draws, %d k vertices; "
                + "main view %d cubes seen, %.3f ms last frame, %.3f ms a search over %d searches, "
                + "%d of %d frames kept the last search, %d swept instead",
            meshes.size(), dirty.size(), built,
            built == 0 ? 0.0 : buildNanos / 1e6 / built, emptied, waiting, bytes / 1048576.0, vertices / 1_000_000,
            drawsThisFrame, drawnVerticesThisFrame / 1000, visible.count(), lastSearchNanos / 1e6,
            searches == 0 ? 0.0 : searchNanos / 1e6 / searches, searches, reused, frames, fellBack);
        if (arena == null) {
            return line + String.format("; own buffers; placing %.3f ms each, %.3f ms last frame, %.3f ms most",
                placed == 0 ? 0.0 : placeNanos / 1e6 / placed, lastPlaceNanos / 1e6, mostPlaceNanos / 1e6);
        }
        line += String.format("; arena %d pages, %.1f MiB capacity, %.1f MiB in use, %.1f MiB wasted by size "
                + "classes, %.1f MiB freed and not yet reusable, %.1f MiB reusable, %d fences open, %d meshes "
                + "refused; placing %.3f ms each, %.3f ms last frame, %.3f ms most",
            arena.pages(), arena.capacityBytes() / 1048576.0, arena.usedBytes() / 1048576.0,
            arena.wastedBytes() / 1048576.0, arena.pendingBytes() / 1048576.0, arena.reusableBytes() / 1048576.0,
            arena.openFences(), refused, placed == 0 ? 0.0 : placeNanos / 1e6 / placed, lastPlaceNanos / 1e6,
            mostPlaceNanos / 1e6);
        line += String.format("; the frame that placed longest held %d meshes, %.1f KiB, the slowest %.3f ms; "
                + "%d frames stopped by the placing budget; pages made ahead %d, in the way %d, %.3f ms each, "
                + "%.3f ms most, %.3f ms most in the way in a frame",
            worstPlaced, worstPlacedBytes / 1024.0, worstLargestPlaceNanos / 1e6, stoppedByPlacing,
            arena.pagesAhead(), arena.pagesInTheWay(), arena.createMillisEach(), arena.createMillisMost(),
            mostMadeInTheWayNanos / 1e6);
        line += String.format("; %d pages handed out from, largest free range %.1f MiB, %d free ranges, %s",
            arena.pagesInUse(), arena.largestRoomBytes() / 1048576.0, arena.freeRanges(),
            TerrainArena.JOINING ? "blocks joined" : "blocks by size class");
        line += TerrainArena.COPY ? ", meshes staged and copied" : ", meshes written into their pages";
        line += String.format("; the search %s the edge of the box: faces %s, %d steps, through %d cubes not loaded",
            searchEdge.searched() ? searchEdge.faces() != 0 ? "came to" : "did not come to" : "was not made, so nothing is known of",
            Integer.toBinaryString(64 | searchEdge.faces()).substring(1), searchEdge.steps(), searchEdge.unloaded());
        if (FACE_CENSUS) {
            line += String.format("; of %d quads meshed, %d are a whole face of a cell (%.1f%%) and %d of those have "
                    + "rectangular texture coordinates (%.1f%%)", faceCensus[0], faceCensus[1],
                100.0 * faceCensus[1] / Math.max(1, faceCensus[0]), faceCensus[2],
                100.0 * faceCensus[2] / Math.max(1, faceCensus[0]));
        }
        if (CubeMesher.FACE_CHECK || FACES) {
            line += "; " + mesher.faceSummary();
            FaceBuilder built = mesher.faceBuilder();
            if (built != null && built.quads() >= FACE_SHARE_QUADS && built.recordShare() < FACE_SHARE_LEAST) {
                // Terrain is mostly whole faces: so few records means the builder refuses what it should take.
                Quadrature.LOGGER.error("FACE RECORDS FAILED: {} of quads became records where at least {} is "
                    + "expected; what is drawn is the game's vertices, and drawing from records is not exercised.",
                    String.format("%.1f%%", 100 * built.recordShare()), String.format("%.0f%%", 100 * FACE_SHARE_LEAST));
            }
        }
        line += cc.orthrus.quadrature.frame.StageHost.summary();
        line += edgeSummary();
        if (QuadWalk.OCCLUSION != null) {
            line += "; " + QuadWalk.OCCLUSION.summary() + "; quads kept with no state " + FaceBuilder.UNSTATED.get();
            if (QuadWalk.OCCLUSION.corners() != auditCornersLogged) {
                // The whole table when it has changed, a line for each way a face is turned.
                auditCornersLogged = QuadWalk.OCCLUSION.corners();
                Quadrature.LOGGER.info("Occlusion audit, corners nearest each tenth from 0 to 1:{}", QuadWalk.OCCLUSION.table());
            }
        }
        if (TARGETS) {
            line += String.format("; shading targets %d by %d, %.1f MiB, drawn in %d frames, %d draws last frame%s",
                targets.width(), targets.height(), targets.bytes() / 1048576.0, targetFrames, targetDraws,
                targetsRefused ? ", refused for want of reversed depth" : "");
        }
        if (FACES) {
            line += String.format("; records in %d pages, %.1f MiB capacity, %.1f MiB in use, %d commands", faceArena.pages(),
                faceArena.capacityBytes() / 1048576.0, faceArena.usedBytes() / 1048576.0, mainFaceList.commandCount());
        }
        if (COVERAGE) {
            line += String.format("; coverage of %d columns: %d surface meshed, %d surface waiting, %d no surface, "
                    + "%d not loaded, generation %d, worked out %d times, %.3f ms last, %.3f ms mean, %.3f ms most, "
                    + "%d cubes that cannot be meshed asked whether they are empty",
                coverage.side() * coverage.side(), coverage.count(CoverageMap.SURFACE_MESHED),
                coverage.count(CoverageMap.SURFACE_WAITING), coverage.count(CoverageMap.NO_SURFACE),
                coverage.count(CoverageMap.NOT_LOADED), coverage.generation(), coverages, lastCoverageNanos / 1e6,
                coverages == 0 ? 0.0 : coverageNanos / 1e6 / coverages, mostCoverageNanos / 1e6, emptinessAsked);
            line += String.format("; heights asked beside a mesh %d times and in the pass %d times, the pass's cap reached %d times",
                heightsAtMesh, heightsInPass, heightCapTrips);
            line += String.format("; the map filled anew %d times and brought up to date %d; %d columns with their surface meshed "
                    + "have no answer from the cube source%s", coveragesAnew, coverages - coveragesAnew, coverage.meshedWithoutBand(),
                COVERAGE_CHECK ? String.format("; checked against a map filled anew %d times, %d differed", coverageChecks,
                    coverageCheckFailures) : "");
            line += String.format("; of the mean, %.3f ms the scan, %.3f ms looking at kept heights, %.3f ms asking for heights",
                coverages == 0 ? 0.0 : coverageScanNanos / 1e6 / coverages, coverages == 0 ? 0.0 : coverageBandNanos / 1e6 / coverages,
                coverages == 0 ? 0.0 : coverageAskNanos / 1e6 / coverages);
            if (surfaceHeights != null && surfaceHeights.present()) {
                line += "; " + surfaceHeights.summary();
            }
        }
        if (SCENE) {
            line += String.format("; scene %d cubes of %d loaded, %d with cells of their own, %.1f MiB, version %d",
                scene.cubes(), links.size(), scene.mixedCubes(), scene.cellBytes() / 1048576.0, scene.version());
        }
        if (SCENE_GPU) {
            line += sceneBuffers.summary();
        }
        if (SHADOW) {
            line += String.format("; light's depth %d texels a side, %d MiB and as much of scratch, in tiles of %d texels, "
                    + "a new box over about %d frames; drawn in %d of %d frames, CPU %.3f ms last frame that drew, %.3f ms mean, "
                    + "%.3f ms most, GPU %.3f ms last, %.3f ms mean, %.3f ms most, %.3f ms a frame over all frames, "
                    + "generation %d, content generation %d, cutout alpha %.2f",
                ShadowTarget.SIDE, ShadowTarget.BYTES >> 20, ShadowTarget.SIDE / LightTiles.ACROSS, SHADOW_SPREAD, shadowFrames,
                lightFrames, lastShadowNanos / 1e6, shadowFrames == 0 ? 0.0 : shadowNanos / 1e6 / shadowFrames,
                mostShadowNanos / 1e6, shadow.gpuMillisLast(), shadow.gpuMillisMean(), shadow.gpuMillisMost(),
                shadow.gpuMillisTotal() / Math.max(1, lightFrames), shadowMaps.generation(), shadowMaps.contentGeneration(),
                cutoutReference);
            line += String.format("; since the last line, most CPU %.3f ms (frame %d) and most GPU %.3f ms (frame %d); frames over 1 ms "
                    + "of CPU %d, over 1 ms of GPU %d, over 2 ms %d", mostShadowNanosSince / 1e6, mostShadowFrameSince,
                shadow.takeGpuMillisMostSince(), shadow.gpuMostFrameSince(), shadowCpuOverOne, shadow.gpuFramesOverOne(),
                shadow.gpuFramesOverTwo());
            double frames = Math.max(1, shadowFrames);
            line += String.format("; CPU a frame that drew, mean: sweep %.3f ms, tiles %.3f ms, clears %.3f ms, commands %.3f ms, "
                    + "draws %.3f ms, copies %.3f ms, state and timers %.3f ms; the slowest since the last line: %.3f, %.3f, %.3f, "
                    + "%.3f, %.3f, %.3f, %.3f ms",
                lightPartsTotal[0] / 1e6 / frames, lightPartsTotal[1] / 1e6 / frames, lightPartsTotal[2] / 1e6 / frames,
                lightPartsTotal[3] / 1e6 / frames, lightPartsTotal[4] / 1e6 / frames, lightPartsTotal[5] / 1e6 / frames,
                lightPartsTotal[6] / 1e6 / frames, lightPartsWorst[0] / 1e6, lightPartsWorst[1] / 1e6, lightPartsWorst[2] / 1e6,
                lightPartsWorst[3] / 1e6, lightPartsWorst[4] / 1e6, lightPartsWorst[5] / 1e6, lightPartsWorst[6] / 1e6);
            mostShadowNanosSince = 0;
            java.util.Arrays.fill(lightPartsWorst, 0);
            for (LightLayer layer : lightLayers) {
                LightLayerPlan plan = layer.plan;
                LightBox box = plan.front() != null ? plan.front() : plan.next();
                line += String.format("; %s layer %.0f blocks across and %.0f deep, %.3f blocks a texel; boxes begun: %d first, "
                        + "%d for a new reach, %d for the light turning, %d for the player moving on, %d begun again where "
                        + "they had gone, %d published; %d tiles drawn into new boxes over %d frames, %d drawn again in place; "
                        + "%d cubes drawn into new boxes and %d in place; last sweep %d cubes, %d draws; now %d tiles dirty, "
                        + "%d of a new box to draw",
                    layer.name, box == null ? 0 : box.side(), box == null ? 0 : box.depth(), box == null ? 0 : box.texel(),
                    plan.begun(LightLayerPlan.Cause.FIRST), plan.begun(LightLayerPlan.Cause.SIZE),
                    plan.begun(LightLayerPlan.Cause.TURN), plan.begun(LightLayerPlan.Cause.MOVE), plan.restarts(),
                    plan.published(), plan.nextTiles(), plan.nextFrames(), plan.frontTiles(), layer.nextCubes,
                    layer.frontCubes, layer.swept.count(), layer.lastDraws, plan.dirtyTiles(), plan.pendingTiles());
            }
        }
        line += TerrainArena.TOUCH ? ", pages written once when made" : "";
        if (DIRECT) {
            return line + "; direct draws";
        }
        return line + String.format("; commands %d in %.3f ms, %d records", mainList.commandCount(),
            lastCommandNanos / 1e6, mainList.recordCount());
    }
}
