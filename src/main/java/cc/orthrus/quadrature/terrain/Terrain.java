package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.terrain.ArenaAllocator;
import cc.orthrus.quadrature.core.terrain.CubeConnectivity;
import cc.orthrus.quadrature.core.terrain.CubeKey;
import cc.orthrus.quadrature.core.terrain.CubeMatrix;
import cc.orthrus.quadrature.core.terrain.CubeSlots;
import cc.orthrus.quadrature.core.terrain.CubeVisitor;
import cc.orthrus.quadrature.core.terrain.DrawCommands;
import cc.orthrus.quadrature.core.terrain.Visibility;
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
    private static final int STILL_FRAMES = 960;

    private final ICubeSource source;
    private final Long2ObjectOpenHashMap<CubeMesh> meshes = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    private final CubeMesher mesher = new CubeMesher();
    private final TerrainProgram program = new TerrainProgram();
    private final TerrainArena arena = OWN_BUFFERS ? null : new TerrainArena();
    /** A slot for every loaded cube, holding where its mesh lies in the arena. */
    private final CubeSlots slots = new CubeSlots();
    private final DrawCommands mainList = new DrawCommands();
    private final DrawCommands lightList = new DrawCommands();
    private final ShadowTarget shadow = SHADOW ? new ShadowTarget() : null;
    /** What is published of the depth drawn along the light, or null when it is not drawn. */
    private final ShadowMaps shadowMaps = SHADOW
        ? new ShadowMaps(ShadowTarget.LAYERS, ShadowTarget.SIDE, ShadowTarget.BYTES) : null;
    private final double[] towardLight = new double[3];
    private double lightSide;
    /** Counts meshes placed and released: what a view draws may differ once it has moved on. */
    private long meshVersion;
    private long shadowMeshVersion = -1;
    private double shadowOriginX;
    private double shadowOriginY;
    private double shadowOriginZ;
    /** The alpha at or below which the game drops a fragment of a cutout layer, as last seen set. */
    private float cutoutReference = 0.1f;
    private long shadowNanos;
    private long lastShadowNanos;
    private long mostShadowNanos;
    private long shadowFrames;
    private int shadowDraws;
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
    private final View lightView = new View();
    private final Visibility visible = new Visibility();
    private final Visibility lit = new Visibility();
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
    private long lastLightNanos;
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
    private long refused;
    private long lastCommandNanos;
    private long lastLightCommandNanos;

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
    }

    @Override
    public void worldDetached(long worldIdentity) {
        clear();
        attached = false;
    }

    @Override
    public void cubeLoaded(int x, int y, int z) {
        long key = CubeKey.of(x, y, z);
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
        dirty.remove(key);
        release(meshes.remove(key));
        links.remove(key);
        slots.release(key);
        tableVersion++;
    }

    @Override
    public void cubeChanged(int x, int y, int z, int kinds, int minX, int minY, int minZ, int maxX, int maxY,
        int maxZ) {
        dirty.add(CubeKey.of(x, y, z));
    }

    private void clear() {
        for (CubeMesh mesh : meshes.values()) {
            release(mesh);
        }
        meshes.clear();
        dirty.clear();
        for (LongIterator it = links.keySet().iterator(); it.hasNext();) {
            slots.release(it.nextLong());
        }
        links.clear();
        tableVersion++;
    }

    private void release(CubeMesh mesh) {
        if (mesh != null) {
            meshVersion++;
            vertices -= mesh.vertices();
            bytes -= mesh.bytes();
            if (mesh.inArena()) {
                int slot = slots.find(CubeKey.of(mesh.x, mesh.y, mesh.z));
                if (slot >= 0) {
                    slots.clear(slot);
                }
                arena.free(mesh, frames);
            } else {
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
        int x = CubeKey.x(key);
        int y = CubeKey.y(key);
        int z = CubeKey.z(key);
        if (!source.isLoaded(x, y, z)) {
            release(meshes.remove(key));
            links.remove(key);
            slots.release(key);
            tableVersion++;
            return;
        }
        if (!surrounded(x, y, z)) {
            // As in the game, a cube that cannot be meshed now keeps the mesh it has.
            return;
        }
        release(meshes.remove(key));
        ICubeSnapshot snapshot = source.snapshot(x, y, z);
        if (snapshot.centerEmpty()) {
            emptied++;
            snapshot.release();
            setLinks(key, CubeConnectivity.ALL);
            return;
        }
        long start = System.nanoTime();
        boolean any = mesher.build(snapshot, x, y, z);
        snapshot.release();
        setLinks(key, mesher.links());
        CubeMesh mesh = null;
        if (any) {
            long placing = System.nanoTime();
            mesh = arena == null ? null : arena.place(x, y, z, mesher, frames);
            if (mesh == null) {
                // No arena, or no room in it: the mesh keeps buffers of its own and is drawn by itself.
                mesh = CubeMesh.owned(x, y, z, mesher);
                if (arena != null) {
                    refused++;
                    looseMeshes++;
                }
            } else {
                slots.set(slots.acquire(key), mesh.block, mesh.blockBytes, mesh.first(0), mesh.count(0),
                    mesh.first(1), mesh.count(1), mesh.first(2), mesh.count(2));
            }
            placing = System.nanoTime() - placing;
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
            meshes.put(key, mesh);
            vertices += mesh.vertices();
            bytes += mesh.bytes();
        }
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

        // A second view, found and not yet drawn: what a shadow of the sun or moon would be drawn from.
        start = System.nanoTime();
        if (SHADOW) {
            lightSide = MainView.alongLight(lightView, mainView, partialTicks, ShadowTarget.SIDE, towardLight);
        } else {
            MainView.alongLight(lightView, mainView, partialTicks);
        }
        lit.sweep(this, lightView);
        lastLightNanos = System.nanoTime() - start;

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
            // The second view's commands are written as the first's are, and not handed to the driver.
            start = System.nanoTime();
            command(lightList, lit, lightView, false);
            lastLightCommandNanos = System.nanoTime() - start;
            if (SHADOW) {
                drawShadow();
            }
        }
        return mainView;
    }

    /**
     * Draws the view along the light for depth alone: the opaque layers of every meshed cube in its
     * volume, from the same pages and by the same indirect draws as the player's view, each cube under
     * the same matrix composition. The cutout layers are tested against the block texture's alpha.
     * Entities are not drawn into it.
     */
    private void drawShadow() {
        long start = System.nanoTime();
        DriverMessages.enter("shadow");
        program.uploadDepth(lightList);
        shadow.begin();
        shadowMaps.objects(shadow.texture(), shadow.compareSampler(), shadow.depthSampler());
        shadowMaps.polygonOffset(ShadowTarget.POLYGON_OFFSET[0], ShadowTarget.POLYGON_OFFSET[1]);
        shadowMaps.beginFrame(mainView.originX(), mainView.originY(), mainView.originZ(), towardLight[0],
            towardLight[1], towardLight[2]);
        // The box moved to another texel if the player's old and new positions fall in different
        // texels along the light's axes as they stand now.
        float[] axes = lightView.modelView();
        double texel = lightSide / ShadowTarget.SIDE;
        boolean resnapped = false;
        for (int axis = 0; axis < 3 && !resnapped; axis++) {
            double now = axes[axis] * mainView.originX() + axes[4 + axis] * mainView.originY()
                + axes[8 + axis] * mainView.originZ();
            double before = axes[axis] * shadowOriginX + axes[4 + axis] * shadowOriginY + axes[8 + axis] * shadowOriginZ;
            resnapped = Math.rint(now / texel) != Math.rint(before / texel);
        }
        shadowOriginX = mainView.originX();
        shadowOriginY = mainView.originY();
        shadowOriginZ = mainView.originZ();
        shadowMaps.layer(0, lightView, lightSide, shadowMeshVersion != meshVersion || resnapped);
        shadowMeshVersion = meshVersion;
        shadowDraws = program.drawDepth(lightList, BlockRenderLayer.SOLID.ordinal(), arena, lightView.projection(), -1f);
        shadowDraws += program.drawDepth(lightList, BlockRenderLayer.CUTOUT_MIPPED.ordinal(), arena,
            lightView.projection(), cutoutReference);
        shadowDraws += program.drawDepth(lightList, BlockRenderLayer.CUTOUT.ordinal(), arena, lightView.projection(),
            cutoutReference);
        shadow.end();
        program.end();
        DriverMessages.leave();
        lastShadowNanos = System.nanoTime() - start;
        shadowNanos += lastShadowNanos;
        mostShadowNanos = Math.max(mostShadowNanos, lastShadowNanos);
        shadowFrames++;
        if (SHADOW_IMAGE) {
            long place = CubeKey.of((int) Math.floor(mainView.originX()), (int) Math.floor(mainView.originY()),
                (int) Math.floor(mainView.originZ()));
            if (place != stillAt || !dirty.isEmpty()) {
                stillAt = place;
                stillFrames = 0;
            } else if (++stillFrames == STILL_FRAMES && imagedAt != place) {
                imagedAt = place;
                shadow.writeImage((int) Math.floor(mainView.originX()) + "_" + (int) Math.floor(mainView.originY()) + "_"
                    + (int) Math.floor(mainView.originZ()));
            }
        }
    }

    /**
     * Writes what a view draws this frame: a record with its matrix for every cube it sees that has a
     * mesh in the arena, and a command for each layer the mesh has. Meshes outside the arena are set
     * aside for the main view to draw one by one.
     */
    private void command(DrawCommands list, Visibility seen, View view, boolean main) {
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
            // The game's own threshold for a cutout layer, read where the game has set it.
            cutoutReference = org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_ALPHA_TEST_REF);
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
        program.end();
        DriverMessages.leave();
        net.minecraft.client.Minecraft.getMinecraft().entityRenderer.disableLightmap();
        if (arena != null && layer == BlockRenderLayer.CUTOUT) {
            // The frame's last terrain draw: what was freed up to now may be reused once this has run.
            arena.endFrame(frames);
            // After the draws, so that making a page is never in the way of placing a mesh.
            arena.prepare();
            arena.takeCreateNanos();
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
    public String summary() {
        String line = String.format("terrain: %d meshes, %d waiting, %d built (%.3f ms each), %d empty, "
                + "%d left for a neighbor, %.1f MiB, %d M vertices held; last frame %d draws, %d k vertices; "
                + "main view %d cubes seen, %.3f ms last frame, %.3f ms a search over %d searches, "
                + "%d of %d frames kept the last search, %d swept instead; light view %d cubes, %.3f ms",
            meshes.size(), dirty.size(), built,
            built == 0 ? 0.0 : buildNanos / 1e6 / built, emptied, waiting, bytes / 1048576.0, vertices / 1_000_000,
            drawsThisFrame, drawnVerticesThisFrame / 1000, visible.count(), lastSearchNanos / 1e6,
            searches == 0 ? 0.0 : searchNanos / 1e6 / searches, searches, reused, frames, fellBack, lit.count(),
            lastLightNanos / 1e6);
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
        if (SHADOW) {
            float[] box = lightView.projection();
            line += String.format("; shadow %d texels a side, %d MiB, %.3f blocks a texel, %d cubes, %d commands, "
                    + "%d draws, CPU %.3f ms last frame, %.3f ms mean, %.3f ms most, GPU %.3f ms last, %.3f ms mean, "
                    + "%.3f ms most, cutout alpha %.2f",
                ShadowTarget.SIDE, ShadowTarget.BYTES >> 20, box[0] == 0 ? 0.0 : 2.0 / box[0] / ShadowTarget.SIDE,
                lit.count(), lightList.commandCount(), shadowDraws, lastShadowNanos / 1e6,
                shadowFrames == 0 ? 0.0 : shadowNanos / 1e6 / shadowFrames, mostShadowNanos / 1e6,
                shadow.gpuMillisLast(), shadow.gpuMillisMean(), shadow.gpuMillisMost(), cutoutReference);
            line += String.format(", box %.0f blocks, generation %d and content generation %d of %d frames", lightSide,
                shadowMaps.generation(), shadowMaps.contentGeneration(), shadowFrames);
        }
        line += TerrainArena.TOUCH ? ", pages written once when made" : "";
        if (DIRECT) {
            return line + "; direct draws";
        }
        return line + String.format("; commands %d in %.3f ms, %d records; light view commands %d in %.3f ms",
            mainList.commandCount(), lastCommandNanos / 1e6, mainList.recordCount(), lightList.commandCount(),
            lastLightCommandNanos / 1e6);
    }
}