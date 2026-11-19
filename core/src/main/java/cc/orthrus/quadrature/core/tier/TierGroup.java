package cc.orthrus.quadrature.core.tier;

/**
 * The groups of settings a tier sets, as ARCHITECTURE section 12.1 lists them. A group that holds GPU
 * memory takes the lower of the speed and memory tiers; the others take the speed tier (D22).
 */
public enum TierGroup {
    RENDER_SCALE("renderScale", false),
    NEAR_TERRAIN("nearTerrain", true),
    DISTANT_REACH("distantReach", true),
    LIGHT_DEPTH("lightDepth", true),
    SHADOW_FILTER("shadowFilter", false),
    AMBIENT_OCCLUSION("ambientOcclusion", false),
    INDIRECT_LIGHT("indirectLight", false),
    FOG_AND_CLOUDS("fogAndClouds", false);

    private final String key;
    private final boolean holdsMemory;

    TierGroup(String key, boolean holdsMemory) {
        this.key = key;
        this.holdsMemory = holdsMemory;
    }

    /** The group's name in settings and the stored result. */
    public String key() {
        return key;
    }

    /** Whether what the group sets is mostly memory held, rather than work done each frame. */
    public boolean holdsMemory() {
        return holdsMemory;
    }

    /** A group by its key, or null. */
    public static TierGroup byKey(String key) {
        for (TierGroup group : values()) {
            if (group.key.equals(key)) {
                return group;
            }
        }
        return null;
    }
}
