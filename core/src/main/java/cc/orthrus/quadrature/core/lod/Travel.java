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
}
