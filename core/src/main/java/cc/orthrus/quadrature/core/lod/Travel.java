package cc.orthrus.quadrature.core.lod;

/** What a capture of distant terrain in travel is decided by: when a viewer jumped, and the delays after it. */
public final class Travel {

    private Travel() {
    }

    /**
     * The delays a setting names, in milliseconds: whole numbers separated by commas, none below 0,
     * each greater than the one before. None for an unset setting or one that does not read so.
     */
    public static long[] delays(String setting) {
        if (setting == null || setting.trim().isEmpty()) {
            return new long[0];
        }
        String[] parts = setting.split(",");
        long[] delays = new long[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                delays[i] = Long.parseLong(parts[i].trim());
                if (delays[i] < 0 || i > 0 && delays[i] <= delays[i - 1]) {
                    return new long[0];
                }
            }
        } catch (NumberFormatException unreadable) {
            return new long[0];
        }
        return delays;
    }

    /**
     * Whether a viewer jumped between two places a frame apart: more than 8 blocks over the ground
     * or in height, which is what a capture tour takes for a new view and no flight covers in a
     * frame.
     */
    public static boolean jumped(double fromX, double fromY, double fromZ, double x, double y, double z) {
        return Math.abs(x - fromX) + Math.abs(z - fromZ) > 8 || Math.abs(y - fromY) > 8;
    }

    /**
     * Whether everything the viewer's place asks for is there: the cube source says its view is
     * settled, the box of cubes the server keeps sent is around the viewer, and every tile wanted
     * is stored with none being built.
     *
     * <p>The cube source's word alone is not enough twice over. It speaks of cubes and not of
     * tiles; and the box it speaks of is centered where the server last moved the player, which
     * trails a jump, so that in the first ticks after one the old place's box can still read as
     * settled. A center within a cube of the viewer's on each axis is the box of this place.
     *
     * @param settled whether the cube source calls its view settled
     * @param heldBox the cube source's held box, {@code {centerX, centerY, centerZ, horizontal,
     *                vertical}}, or null from a server that states none: then {@code settled} stands
     *                for the cubes alone
     * @param cubeX   the viewer's cube
     * @param wanted  tiles the selection asks for, not counting spared ones
     * @param stored  of those, the ones stored
     */
    public static boolean whole(boolean settled, int[] heldBox, int cubeX, int cubeY, int cubeZ, int wanted,
            int stored, int building) {
        if (!settled || wanted <= 0 || stored < wanted || building != 0) {
            return false;
        }
        return heldBox == null || Math.abs((long) cubeX - heldBox[0]) <= 1
            && Math.abs((long) cubeY - heldBox[1]) <= 1 && Math.abs((long) cubeZ - heldBox[2]) <= 1;
    }
}
