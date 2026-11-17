package cc.orthrus.quadrature.core.view;

/**
 * How far near terrain reaches from the eye: the horizontal distance in blocks from the eye to the
 * nearest side of the box of cubes that can be meshed and drawn.
 *
 * <p>Near terrain holds the cubes within a count of cubes of the view's own cube, along x and along
 * z. The outermost of them lack a neighbor beyond and are never meshed, so the cubes that can be drawn
 * end one cube short of those held. The distance is to the nearest of that box's four sides, since
 * that is how far terrain is certain to reach in every direction; toward a corner it reaches farther.
 * It says how far terrain reaches once what is held has been meshed, not how much of it has been.
 */
public final class NearEdge {

    private static final int CUBE = 16;

    private NearEdge() {
    }

    /**
     * @param originCubeX the view's own cube, along x
     * @param across      how many cubes are held to each side of it, along x and along z
     * @param eyeX        where the eye stands, in blocks
     * @return the distance in blocks, never below 0
     */
    public static float blocks(int originCubeX, int originCubeZ, int across, double eyeX, double eyeZ) {
        int reach = Math.max(0, across - 1);
        double lowX = (double) (originCubeX - reach) * CUBE;
        double highX = (double) (originCubeX + reach + 1) * CUBE;
        double lowZ = (double) (originCubeZ - reach) * CUBE;
        double highZ = (double) (originCubeZ + reach + 1) * CUBE;
        double nearest = Math.min(Math.min(eyeX - lowX, highX - eyeX), Math.min(eyeZ - lowZ, highZ - eyeZ));
        return (float) Math.max(0, nearest);
    }
}
