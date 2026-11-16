package cc.orthrus.quadrature.core.material;

/**
 * What every block state's surface is like where no texture says: eight bytes a state, at the same
 * number the scene for lighting knows the state by.
 *
 * <ol start="0">
 * <li>perceptual roughness, 0 to 255 for 0 to 1</li>
 * <li>reflectance where the surface is not a metal, likewise</li>
 * <li>subsurface, likewise</li>
 * <li>emission strength, likewise</li>
 * <li>flags: bit 0 a conductor, bit 1 foliage</li>
 * <li>the kind the state was given, or 0</li>
 * <li>and 7: kept</li>
 * </ol>
 *
 * <p>The values follow from the state's kind and the light it gives, by the rule in {@link #set}. The
 * kinds come from a list, or for a block the list does not name from what the game says of it.
 */
public final class MaterialTable {

    public static final int STATES = 1 << 16;
    public static final int BYTES_A_STATE = 8;
    public static final int CONDUCTOR = 1;
    public static final int FOLIAGE = 2;

    private static final int ROUGHNESS = 217;
    private static final int REFLECTANCE = 10;
    private static final int SMOOTH = 31;
    private static final int METAL_ROUGHNESS = 82;
    private static final int SUBSURFACE = 140;
    /** Low, since lava is also lit by the block light it gives: more washes it out. */
    private static final int LAVA_EMISSION = 26;

    private final byte[] bytes = new byte[STATES * BYTES_A_STATE];
    private long version;

    public MaterialTable() {
        for (int state = 0; state < STATES; state++) {
            bytes[state * BYTES_A_STATE] = (byte) ROUGHNESS;
            bytes[state * BYTES_A_STATE + 1] = (byte) REFLECTANCE;
        }
    }

    /**
     * Sets a state's values from its kind and the light it gives.
     *
     * <p>Roughness 0.85 and reflectance 0.04, but roughness 0.12 for glass and ice and 0.32 for a metal,
     * which is a conductor. Subsurface 0.55 and the foliage flag for every kind of foliage. Emission
     * 0.10 for lava, none for a plain state, and for any other state the light it gives over 15. Ground
     * cover and plain states have the defaults.
     *
     * @param lightValue the light the state gives, 0 to 15
     */
    public void set(int state, int kind, int lightValue) {
        int at = state * BYTES_A_STATE;
        boolean glassy = kind == MaterialKinds.GLASS || kind == MaterialKinds.ICE;
        boolean metal = kind == MaterialKinds.METAL;
        boolean foliage = MaterialKinds.foliage(kind);
        bytes[at] = (byte) (glassy ? SMOOTH : metal ? METAL_ROUGHNESS : ROUGHNESS);
        bytes[at + 1] = (byte) REFLECTANCE;
        bytes[at + 2] = (byte) (foliage ? SUBSURFACE : 0);
        bytes[at + 3] = (byte) (kind == MaterialKinds.LAVA ? LAVA_EMISSION : kind == MaterialKinds.PLAIN ? 0 : Math.round(Math.max(0, Math.min(15, lightValue)) * 255f / 15f));
        bytes[at + 4] = (byte) ((metal ? CONDUCTOR : 0) | (foliage ? FOLIAGE : 0));
        bytes[at + 5] = (byte) kind;
        version++;
    }

    public float roughness(int state) {
        return (bytes[state * BYTES_A_STATE] & 0xFF) / 255f;
    }

    public float reflectance(int state) {
        return (bytes[state * BYTES_A_STATE + 1] & 0xFF) / 255f;
    }

    public float subsurface(int state) {
        return (bytes[state * BYTES_A_STATE + 2] & 0xFF) / 255f;
    }

    public float emission(int state) {
        return (bytes[state * BYTES_A_STATE + 3] & 0xFF) / 255f;
    }

    public boolean conductor(int state) {
        return (bytes[state * BYTES_A_STATE + 4] & CONDUCTOR) != 0;
    }

    public boolean foliage(int state) {
        return (bytes[state * BYTES_A_STATE + 4] & FOLIAGE) != 0;
    }

    /** The kind a state was given, or 0. */
    public int kind(int state) {
        return bytes[state * BYTES_A_STATE + 5] & 0xFF;
    }

    /** The whole table, {@link #BYTES_A_STATE} bytes a state. */
    public byte[] bytes() {
        return bytes;
    }

    /** Moves on whenever a state is set. */
    public long version() {
        return version;
    }
}
