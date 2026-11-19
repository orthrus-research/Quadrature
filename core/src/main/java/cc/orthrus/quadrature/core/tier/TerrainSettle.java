package cc.orthrus.quadrature.core.tier;

/**
 * Whether near terrain has caught up with the view, so the benchmark may draw it: nothing waits to be
 * meshed, the count of meshes held stays the same and above none, and the view's search meets no cube
 * that is not loaded. A place still arriving can hold its meshes for seconds between batches, which the
 * count alone does not show; the search's count of unloaded cubes falls all the while.
 *
 * <p>The search counts what lies in the view's box, which is the client's own distances. A server that
 * loads less, a lower view distance on a remote server among them, leaves cubes in the box that never
 * come, so a count that has stood unchanged for {@link #PLATEAU_NANOS} counts as caught up as well. A
 * place where nothing has arrived also stands still for a while: the benchmark's floor of primitives
 * ({@link TierBenchmark#LEAST_PRIMITIVES}) turns that run away.
 *
 * <p>Where the light scene is copied to the GPU, which the trace probe walks, the copy must have caught up
 * too: nothing left to take from the scene or copy, and some bricks held. Its copying trails the meshes,
 * and after one jump of the view it held no brick for 35 s while near terrain stood caught up, so the
 * probe's rays left the scene at once and read eleven times as fast.
 */
public final class TerrainSettle {

    /** How long the count of unloaded cubes must stand still to say the rest will not come. */
    public static final long PLATEAU_NANOS = 15_000_000_000L;

    private int lastHeld = -1;
    private int lastUnloaded = -1;
    private long unloadedSince;
    private long arrivedAt = -1;
    private long terrainSince = -1;
    private long sceneWaiting = -1;
    private int sceneBricks;
    private boolean settled;

    /** As {@link #frame(long, int, int, boolean, int, long, int)} where the light scene is not copied to the GPU. */
    public boolean frame(long nanos, int waiting, int held, boolean searched, int unloaded) {
        return frame(nanos, waiting, held, searched, unloaded, -1, 0);
    }

    /**
     * Takes one frame's figures.
     *
     * @param nanos the frame's time
     * @param waiting cubes waiting to be meshed or sorted
     * @param held meshes held
     * @param searched whether the view's last search was made, so its count of unloaded cubes is known
     * @param unloaded the cubes not loaded that the view's last search passed through
     * @param sceneWaiting what the light scene's copy on the GPU has yet to take from the scene and copy: cubes,
     *     bricks and lookup entries; below 0 where the scene is not copied
     * @param sceneBricks the bricks the copy holds
     * @return whether near terrain, and the scene's copy where there is one, have caught up
     */
    public boolean frame(long nanos, int waiting, int held, boolean searched, int unloaded, long sceneWaiting,
                         int sceneBricks) {
        boolean heldSame = held == lastHeld;
        lastHeld = held;
        if (unloaded != lastUnloaded) {
            lastUnloaded = unloaded;
            unloadedSince = nanos;
        }
        if (arrivedAt < 0) {
            arrivedAt = nanos;
        }
        this.sceneWaiting = sceneWaiting;
        this.sceneBricks = sceneBricks;
        boolean terrain = waiting == 0 && held > 0 && heldSame && searched
            && (unloaded == 0 || nanos - unloadedSince >= PLATEAU_NANOS);
        if (!terrain) {
            terrainSince = -1;
        } else if (terrainSince < 0) {
            terrainSince = nanos;
        }
        boolean now = terrain && (sceneWaiting < 0 || sceneWaiting == 0 && sceneBricks > 0);
        settled = now;
        return now;
    }

    /** The view has moved to another place: what it waits for starts again. */
    public void arrived(long nanos) {
        arrivedAt = nanos;
        terrainSince = -1;
        settled = false;
    }

    /** Whether the last frame found near terrain caught up while the light scene's copy had not. */
    public boolean sceneBehind() {
        return terrainSince >= 0 && !settled;
    }

    /** How long near terrain has stood caught up without a break, in seconds; 0 while it has not. */
    public double secondsTerrainCaughtUp(long nanos) {
        return terrainSince < 0 ? 0 : (nanos - terrainSince) / 1e9;
    }

    /** The light scene's copy as the last frame found it: what it had yet to take and copy, below 0 where there is none. */
    public long sceneWaiting() {
        return sceneWaiting;
    }

    /** The bricks the light scene's copy held at the last frame. */
    public int sceneBricks() {
        return sceneBricks;
    }

    /** How long since the view arrived, in seconds. */
    public double secondsSinceArrival(long nanos) {
        return arrivedAt < 0 ? 0 : (nanos - arrivedAt) / 1e9;
    }

    /** Whether the last frame found terrain caught up because the unloaded count stood still, not because it was 0. */
    public boolean byPlateau() {
        return settled && lastUnloaded != 0;
    }

    /** The unloaded count of the last frame. */
    public int unloaded() {
        return lastUnloaded;
    }
}
