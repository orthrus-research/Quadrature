package cc.orthrus.quadrature.core.terrain;

/**
 * Whether a view's search for what it sees came to the edge of the box near terrain holds cubes in:
 * what drawing terrain beyond the box needs to know, since only where the search came to the edge can
 * anything beyond it be seen.
 */
public final class SearchEdge {

    private boolean searched;
    private int faces;
    private int steps;
    private int unloaded;

    /** Sets what the last search found. */
    public void set(boolean searched, int faces, int steps, int unloaded) {
        this.searched = searched;
        this.faces = searched ? faces : 0;
        this.steps = searched ? steps : 0;
        this.unloaded = searched ? unloaded : 0;
    }

    /**
     * Whether a search was made. Where terrain is drawn without one, as when the eye is outside the box,
     * nothing is known and everything beyond the box must be taken as seen.
     */
    public boolean searched() {
        return searched;
    }

    /** Whether the search came to the edge with the view still seeing beyond it. */
    public boolean reachedEdge() {
        return !searched || faces != 0;
    }

    /**
     * Through which faces of the box the search would have gone on: bit 0 down, 1 up, 2 north (toward
     * lower z), 3 south, 4 west (toward lower x), 5 east.
     */
    public int faces() {
        return faces;
    }

    /** How many times the search came to the edge. */
    public int steps() {
        return steps;
    }

    /** How many cubes inside the box the search passed through that are not loaded. */
    public int unloaded() {
        return unloaded;
    }
}
