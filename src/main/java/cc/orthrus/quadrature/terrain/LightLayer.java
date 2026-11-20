package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.core.terrain.CubeList;
import cc.orthrus.quadrature.core.terrain.Visibility;
import cc.orthrus.quadrature.core.view.LightLayerPlan;
import cc.orthrus.quadrature.core.view.LightTiles;
import cc.orthrus.quadrature.core.view.View;

import java.util.Arrays;

/**
 * One layer of the light's depth as the terrain draws it: its plan, the views through its boxes for this
 * frame, and what the last sweep of each found. The drawing itself is {@link Terrain}'s, which holds the
 * meshes; see {@link LightLayerPlan} for when and where a layer is drawn.
 */
final class LightLayer {

    /** The layer's index in the array texture. */
    final int index;
    /** "near" or "wide", for the log. */
    final String name;
    /** The side across the light; the depth along it is the wide box's. */
    final boolean near;
    final LightLayerPlan plan;

    /** The views through the front, the box being drawn and the box published, made each frame. */
    final View front = new View();
    final View next = new View();
    final View published = new View();
    final Visibility swept = new Visibility();

    /** For each tile, how many drawable cubes of the last sweep may cover it. */
    final int[] costs = new int[LightTiles.COUNT];
    /** The drawable cubes of the last sweep that lie across the map, and the texels each may cover. */
    final CubeList.Keys candidates = new CubeList.Keys();
    int[] ranges = new int[4 * 256];
    /** The cubes chosen to draw for the tiles of a frame. */
    final CubeList.Keys chosen = new CubeList.Keys();
    final int[] rectangles = new int[4 * LightTiles.COUNT];

    long frontCubes;
    long nextCubes;
    /** Cubes drawn into the box being drawn into the scratch since the last was published; none means the scratch is clear. */
    long boxCubes;
    int lastDraws;

    LightLayer(int index, String name, boolean near, double turnCosine, int spread) {
        this.index = index;
        this.name = name;
        this.near = near;
        this.plan = new LightLayerPlan(turnCosine, spread);
    }

    /** Starts a sweep's bookkeeping: no candidate, no cost. */
    void clearCandidates() {
        candidates.clear();
        Arrays.fill(costs, 0);
    }

    /** Keeps a candidate and the texels it may cover. */
    void candidate(long key, int[] range) {
        int at = candidates.count();
        if (ranges.length < (at + 1) * 4) {
            ranges = Arrays.copyOf(ranges, ranges.length * 2);
        }
        System.arraycopy(range, 0, ranges, at * 4, 4);
        candidates.add(key);
    }
}
