package cc.orthrus.quadrature.core.view;

import java.util.BitSet;

/**
 * When and where one layer of the light's depth is drawn, so that no frame draws the whole of it.
 *
 * <p>The layer published is drawn through a {@link LightBox} fixed in the world, its front. Two kinds of
 * work keep it right, and both are measured in tiles ({@link LightTiles}) and bounded each frame by a
 * budget of cubes, a {@code spread}th of what covers the whole layer:
 * <ul>
 *   <li>A mesh placed, replaced or released makes the tiles its cube covers dirty in the front; they are
 *       cleared and drawn again in place, nearest the center first.</li>
 *   <li>When the light has turned more than a set angle since the front was drawn, or the player has
 *       come further than a set share of the side from its center, or the box's size changes, a new box
 *       is made where the player stands now and drawn tile by tile into a layer of its own, a scratch,
 *       over about {@code spread} frames; once every tile is drawn the scratch is copied over the
 *       front and becomes it. The front stays whole and published until then.</li>
 * </ul>
 * A mesh that changes while a new box is being drawn makes its tiles dirty there too, so whatever is
 * drawn into the scratch before the change is drawn again before it is published; the front's own dirty
 * tiles wait meanwhile, since the box that replaces it holds the change.
 *
 * <p>Nothing here touches the graphics context: the host reports what changed and what each tile costs,
 * draws the tiles named, and says when it has.
 */
public final class LightLayerPlan {

    /** Why a new box was begun. */
    public enum Cause { FIRST, SIZE, TURN, MOVE }

    /** The share of the side the player may stray from a front's center before a new box is begun. */
    public static final double DRIFT_SHARE = 1.0 / 64;
    /** The share of the side the player may stray from a box being drawn before it is begun again where they stand. */
    public static final double RESTART_SHARE = 1.0 / 8;

    private final double turnCosine;
    private final int spread;

    private LightBox front;
    private LightBox next;
    private Cause nextCause;
    private final BitSet dirty = new BitSet(LightTiles.COUNT);
    private final BitSet pending = new BitSet(LightTiles.COUNT);
    private final BitSet chosen = new BitSet(LightTiles.COUNT);
    private final int[] range = new int[4];
    private boolean nextTouched;
    private boolean contentChanged;
    private boolean lightChanged;

    private final long[] begun = new long[Cause.values().length];
    private long restarts;
    private long published;
    private long frontTiles;
    private long nextTiles;
    private long nextFrames;

    /**
     * @param turnCosine the cosine of the angle the light may turn before a new box is begun
     * @param spread     about how many frames a new box is drawn over, and the share of a layer's cost a
     *                   frame may spend on dirty tiles; at least 1
     */
    public LightLayerPlan(double turnCosine, int spread) {
        this.turnCosine = turnCosine;
        this.spread = Math.max(1, spread);
    }

    /**
     * Starts a frame: begins a new box where one is due. Changes this frame should already be reported.
     *
     * @param towardX the direction toward the light this frame; it need not be of unit length
     * @param x       where the player stands, the place boxes are centered on
     */
    public void frame(double towardX, double towardY, double towardZ, double x, double y, double z, double side,
        double depth, int texels) {
        if (next != null) {
            if (!next.sized(side, depth) || next.offCenter(x, y, z) > side * RESTART_SHARE) {
                restarts++;
                begin(towardX, towardY, towardZ, x, y, z, side, depth, texels, front == null ? Cause.FIRST
                    : !next.sized(side, depth) ? Cause.SIZE : Cause.MOVE);
            }
            return;
        }
        if (front == null) {
            begin(towardX, towardY, towardZ, x, y, z, side, depth, texels, Cause.FIRST);
        } else if (!front.sized(side, depth)) {
            begin(towardX, towardY, towardZ, x, y, z, side, depth, texels, Cause.SIZE);
        } else if (front.cosine(towardX, towardY, towardZ) < turnCosine) {
            begin(towardX, towardY, towardZ, x, y, z, side, depth, texels, Cause.TURN);
        } else if (front.offCenter(x, y, z) > side * DRIFT_SHARE) {
            begin(towardX, towardY, towardZ, x, y, z, side, depth, texels, Cause.MOVE);
        }
    }

    private void begin(double towardX, double towardY, double towardZ, double x, double y, double z, double side,
        double depth, int texels, Cause cause) {
        next = LightBox.around(towardX, towardY, towardZ, x, y, z, side, depth, texels);
        nextCause = cause;
        pending.set(0, LightTiles.COUNT);
        nextTouched = false;
        begun[cause.ordinal()]++;
    }

    /** A cube's mesh was placed, replaced or released: the tiles it covers are drawn again. */
    public void changed(int cubeX, int cubeY, int cubeZ) {
        if (front != null && front.texelsOf(cubeX, cubeY, cubeZ, range)) {
            LightTiles.cover(dirty, range, front.texels());
        }
        if (next != null && next.texelsOf(cubeX, cubeY, cubeZ, range)) {
            LightTiles.cover(pending, range, next.texels());
            nextTouched = true;
        }
    }

    /** The box the published layer was drawn through, or null before one has been drawn whole. */
    public LightBox front() {
        return front;
    }

    /** The box being drawn into the scratch, or null when none is. */
    public LightBox next() {
        return next;
    }

    /** Why the box being drawn was begun, or null when none is. */
    public Cause nextCause() {
        return next == null ? null : nextCause;
    }

    /**
     * Whether the front has tiles to draw again now. While a new box is being drawn they wait: the new
     * box draws again every tile a change touches, so it holds the change when it is published, and the
     * front it replaces may lie where the player no longer is, as after a teleport, where the meshes
     * released as the old place unloads would make it dirty from end to end.
     */
    public boolean frontDirty() {
        return front != null && next == null && !dirty.isEmpty();
    }

    /**
     * The front's dirty tiles to draw this frame: nearest the center first, while the cubes covering
     * them fit a {@code spread}th of what covers the whole layer, and at least one. A tile no cube covers
     * costs nothing and is always taken.
     *
     * @param costs for each tile, how many cubes in the front's box may cover it
     */
    public BitSet chooseFront(int[] costs) {
        return choose(dirty, costs, share(costs));
    }

    /** The front's tiles named were cleared and drawn. */
    public void frontDrawn(BitSet tiles) {
        dirty.andNot(tiles);
        frontTiles += tiles.cardinality();
        if (!tiles.isEmpty()) {
            contentChanged = true;
        }
    }

    /**
     * The tiles of the box being drawn to draw this frame, by the same rule as the front's: a
     * {@code spread}th of what covers the whole box as it stands this frame, so that a box begun where
     * meshes are still arriving, as after a teleport, is not held to the little that was there at first.
     *
     * @param costs for each tile, how many cubes in that box may cover it
     */
    public BitSet chooseNext(int[] costs) {
        return choose(pending, costs, share(costs));
    }

    /**
     * The tiles named of the box being drawn were cleared and drawn.
     *
     * @return whether every tile is now drawn: the host copies the scratch over the front, then calls
     *         {@link #publish}
     */
    public boolean nextDrawn(BitSet tiles) {
        pending.andNot(tiles);
        nextTiles += tiles.cardinality();
        nextFrames++;
        return pending.isEmpty();
    }

    /**
     * The scratch has been copied over the front: the box drawn becomes the front, whole and with no
     * dirty tile, since every change made while it was drawn was drawn into it.
     */
    public void publish() {
        boolean moved = nextCause != Cause.TURN || nextTouched;
        front = next;
        next = null;
        nextCause = null;
        dirty.clear();
        lightChanged = true;
        contentChanged |= moved;
        published++;
    }

    /**
     * Every tile is drawn again, of the front and of a box being drawn, as when something every draw
     * depends on changes, such as the alpha at which cutout faces are dropped.
     */
    public void redrawAll() {
        if (front != null) {
            dirty.set(0, LightTiles.COUNT);
        }
        if (next != null) {
            pending.set(0, LightTiles.COUNT);
            nextTouched = true;
        }
    }

    /** Forgets every box: another world, or the target made anew. */
    public void reset() {
        front = null;
        next = null;
        nextCause = null;
        dirty.clear();
        pending.clear();
        contentChanged = true;
        lightChanged = true;
    }

    /**
     * Whether what the front holds has changed, apart from the light's own motion, since this was last
     * asked: tiles drawn again, or a box published for another place.
     */
    public boolean takeContentChanged() {
        boolean was = contentChanged;
        contentChanged = false;
        return was;
    }

    /** Whether the front's box has changed since this was last asked. */
    public boolean takeLightChanged() {
        boolean was = lightChanged;
        lightChanged = false;
        return was;
    }

    public long begun(Cause cause) {
        return begun[cause.ordinal()];
    }

    /** How many boxes being drawn were begun again where the player had gone. */
    public long restarts() {
        return restarts;
    }

    /** How many boxes were drawn whole and published. */
    public long published() {
        return published;
    }

    /** Tiles of the front drawn again. */
    public long frontTiles() {
        return frontTiles;
    }

    /** Tiles drawn into boxes being drawn, and the frames that drew them. */
    public long nextTiles() {
        return nextTiles;
    }

    public long nextFrames() {
        return nextFrames;
    }

    /** Tiles still to draw of the box being drawn. */
    public int pendingTiles() {
        return next == null ? 0 : pending.cardinality();
    }

    public int dirtyTiles() {
        return dirty.cardinality();
    }

    private long share(int[] costs) {
        long total = 0;
        for (int cost : costs) {
            total += cost;
        }
        return (total + spread - 1) / spread;
    }

    private BitSet choose(BitSet from, int[] costs, long budget) {
        chosen.clear();
        long spent = 0;
        boolean full = false;
        for (int rank = 0; rank < LightTiles.COUNT; rank++) {
            int tile = LightTiles.outward(rank);
            if (!from.get(tile)) {
                continue;
            }
            int cost = costs[tile];
            if (cost == 0) {
                chosen.set(tile);
            } else if (!full && (spent == 0 || spent + cost <= budget)) {
                chosen.set(tile);
                spent += cost;
            } else {
                full = true;
            }
        }
        return chosen;
    }
}
