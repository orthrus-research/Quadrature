package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.terrain.CubeConnectivity;
import cc.orthrus.quadrature.core.terrain.CubeKey;
import cc.orthrus.quadrature.core.terrain.CubeVisitor;
import cc.orthrus.quadrature.core.terrain.Visibility;
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
 * <p>Off unless {@code -Dquadrature.terrain=own}. Everything here runs on the client thread.
 */
public final class Terrain
    implements ICubeSourceListener, TerrainHook.Drawer, Visibility.Cubes, Visibility.CubeSet {

    private static final long BUILD_BUDGET_NANOS = 4_000_000L;
    private static final int CANDIDATES = 24;
    private static final double NEAR_BLOCKS_SQUARED = 576.0;
    /**
     * A diagnostic, off unless {@code -Dquadrature.terrain.visibility=all}: every meshed cube in the
     * view's volume and reach is drawn, with no search, to set beside the search's frame and cost.
     */
    private static final boolean DRAW_ALL = "all".equals(System.getProperty("quadrature.terrain.visibility"));

    private final ICubeSource source;
    private final Long2ObjectOpenHashMap<CubeMesh> meshes = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    private final CubeMesher mesher = new CubeMesher();
    private final TerrainProgram program = new TerrainProgram();
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
            tableVersion++;
        }
    }

    @Override
    public void cubeUnloaded(int x, int y, int z) {
        long key = CubeKey.of(x, y, z);
        dirty.remove(key);
        release(meshes.remove(key));
        links.remove(key);
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
        links.clear();
        tableVersion++;
    }

    private void release(CubeMesh mesh) {
        if (mesh != null) {
            vertices -= mesh.vertices();
            bytes -= mesh.bytes();
            mesh.delete();
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
        CubeMesh mesh = mesher.build(snapshot, x, y, z);
        snapshot.release();
        setLinks(key, mesher.links());
        buildNanos += System.nanoTime() - start;
        built++;
        if (mesh != null) {
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
        build(mainView.originX(), mainView.originY(), mainView.originZ());
        frames++;
        long start = System.nanoTime();
        if (DRAW_ALL) {
            visible.sweep(this, mainView);
        } else if (searchedVersion == tableVersion && mainView.sameVolume(searchedView)) {
            reused++;
        } else if (visible.search(this, mainView) < 0) {
            // The eye's cube is not held: nothing is known, so nothing is left out.
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
        MainView.alongLight(lightView, mainView, partialTicks);
        lit.sweep(this, lightView);
        lastLightNanos = System.nanoTime() - start;
        return mainView;
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
            drawnVerticesThisFrame = 0;
            if (selectedCubes != null) {
                // Once a frame: the game has finished its search by the time it draws the first layer.
                SelectionComparison.selected(selectedCubes);
            }
        }
        // The game uploads its light map every tick, which resets that texture to unfiltered sampling,
        // and puts the filter back only here, inside the draw this stage replaces. Without it a face
        // whose light coordinate lies on a texel boundary flickers between two light levels.
        net.minecraft.client.Minecraft.getMinecraft().entityRenderer.enableLightmap();
        program.begin(view);
        int index = layer.ordinal();
        boolean record = comparison != null && layer == BlockRenderLayer.SOLID;
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
        if (ScreenProbe.INSTANCE != null && layer == BlockRenderLayer.SOLID && dirty.isEmpty()) {
            ScreenProbe.INSTANCE.probe(meshes, selectedCubes, source, mesher, cameraX, cameraY, cameraZ);
        }
        if (ScreenProbe.INSTANCE != null) {
            ScreenProbe.INSTANCE.sample(layer.toString(), layer == BlockRenderLayer.CUTOUT);
        }
        program.end();
        net.minecraft.client.Minecraft.getMinecraft().entityRenderer.disableLightmap();
        if (record && dirty.isEmpty()) {
            comparison.compare(drawnCubes, cameraX, cameraY, cameraZ, view.across(), view.upward());
        }
    }
    @Override
    public String summary() {
        return String.format("terrain: %d meshes, %d waiting, %d built (%.3f ms each), %d empty, "
                + "%d left for a neighbor, %.1f MiB, %d M vertices held; last frame %d draws, %d k vertices; "
                + "main view %d cubes seen, %.3f ms last frame, %.3f ms a search over %d searches, "
                + "%d of %d frames kept the last search, %d swept instead; light view %d cubes, %.3f ms",
            meshes.size(), dirty.size(), built,
            built == 0 ? 0.0 : buildNanos / 1e6 / built, emptied, waiting, bytes / 1048576.0, vertices / 1_000_000,
            drawsThisFrame, drawnVerticesThisFrame / 1000, visible.count(), lastSearchNanos / 1e6,
            searches == 0 ? 0.0 : searchNanos / 1e6 / searches, searches, reused, frames, fellBack, lit.count(),
            lastLightNanos / 1e6);
    }
}
