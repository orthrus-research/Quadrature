package cc.orthrus.quadrature.core.lod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What becomes of each distant tile: which to build next, which results to keep, which tiles to
 * draw and which to let go. It holds no tile's data; whoever samples, meshes and stores tiles asks
 * it and tells it what happened.
 *
 * <p>A tile is wanted while a selection holds it. A wanted tile is built once: handed out by
 * {@link #next} in the selection's order (coarsest level first, then nearest), then reported
 * {@link #built} and {@link #placed}, or {@link #failed}. A tile that leaves the selection while it
 * is being built is cancelled: its result is refused when it arrives.
 *
 * <p>A placed tile that leaves the selection is not let go at once. Until every wanted tile over
 * the same ground is placed it stays, and is drawn, so that ground does not turn empty while its
 * replacement is built; it is listed by {@link #takeReleased} only after that.
 *
 * <p>A change of world is a new generation: {@link #reset} forgets every tile and refuses every
 * result still on its way.
 *
 * <p>Not safe for use from more than one thread: results are reported on the thread that owns it.
 */
public final class TileSchedule {

    private static final int QUEUED = 0, BUILDING = 1, BUILT = 2, PLACED = 3, FAILED = 4, LEAVING = 5;

    private final Map<Long, int[]> states = new HashMap<>();
    private long[] wanted = new long[1024];
    private int wantedCount;
    private int cursor;
    private int building;
    private long generation;
    private final ArrayList<Long> released = new ArrayList<>();
    private boolean leavingStale;

    /** The generation results must carry to be accepted. */
    public long generation() {
        return generation;
    }

    /** Forgets every tile. Placed tiles are listed by {@link #takeReleased}; work on its way is refused. */
    public void reset() {
        for (Map.Entry<Long, int[]> entry : states.entrySet()) {
            int state = entry.getValue()[0];
            if (state == PLACED || state == LEAVING) {
                released.add(entry.getKey());
            }
        }
        states.clear();
        wantedCount = 0;
        cursor = 0;
        building = 0;
        generation++;
    }

    /** Takes a selection as what is wanted now. */
    public void want(Rings rings) {
        int count = rings.count();
        if (wanted.length < count) {
            wanted = new long[count * 2];
        }
        // Mark every tile unwanted, then mark the selection's again.
        for (int[] state : states.values()) {
            state[1] = 0;
        }
        for (int i = 0; i < count; i++) {
            long key = rings.tile(i);
            wanted[i] = key;
            int[] state = states.get(key);
            if (state == null) {
                states.put(key, new int[] {QUEUED, 1});
            } else {
                state[1] = 1;
                if (state[0] == LEAVING) {
                    state[0] = PLACED;
                }
            }
        }
        wantedCount = count;
        cursor = 0;
        for (Iterator<Map.Entry<Long, int[]>> it = states.entrySet().iterator(); it.hasNext();) {
            int[] state = it.next().getValue();
            if (state[1] != 0) {
                continue;
            }
            if (state[0] == PLACED) {
                state[0] = LEAVING;
            } else if (state[0] == QUEUED || state[0] == FAILED || state[0] == BUILT) {
                it.remove();
            }
            // A tile being built stays until its result arrives and is refused.
        }
        leavingStale = true;
    }

    /**
     * The next tile to build, or -1 when none is waiting or {@code limit} are being built already.
     * The tile counts as being built until {@link #built} or {@link #failed} is told of it.
     */
    public long next(int limit) {
        if (building >= limit) {
            return -1;
        }
        while (cursor < wantedCount) {
            long key = wanted[cursor++];
            int[] state = states.get(key);
            if (state != null && state[0] == QUEUED) {
                state[0] = BUILDING;
                building++;
                return key;
            }
        }
        return -1;
    }

    /**
     * A tile's build has finished.
     *
     * @return whether its result is still wanted; if not, discard it
     */
    public boolean built(long key, long ofGeneration) {
        if (ofGeneration != generation) {
            return false;
        }
        int[] state = states.get(key);
        if (state == null || state[0] != BUILDING) {
            return false;
        }
        building--;
        if (state[1] == 0) {
            states.remove(key);
            return false;
        }
        state[0] = BUILT;
        return true;
    }

    /** Whether the selection holds a tile. A tile being built that it does not hold may be abandoned. */
    public boolean wanted(long key) {
        int[] state = states.get(key);
        return state != null && state[1] != 0;
    }

    /** Whether a built tile is still waiting to be stored; if not, discard it. */
    public boolean awaited(long key) {
        int[] state = states.get(key);
        return state != null && state[0] == BUILT;
    }

    /** A built tile is stored and can be drawn. */
    public void placed(long key) {
        int[] state = states.get(key);
        if (state == null || state[0] != BUILT) {
            throw new IllegalStateException("Tile " + key + " was not built");
        }
        state[0] = PLACED;
        leavingStale = true;
    }

    /**
     * A tile's build failed, or its result could not be stored. It is not tried again until it has
     * left the selection and come back, unless {@code again} is set.
     */
    public void failed(long key, long ofGeneration, boolean again) {
        if (ofGeneration != generation) {
            return;
        }
        int[] state = states.get(key);
        if (state == null || (state[0] != BUILDING && state[0] != BUILT)) {
            return;
        }
        if (state[0] == BUILDING) {
            building--;
        }
        if (state[1] == 0) {
            states.remove(key);
        } else if (again) {
            state[0] = QUEUED;
            cursor = 0;
        } else {
            state[0] = FAILED;
        }
    }

    /** Whether a stored tile has left the selection and is kept only until its replacement is stored. */
    public boolean kept(long key) {
        int[] state = states.get(key);
        return state != null && state[0] == LEAVING;
    }

    /** Whether a tile is stored and should be drawn: wanted, or kept until its replacement is. */
    public boolean drawn(long key) {
        int[] state = states.get(key);
        return state != null && (state[0] == PLACED || state[0] == LEAVING);
    }

    /**
     * The stored tiles that may be let go now, each listed once: those no longer wanted whose ground
     * is covered by placed tiles of the selection, and everything after a {@link #reset}. The
     * caller empties the list once it has let them go.
     */
    public ArrayList<Long> takeReleased() {
        if (leavingStale) {
            leavingStale = false;
            for (Iterator<Map.Entry<Long, int[]>> it = states.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Long, int[]> entry = it.next();
                if (entry.getValue()[0] == LEAVING && !needed(entry.getKey())) {
                    released.add(entry.getKey());
                    it.remove();
                }
            }
        }
        return released;
    }

    /** Whether a wanted tile over the same ground as {@code leaving} is not placed yet. */
    private boolean needed(long leaving) {
        for (int i = 0; i < wantedCount; i++) {
            long key = wanted[i];
            if (TileKey.covers(leaving, key) || TileKey.covers(key, leaving)) {
                int[] state = states.get(key);
                // A tile that failed will not arrive: it holds nothing back.
                if (state != null && state[0] != PLACED && state[0] != FAILED) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Tiles being built now. */
    public int building() {
        return building;
    }

    /** Tiles of the selection, and of them those placed, of one level: {@code [wanted, placed]}. */
    public void count(int level, int[] into) {
        into[0] = 0;
        into[1] = 0;
        for (int i = 0; i < wantedCount; i++) {
            if (TileKey.level(wanted[i]) == level) {
                into[0]++;
                int[] state = states.get(wanted[i]);
                if (state != null && state[0] == PLACED) {
                    into[1]++;
                }
            }
        }
    }

    /** Stored tiles kept past their time in the selection. */
    public int leaving() {
        int count = 0;
        for (int[] state : states.values()) {
            if (state[0] == LEAVING) {
                count++;
            }
        }
        return count;
    }
}
