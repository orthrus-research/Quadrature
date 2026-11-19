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
 */
public final class TerrainSettle {

    /** How long the count of unloaded cubes must stand still to say the rest will not come. */
    public static final long PLATEAU_NANOS = 15_000_000_000L;

    private int lastHeld = -1;
    private int lastUnloaded = -1;
    private long unloadedSince;
    private long arrivedAt = -1;
    private boolean settled;

    /**
     * Takes one frame's figures.
     *
     * @param nanos the frame's time
     * @param waiting cubes waiting to be meshed or sorted
     * @param held meshes held
     * @param searched whether the view's last search was made, so its count of unloaded cubes is known
     * @param unloaded the cubes not loaded that the view's last search passed through
     * @return whether near terrain has caught up
     */
    public boolean frame(long nanos, int waiting, int held, boolean searched, int unloaded) {
        boolean heldSame = held == lastHeld;
        lastHeld = held;
        if (unloaded != lastUnloaded) {
            lastUnloaded = unloaded;
            unloadedSince = nanos;
        }
        if (arrivedAt < 0) {
            arrivedAt = nanos;
        }
        boolean now = waiting == 0 && held > 0 && heldSame && searched
            && (unloaded == 0 || nanos - unloadedSince >= PLATEAU_NANOS);
        settled = now;
        return now;
    }

    /** The view has moved to another place: what it waits for starts again. */
    public void arrived(long nanos) {
        arrivedAt = nanos;
        settled = false;
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
