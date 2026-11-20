package cc.orthrus.quadrature.frame;

/**
 * A switch for comparing pictures between runs: {@code -Dquadrature.textures.still=true} holds every animated texture
 * of the block atlas at the first frame stitched, so that water, lava, fire and the like look the same in every capture
 * of every run, whatever the time since the client started. Off unless set; for the lab, never for play.
 */
public final class StillTextures {

    public static final boolean ON = Boolean.getBoolean("quadrature.textures.still");

    private StillTextures() {
    }
}
