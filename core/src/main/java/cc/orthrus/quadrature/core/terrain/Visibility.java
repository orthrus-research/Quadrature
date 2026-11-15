package cc.orthrus.quadrature.core.terrain;

import cc.orthrus.quadrature.core.view.View;

import java.util.Arrays;

/**
 * Which cubes a view can see, found once per view per frame and read by every layer drawn for it.
 * Two functions over the same table of cubes:
 *
 * <ul>
 * <li>{@link #search}, for a view from a point inside the world: outward from the cube the eye is in,
 * cube to cube, through faces that see each other;</li>
 * <li>{@link #sweep}, for a view with no such point, as a light's: every cube in the view's volume
 * and reach.</li>
 * </ul>
 *
 * <p>The search takes these steps and no others. From a cube it leaves through a face only if that
 * moves away from the eye's cube on that axis, or the cube is level with it there; so it never doubles
 * back, whichever way it came. It leaves through a face only if that face is joined, inside the cube,
 * to a face it entered by; a cube entered by several faces is left through all that any of them
 * allows. It enters only a cube that is loaded, within the view's reach and inside its volume. These
 * rules admit every path the game's own search can take, so it never leaves out a cube the game draws:
 * the game additionally lets only the first way into a cube decide where the search may go from it,
 * which leaves out cubes that can be seen.
 *
 * <p>One instance serves one view and allocates only when the view's reach grows.
 */
public final class Visibility implements CubeVisitor {

    /** The table of cubes a search reads. */
    public interface Cubes {
        /** The joined faces of a loaded cube, as {@link CubeConnectivity} gives them, or -1 if it is not loaded. */
        int links(int cubeX, int cubeY, int cubeZ);
    }

    /** A set of cubes a sweep reads. */
    public interface CubeSet {
        void each(CubeVisitor visitor);
    }

    private static final int UNKNOWN = -2;
    /** A way into a cube that is no face: the search starts there. */
    private static final int START = 6;

    private int sizeX;
    private int sizeY;
    private int[] links = new int[0];
    private byte[] entered = new byte[0];
    private byte[] inside = new byte[0];
    private int[] queue = new int[0];
    private long[] keys = new long[256];
    private int count;
    private View sweeping;

    /**
     * Finds the cubes the view can see from its eye, nearest first.
     *
     * @return how many, or -1 when the eye's cube is not loaded or not within reach, in which case
     *         nothing is known and the caller should fall back on {@link #sweep}
     */
    public int search(Cubes cubes, View view) {
        count = 0;
        int across = view.across();
        int upward = view.upward();
        int baseX = view.originCubeX() - across;
        int baseY = view.originCubeY() - upward;
        int baseZ = view.originCubeZ() - across;
        sizeX = 2 * across + 1;
        sizeY = 2 * upward + 1;
        int cells = sizeX * sizeY * sizeX;
        if (links.length < cells) {
            links = new int[cells];
            entered = new byte[cells];
            inside = new byte[cells];
            queue = new int[cells * 7];
        }
        Arrays.fill(links, 0, cells, UNKNOWN);
        Arrays.fill(entered, 0, cells, (byte) 0);
        Arrays.fill(inside, 0, cells, (byte) 0);

        int startX = view.eyeCubeX() - baseX;
        int startY = view.eyeCubeY() - baseY;
        int startZ = view.eyeCubeZ() - baseZ;
        if (startX < 0 || startX >= sizeX || startY < 0 || startY >= sizeY || startZ < 0 || startZ >= sizeX
            || cubes.links(view.eyeCubeX(), view.eyeCubeY(), view.eyeCubeZ()) < 0) {
            return -1;
        }
        int head = 0;
        int tail = 0;
        int start = index(startX, startY, startZ);
        entered[start] = (byte) (1 << START);
        add(CubeKey.of(view.eyeCubeX(), view.eyeCubeY(), view.eyeCubeZ()));
        queue[tail++] = start << 3 | START;
        boolean occlusion = view.occlusion();
        while (head < tail) {
            int item = queue[head++];
            int from = item & 7;
            int cell = item >>> 3;
            int x = cell % sizeX;
            int z = cell / sizeX % sizeX;
            int y = cell / (sizeX * sizeX);
            int joined = links[cell];
            if (joined == UNKNOWN) {
                joined = cubes.links(x + baseX, y + baseY, z + baseZ);
                links[cell] = joined;
            }
            for (int face = 0; face < CubeConnectivity.FACES; face++) {
                int dx = CubeConnectivity.stepX(face);
                int dy = CubeConnectivity.stepY(face);
                int dz = CubeConnectivity.stepZ(face);
                // Never back toward the eye's cube.
                if (dx * (x - startX) < 0 || dy * (y - startY) < 0 || dz * (z - startZ) < 0) {
                    continue;
                }
                if (occlusion && from != START && (from == face || !CubeConnectivity.connected(joined, from, face))) {
                    continue;
                }
                int nx = x + dx;
                int ny = y + dy;
                int nz = z + dz;
                if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeX) {
                    continue;
                }
                int next = index(nx, ny, nz);
                int way = 1 << CubeConnectivity.opposite(face);
                if ((entered[next] & way) != 0) {
                    continue;
                }
                if (links[next] == UNKNOWN) {
                    links[next] = cubes.links(nx + baseX, ny + baseY, nz + baseZ);
                }
                if (links[next] < 0) {
                    continue;
                }
                if (inside[next] == 0) {
                    inside[next] = (byte) (view.cubeVisible(nx + baseX, ny + baseY, nz + baseZ) ? 1 : 2);
                }
                if (inside[next] == 2) {
                    continue;
                }
                if (entered[next] == 0) {
                    add(CubeKey.of(nx + baseX, ny + baseY, nz + baseZ));
                }
                entered[next] |= (byte) way;
                queue[tail++] = next << 3 | CubeConnectivity.opposite(face);
            }
        }
        return count;
    }

    /** Finds the cubes of a set that lie within the view's reach and volume, in the set's order. */
    public int sweep(CubeSet cubes, View view) {
        count = 0;
        sweeping = view;
        cubes.each(this);
        sweeping = null;
        return count;
    }

    @Override
    public void cube(int cubeX, int cubeY, int cubeZ) {
        if (sweeping.cubeWithinReach(cubeX, cubeY, cubeZ) && sweeping.cubeVisible(cubeX, cubeY, cubeZ)) {
            add(CubeKey.of(cubeX, cubeY, cubeZ));
        }
    }

    /** How many cubes the last search or sweep found. */
    public int count() {
        return count;
    }

    /** One of them, as a {@link CubeKey}. */
    public long key(int index) {
        return keys[index];
    }

    private int index(int x, int y, int z) {
        return (y * sizeX + z) * sizeX + x;
    }

    private void add(long key) {
        if (count == keys.length) {
            keys = Arrays.copyOf(keys, count * 2);
        }
        keys[count++] = key;
    }
}
