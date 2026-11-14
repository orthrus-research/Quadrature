package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.terrain.CubeKey;

import io.github.opencubicchunks.cubicchunks.api.client.CubeSource;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;
import io.github.opencubicchunks.cubicchunks.api.client.ICubeSourceListener;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;

/**
 * Near terrain, first form: every loaded cube is meshed from a snapshot of the cube source with the
 * game's own block models, on the client thread within a time budget, nearest first, and the three
 * opaque layers are drawn from those meshes in place of the game's draw. Translucent terrain and
 * block entities are still the game's.
 *
 * <p>Off unless {@code -Dquadrature.terrain=own}. Everything here runs on the client thread.
 */
public final class Terrain implements ICubeSourceListener, TerrainHook.Drawer {

    private static final long BUILD_BUDGET_NANOS = 4_000_000L;
    private static final int CANDIDATES = 24;

    private final ICubeSource source;
    private final Long2ObjectOpenHashMap<CubeMesh> meshes = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
    private final CubeMesher mesher = new CubeMesher();
    private final TerrainProgram program = new TerrainProgram();
    private final Frustum frustum = new Frustum();
    private final long[] nearest = new long[CANDIDATES];
    private final double[] nearestDistance = new double[CANDIDATES];
    private boolean attached;

    // Counters for the timing log.
    private long built;
    private long buildNanos;
    private long emptied;
    private long vertices;
    private long bytes;
    private long drawsThisFrame;
    private long drawnVerticesThisFrame;

    private Terrain(ICubeSource source) {
        this.source = source;
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
        source.forEachLoaded((x, y, z) -> dirty.add(CubeKey.of(x, y, z)));
    }

    @Override
    public void worldDetached(long worldIdentity) {
        clear();
        attached = false;
    }

    @Override
    public void cubeLoaded(int x, int y, int z) {
        dirty.add(CubeKey.of(x, y, z));
    }

    @Override
    public void cubeUnloaded(int x, int y, int z) {
        long key = CubeKey.of(x, y, z);
        dirty.remove(key);
        release(meshes.remove(key));
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
        release(meshes.remove(key));
        if (!source.isLoaded(x, y, z)) {
            return;
        }
        ICubeSnapshot snapshot = source.snapshot(x, y, z);
        if (snapshot.centerEmpty()) {
            emptied++;
            snapshot.release();
            return;
        }
        long start = System.nanoTime();
        CubeMesh mesh = mesher.build(snapshot, x, y, z);
        snapshot.release();
        buildNanos += System.nanoTime() - start;
        built++;
        if (mesh != null) {
            meshes.put(key, mesh);
            vertices += mesh.vertices();
            bytes += mesh.bytes();
        }
    }

    // Drawing.

    /**
     * Draws one opaque layer, under the block texture filtering, alpha test and fog state the game
     * has set for that layer. The light map is this stage's own business: see below.
     */
    @Override
    public void draw(BlockRenderLayer layer, double partialTicks, Entity viewer) {
        double cameraX = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * partialTicks;
        double cameraY = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * partialTicks;
        double cameraZ = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * partialTicks;
        if (layer == BlockRenderLayer.SOLID) {
            build(cameraX, cameraY, cameraZ);
            drawsThisFrame = 0;
            drawnVerticesThisFrame = 0;
        }
        frustum.setPosition(cameraX, cameraY, cameraZ);
        // The game uploads its light map every tick, which resets that texture to unfiltered sampling,
        // and puts the filter back only here, inside the draw this stage replaces. Without it a face
        // whose light coordinate lies on a texel boundary flickers between two light levels.
        net.minecraft.client.Minecraft.getMinecraft().entityRenderer.enableLightmap();
        program.begin();
        int index = layer.ordinal();
        // The game's own bound, kept so the two draw the same cubes: from the eye's block to the cube's
        // lowest corner, at most the render distance in blocks on each axis. It reaches one cube
        // farther toward positive coordinates than toward negative ones.
        int eyeX = (int) Math.floor(cameraX);
        int eyeY = (int) Math.floor(cameraY + viewer.getEyeHeight());
        int eyeZ = (int) Math.floor(cameraZ);
        net.minecraft.client.settings.GameSettings settings = net.minecraft.client.Minecraft.getMinecraft().gameSettings;
        int across = settings.renderDistanceChunks * 16;
        int upward = verticalRenderDistance() * 16;
        for (CubeMesh mesh : meshes.values()) {
            int count = mesh.count(index);
            if (count == 0) {
                continue;
            }
            int minX = mesh.x << 4;
            int minY = mesh.y << 4;
            int minZ = mesh.z << 4;
            if (Math.abs(eyeX - minX) > across || Math.abs(eyeY - minY) > upward || Math.abs(eyeZ - minZ) > across) {
                continue;
            }
            if (!frustum.isBoxInFrustum(minX, minY, minZ, minX + 16, minY + 16, minZ + 16)) {
                continue;
            }
            program.draw(mesh, index, (float) (minX - cameraX), (float) (minY - cameraY), (float) (minZ - cameraZ));
            drawsThisFrame++;
            drawnVerticesThisFrame += count;
        }
        program.end();
        net.minecraft.client.Minecraft.getMinecraft().entityRenderer.disableLightmap();
    }

    /** Cubature's vertical render distance in cubes, which the game's video settings show beside its own. */
    private static int verticalRenderDistance() {
        return io.github.opencubicchunks.cubicchunks.core.CubicChunksConfig.verticalCubeLoadDistance;
    }

    @Override
    public String summary() {
        return String.format("terrain: %d meshes, %d waiting, %d built (%.3f ms each), %d empty, %.1f MiB, "
                + "%d M vertices held; last frame %d draws, %d k vertices", meshes.size(), dirty.size(), built,
            built == 0 ? 0.0 : buildNanos / 1e6 / built, emptied, bytes / 1048576.0, vertices / 1_000_000,
            drawsThisFrame, drawnVerticesThisFrame / 1000);
    }
}
