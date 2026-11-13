package cc.orthrus.quadrature.core.frame;

/** Who authors a stage. */
public enum StageKind {
    /** Quadrature's own work. */
    PIPELINE,
    /** The game's or another mod's drawing, run inside the frame unchanged. */
    HOSTED,
    /** Supplied by the selected shader. */
    SHADING,
    /** Added by a mod through the mod interface. */
    MOD
}
