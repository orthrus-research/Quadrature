package cc.orthrus.quadrature.core.terrain;

import java.util.function.LongPredicate;

/**
 * Which cubes around the player the client does not hold yet, by where they lie against the player's own cube: a
 * measure of the order cubes arrive in, as the player sees it fill in. The cubes counted lie within {@code across - 1}
 * cubes of the player's column across (a round reach, as a server sends) and within {@code upward - 1} cubes up and
 * down, leaving out the outermost row, which a client may hold or not.
 *
 * <p>Each cube falls in one of three bands, the player's level (no more than one cube up or down), above it or below
 * it, and in one of two rings, near (no more than {@link #NEAR} cubes across) or far.
 */
public final class ArrivalCounts {

    public static final int LEVEL = 0;
    public static final int ABOVE = 1;
    public static final int BELOW = 2;
    public static final int BANDS = 3;
    /** How many cubes across the near ring reaches. */
    public static final int NEAR = 4;

    /** For each band, near then far: cubes counted, and cubes missing. */
    private final int[] counted = new int[BANDS * 2];
    private final int[] missing = new int[BANDS * 2];

    /**
     * Counts anew around a player's cube.
     *
     * @param loaded whether the client holds a cube, by its {@link CubeKey}
     */
    public void count(int cubeX, int cubeY, int cubeZ, int across, int upward, LongPredicate loaded) {
        java.util.Arrays.fill(counted, 0);
        java.util.Arrays.fill(missing, 0);
        int reach = across - 1;
        int height = upward - 1;
        for (int dy = -height; dy <= height; dy++) {
            int band = Math.abs(dy) <= 1 ? LEVEL : dy > 0 ? ABOVE : BELOW;
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    if (dx * dx + dz * dz > reach * reach) {
                        continue;
                    }
                    int ring = Math.max(Math.abs(dx), Math.abs(dz)) <= NEAR ? 0 : 1;
                    int slot = band * 2 + ring;
                    counted[slot]++;
                    if (!loaded.test(CubeKey.of(cubeX + dx, cubeY + dy, cubeZ + dz))) {
                        missing[slot]++;
                    }
                }
            }
        }
    }

    /** Cubes counted in a band and ring (0 near, 1 far). */
    public int counted(int band, int ring) {
        return counted[band * 2 + ring];
    }

    /** Cubes missing in a band and ring (0 near, 1 far). */
    public int missing(int band, int ring) {
        return missing[band * 2 + ring];
    }
}
