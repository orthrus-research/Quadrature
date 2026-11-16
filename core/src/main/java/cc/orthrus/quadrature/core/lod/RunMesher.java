package cc.orthrus.quadrature.core.lod;

import java.util.Arrays;

/**
 * Turns a {@link RunTile} into faces. Only the tile's 64 by 64 columns make faces; the apron says
 * what stands beside them.
 *
 * <p>Space is a stretch of a column with no opaque run in it: air, leaves, water and detail (a
 * plant, a layer of snow) are all space. The rules:
 *
 * <ul>
 *   <li><b>Opaque.</b> A top or bottom face where the run above or below is space. Against each of
 *       the four columns beside it, one side face for each stretch of that column's space that an
 *       unbroken stretch of this column's opaque runs overlaps, however many materials the stretch
 *       crosses; the face carries the material at its top.
 *   <li><b>Leaves.</b> The same, run by run, but no face against opaque runs or other leaves.
 *   <li><b>Water.</b> Top faces only, in a layer of their own. The bed under water is space's
 *       floor and is meshed as opaque.
 *   <li><b>Tops merge.</b> Top faces of one layer, material, height, sky light and occlusion
 *       join into rectangles, along the axes on which their corners' occlusion agrees: both
 *       where no corner is closed in, one for ground along a wall.
 *   <li><b>A closed tile.</b> On the tile's four outer sides, what the neighbor hides of each
 *       stretch of opaque runs becomes skirt faces, one for each hidden piece, so the sides are
 *       covered from floor to surface and no gap can show beside a tile of another level or one
 *       not yet there.
 *   <li><b>Exposure</b>, off unless asked for: space counts only if it can be reached from the
 *       tile's open top or its sides through neighboring space that overlaps in height. Space
 *       that cannot is treated as solid, so sealed caves under a built-over area make no face.
 *       Generated terrain needs none of this: its sampler fills what the sky cannot reach.
 *   <li><b>Light</b> is the sky's, 15 less one for each block of leaves and three for each of
 *       water above the face; under an opaque run, one less than the best of the four columns
 *       beside it, and none where they are covered too. A side face takes it at its top.
 *   <li><b>Occlusion</b> at each corner of a top or side face counts the opaque cells that close
 *       it in: the two beside the corner and the one diagonal to it, 3 when both sides are
 *       closed.
 * </ul>
 *
 * <p>Not safe for use from more than one thread; each thread that meshes has its own. Allocates
 * only when it grows, never for a face.
 */
public final class RunMesher {

    private static final int[] STEP = {0, 0, -RunTile.SIDE, RunTile.SIDE, -1, 1};
    /** The column to the left of a side face's space, as seen from outside the face. */
    private static final int[] LEFT = {0, 0, 1, -1, -RunTile.SIDE, RunTile.SIDE};
    private static final int NONE = Integer.MIN_VALUE;

    private boolean exposure;
    private boolean closed = true;

    // Stretches of space of the tile's columns.
    private final int[] spaceStart = new int[RunTile.COLUMNS + 1];
    private int[] low = new int[RunTile.COLUMNS * 2];
    private int[] high = new int[RunTile.COLUMNS * 2];
    private int[] spaceColumn = new int[RunTile.COLUMNS * 2];
    private boolean[] reached = new boolean[RunTile.COLUMNS * 2];
    private int[] stack = new int[RunTile.COLUMNS * 2];
    private int[] runSpace = new int[RunTile.COLUMNS * 6];

    // Top faces before merging: layer, material, height, light and occlusion over z and x.
    private long[] tops = new long[RunTile.CELLS * RunTile.CELLS * 2];
    private int topCount;
    private final long[] mask = new long[RunTile.CELLS];

    private RunTile tile;
    private TileMesh mesh;

    /** Whether only space reachable from the tile's top and sides counts. Off at first. */
    public RunMesher exposure(boolean on) {
        exposure = on;
        return this;
    }

    /** Whether the tile's outer sides are closed with skirt faces. On at first. */
    public RunMesher closed(boolean on) {
        closed = on;
        return this;
    }

    /** Meshes a finished tile into {@code mesh}, replacing what it held. */
    public void mesh(RunTile tile, TileMesh mesh) {
        tile.requireFinished();
        this.tile = tile;
        this.mesh = mesh;
        mesh.clear();
        mesh.tints(tile);
        topCount = 0;
        spaces();
        reach();
        for (int z = 0; z < RunTile.CELLS; z++) {
            for (int x = 0; x < RunTile.CELLS; x++) {
                column(x, z);
            }
        }
        merge();
        this.tile = null;
        this.mesh = null;
    }

    private void spaces() {
        int runs = tile.runs();
        if (runSpace.length < runs) {
            runSpace = new int[runs * 2];
        }
        int count = 0;
        for (int c = 0; c < RunTile.COLUMNS; c++) {
            spaceStart[c] = count;
            int open = -1;
            for (int r = tile.start[c]; r < tile.start[c + 1]; r++) {
                if (tile.kind[r] == RunTile.OPAQUE) {
                    runSpace[r] = -1;
                    open = -1;
                    continue;
                }
                if (open < 0) {
                    if (count == low.length) {
                        grow();
                    }
                    open = count++;
                    low[open] = tile.bottom(c, r);
                    spaceColumn[open] = c;
                }
                high[open] = tile.top[r];
                runSpace[r] = open;
            }
        }
        spaceStart[RunTile.COLUMNS] = count;
    }

    private void grow() {
        int size = low.length * 2;
        low = Arrays.copyOf(low, size);
        high = Arrays.copyOf(high, size);
        spaceColumn = Arrays.copyOf(spaceColumn, size);
        reached = Arrays.copyOf(reached, size);
        stack = Arrays.copyOf(stack, size);
    }

    private void reach() {
        int count = spaceStart[RunTile.COLUMNS];
        if (!exposure) {
            Arrays.fill(reached, 0, count, true);
            return;
        }
        Arrays.fill(reached, 0, count, false);
        int depth = 0;
        for (int c = 0; c < RunTile.COLUMNS; c++) {
            int x = c % RunTile.SIDE;
            int z = c / RunTile.SIDE;
            boolean apron = x == 0 || z == 0 || x == RunTile.SIDE - 1 || z == RunTile.SIDE - 1;
            for (int s = spaceStart[c]; s < spaceStart[c + 1]; s++) {
                if (apron || high[s] == tile.ceilingY()) {
                    reached[s] = true;
                    stack[depth++] = s;
                }
            }
        }
        while (depth > 0) {
            int s = stack[--depth];
            int c = spaceColumn[s];
            int x = c % RunTile.SIDE;
            int z = c / RunTile.SIDE;
            for (int d = FaceRecord.NORTH; d <= FaceRecord.EAST; d++) {
                if (d == FaceRecord.WEST && x == 0 || d == FaceRecord.EAST && x == RunTile.SIDE - 1
                        || d == FaceRecord.NORTH && z == 0 || d == FaceRecord.SOUTH && z == RunTile.SIDE - 1) {
                    continue;
                }
                int n = c + STEP[d];
                for (int m = spaceStart[n]; m < spaceStart[n + 1]; m++) {
                    if (!reached[m] && low[m] < high[s] && low[s] < high[m]) {
                        reached[m] = true;
                        stack[depth++] = m;
                    }
                }
            }
        }
    }

    /** Whether a run is space that counts. */
    private boolean open(int run) {
        int space = runSpace[run];
        return space >= 0 && reached[space];
    }

    private void column(int x, int z) {
        int c = RunTile.column(x, z);
        int first = tile.start[c];
        int end = tile.start[c + 1];
        int floor = tile.floorY();
        for (int r = first; r < end; r++) {
            byte kind = tile.kind[r];
            if (kind == RunTile.AIR || kind == RunTile.DETAIL) {
                continue;
            }
            int bottom = tile.bottom(c, r);
            int top = tile.top[r];
            int material = tile.material[r];
            boolean above = r + 1 < end;
            boolean below = r > first;
            if (kind == RunTile.OPAQUE) {
                if (above && open(r + 1)) {
                    top(TileMesh.OPAQUE, x, z, c, top, material);
                }
                if (below && open(r - 1)) {
                    bottom(TileMesh.OPAQUE, x, z, c, bottom, material);
                }
            } else if (!open(r)) {
                continue;
            } else if (kind == RunTile.LEAVES) {
                if (above && tile.kind[r + 1] != RunTile.OPAQUE && tile.kind[r + 1] != RunTile.LEAVES) {
                    top(TileMesh.CUTOUT, x, z, c, top, material);
                }
                if (below && tile.kind[r - 1] != RunTile.OPAQUE && tile.kind[r - 1] != RunTile.LEAVES) {
                    bottom(TileMesh.CUTOUT, x, z, c, bottom, material);
                }
                for (int d = FaceRecord.NORTH; d <= FaceRecord.EAST; d++) {
                    leafSides(x, z, c, d, bottom, top, material);
                }
            } else if (kind == RunTile.WATER) {
                if (above && tile.kind[r + 1] != RunTile.OPAQUE && tile.kind[r + 1] != RunTile.WATER) {
                    top(TileMesh.WATER, x, z, c, top, material);
                }
            }
        }
        // Solid stretches, whatever their materials, against the space beside them.
        int stretchBottom = 0;
        boolean inside = false;
        for (int r = first; r <= end; r++) {
            boolean solid = r < end && !open(r);
            if (solid && !inside) {
                inside = true;
                stretchBottom = tile.bottom(c, r);
            } else if (!solid && inside) {
                inside = false;
                int stretchTop = tile.top[r - 1];
                for (int d = FaceRecord.NORTH; d <= FaceRecord.EAST; d++) {
                    solidSides(x, z, c, d, stretchBottom, stretchTop, floor);
                }
            }
        }
    }

    private void solidSides(int x, int z, int c, int direction, int bottom, int top, int floor) {
        int n = c + STEP[direction];
        boolean skirt = closed && outward(x, z, direction);
        int cursor = bottom;
        for (int m = spaceStart[n]; m < spaceStart[n + 1]; m++) {
            if (!reached[m] || high[m] <= bottom || low[m] >= top) {
                continue;
            }
            int from = Math.max(bottom, low[m]);
            int to = Math.min(top, high[m]);
            if (skirt && from > cursor) {
                skirt(x, z, direction, cursor, from, floor);
            }
            cursor = to;
            mesh.add(TileMesh.group(TileMesh.OPAQUE, direction), FaceRecord.side(x, z, from - floor, to - from,
                    direction, materialAt(c, to - 1), light(n, to), sideOcclusion(n, direction, from, to)));
            mesh.span(from, to);
        }
        if (skirt && cursor < top) {
            skirt(x, z, direction, cursor, top, floor);
        }
    }

    private void skirt(int x, int z, int direction, int from, int to, int floor) {
        mesh.add(TileMesh.group(TileMesh.SKIRT, direction),
                FaceRecord.side(x, z, from - floor, to - from, direction, 0, 0, 0));
        mesh.span(from, to);
    }

    private static boolean outward(int x, int z, int direction) {
        return direction == FaceRecord.WEST && x == 0 || direction == FaceRecord.EAST && x == RunTile.CELLS - 1
                || direction == FaceRecord.NORTH && z == 0 || direction == FaceRecord.SOUTH && z == RunTile.CELLS - 1;
    }

    /** The material of the opaque run holding block {@code y}, or of the nearest one below it. */
    private int materialAt(int c, int y) {
        int found = 0;
        for (int r = tile.start[c]; r < tile.start[c + 1]; r++) {
            if (tile.kind[r] == RunTile.OPAQUE) {
                found = tile.material[r];
            }
            if (tile.top[r] > y) {
                break;
            }
        }
        return found;
    }

    private void leafSides(int x, int z, int c, int direction, int bottom, int top, int material) {
        int n = c + STEP[direction];
        int from = NONE;
        for (int r = tile.start[n]; r < tile.start[n + 1]; r++) {
            int runBottom = tile.bottom(n, r);
            int runTop = tile.top[r];
            if (runTop <= bottom) {
                continue;
            }
            if (runBottom >= top) {
                break;
            }
            byte kind = tile.kind[r];
            boolean shows = kind != RunTile.OPAQUE && kind != RunTile.LEAVES && open(r);
            if (shows && from == NONE) {
                from = Math.max(runBottom, bottom);
            }
            if (!shows && from != NONE) {
                leafSide(x, z, n, direction, from, runBottom, material);
                from = NONE;
            }
            if (shows && runTop >= top) {
                leafSide(x, z, n, direction, from, top, material);
                from = NONE;
            }
        }
    }

    private void leafSide(int x, int z, int n, int direction, int from, int to, int material) {
        int floor = tile.floorY();
        mesh.add(TileMesh.group(TileMesh.CUTOUT, direction), FaceRecord.side(x, z, from - floor, to - from,
                direction, material, light(n, to), sideOcclusion(n, direction, from, to)));
        mesh.span(from, to);
    }

    private void bottom(int layer, int x, int z, int c, int y, int material) {
        mesh.add(TileMesh.group(layer, FaceRecord.DOWN),
                FaceRecord.flat(x, z, y - tile.floorY(), 1, 1, FaceRecord.DOWN, material, light(c, y), 0));
        mesh.span(y, y);
    }

    private void top(int layer, int x, int z, int c, int y, int material) {
        if (topCount == tops.length) {
            tops = Arrays.copyOf(tops, topCount * 2);
        }
        long key = (long) layer << 37 | (long) material << 24 | (long) (y - tile.floorY()) << 12
                | (long) light(c, y) << 8 | topOcclusion(c, y);
        tops[topCount++] = key << 12 | z << 6 | x;
        mesh.topCell();
        mesh.span(y, y);
    }

    /**
     * Sky light at level {@code y} of a column: what falls on it from above, or, under an opaque
     * run, what spreads in from the four columns beside it, one less than the best of theirs. The
     * game spreads sky light sideways so, a level a block; one step of it is what ground and a
     * trunk under a branch get, which with none drew black under every tree with wood in its
     * crown. Deeper under an overhang the light is still none.
     */
    private int light(int c, int y) {
        int direct = fallen(c, y);
        if (direct >= 0) {
            return direct;
        }
        int x = c % RunTile.SIDE;
        int z = c / RunTile.SIDE;
        int best = 0;
        if (x > 0) {
            best = Math.max(best, fallen(c - 1, y) - 1);
        }
        if (x < RunTile.SIDE - 1) {
            best = Math.max(best, fallen(c + 1, y) - 1);
        }
        if (z > 0) {
            best = Math.max(best, fallen(c - RunTile.SIDE, y) - 1);
        }
        if (z < RunTile.SIDE - 1) {
            best = Math.max(best, fallen(c + RunTile.SIDE, y) - 1);
        }
        return best;
    }

    /** Sky light reaching level {@code y} of a column from above, or -1 under an opaque run. */
    private int fallen(int c, int y) {
        int light = 15;
        for (int r = tile.start[c + 1] - 1; r >= tile.start[c] && light > 0; r--) {
            int top = tile.top[r];
            if (top <= y) {
                break;
            }
            byte kind = tile.kind[r];
            if (kind == RunTile.OPAQUE) {
                return -1;
            }
            int height = top - Math.max(y, tile.bottom(c, r));
            if (kind == RunTile.LEAVES) {
                light -= height;
            } else if (kind == RunTile.WATER) {
                light -= 3 * height;
            }
        }
        return Math.max(0, light);
    }

    /** Whether a column is opaque at block {@code y}; nothing is, outside the tile's height. */
    private boolean opaque(int c, int y) {
        if (y < tile.floorY() || y >= tile.ceilingY()) {
            return false;
        }
        for (int r = tile.start[c]; r < tile.start[c + 1]; r++) {
            if (tile.top[r] > y) {
                return tile.kind[r] == RunTile.OPAQUE;
            }
        }
        return false;
    }

    private static int corner(boolean first, boolean second, boolean diagonal) {
        return first && second ? 3 : (first ? 1 : 0) + (second ? 1 : 0) + (diagonal ? 1 : 0);
    }

    /** Occlusion of a top face at level {@code y}, by the columns round it at that level. */
    private int topOcclusion(int c, int y) {
        boolean west = opaque(c - 1, y);
        boolean east = opaque(c + 1, y);
        boolean north = opaque(c - RunTile.SIDE, y);
        boolean south = opaque(c + RunTile.SIDE, y);
        return corner(west, north, opaque(c - RunTile.SIDE - 1, y))
                | corner(east, north, opaque(c - RunTile.SIDE + 1, y)) << 2
                | corner(west, south, opaque(c + RunTile.SIDE - 1, y)) << 4
                | corner(east, south, opaque(c + RunTile.SIDE + 1, y)) << 6;
    }

    /**
     * Occlusion of a side face from {@code from} up to {@code to}, by what closes in the space
     * {@code n} it faces: the columns to its left and right, and that space's own floor and roof.
     */
    private int sideOcclusion(int n, int direction, int from, int to) {
        int left = n + LEFT[direction];
        int right = n - LEFT[direction];
        boolean under = opaque(n, from - 1);
        boolean over = opaque(n, to);
        return corner(opaque(left, from), under, opaque(left, from - 1))
                | corner(opaque(right, from), under, opaque(right, from - 1)) << 2
                | corner(opaque(left, to - 1), over, opaque(left, to)) << 4
                | corner(opaque(right, to - 1), over, opaque(right, to)) << 6;
    }

    private void merge() {
        Arrays.sort(tops, 0, topCount);
        int at = 0;
        while (at < topCount) {
            long key = tops[at] >>> 12;
            int end = at;
            while (end < topCount && tops[end] >>> 12 == key) {
                end++;
            }
            int layer = (int) (key >>> 37);
            int material = (int) (key >>> 24) & 0x1FFF;
            int y = (int) (key >>> 12) & 0xFFF;
            int light = (int) (key >>> 8) & 15;
            int occlusion = (int) key & 0xFF;
            int group = TileMesh.group(layer, FaceRecord.UP);
            Arrays.fill(mask, 0);
            for (int i = at; i < end; i++) {
                mask[(int) (tops[i] >>> 6) & 63] |= 1L << (tops[i] & 63);
            }
            // Occlusion is interpolated between a face's corners, so faces join only along an
            // axis on which their corners agree: both axes when no corner is closed in, one when
            // the occlusion changes across the other alone (ground along a wall), none otherwise.
            boolean alongX = (occlusion & 3) == (occlusion >> 2 & 3) && (occlusion >> 4 & 3) == (occlusion >> 6 & 3);
            boolean alongZ = (occlusion & 3) == (occlusion >> 4 & 3) && (occlusion >> 2 & 3) == (occlusion >> 6 & 3);
            for (int z = 0; z < RunTile.CELLS; z++) {
                while (mask[z] != 0) {
                    int x = Long.numberOfTrailingZeros(mask[z]);
                    int width = alongX ? Long.numberOfTrailingZeros(~(mask[z] >>> x)) : 1;
                    long bits = (width == 64 ? -1L : (1L << width) - 1) << x;
                    int depth = 1;
                    while (alongZ && z + depth < RunTile.CELLS && (mask[z + depth] & bits) == bits) {
                        depth++;
                    }
                    for (int i = 0; i < depth; i++) {
                        mask[z + i] &= ~bits;
                    }
                    mesh.add(group,
                            FaceRecord.flat(x, z, y, width, depth, FaceRecord.UP, material, light, occlusion));
                }
            }
            at = end;
        }
    }
}
