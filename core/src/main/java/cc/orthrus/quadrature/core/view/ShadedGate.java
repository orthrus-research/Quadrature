package cc.orthrus.quadrature.core.view;

/**
 * Whether a stage that adds opaque surfaces to the shading targets draws in a frame.
 *
 * <p>The targets are drawn by near terrain in every frame its switch for them is on, and it
 * publishes them only then; before their first frame they have no framebuffer. So the stage draws
 * whenever targets are published and have one. Their generation is no sign of a frame: it counts
 * how often they were made anew, and stays as it is from frame to frame. A stage that waited for
 * it to move would draw once and then never again.
 */
public final class ShadedGate {

    private ShadedGate() {
    }

    /** Whether to draw into the targets now: they are published and have been made. */
    public static boolean draws(ShadingTargets targets) {
        return targets != null && targets.framebuffer() != 0;
    }
}
