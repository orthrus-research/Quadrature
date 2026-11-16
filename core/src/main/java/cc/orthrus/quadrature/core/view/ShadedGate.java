package cc.orthrus.quadrature.core.view;

/**
 * Whether a stage that adds opaque surfaces to the shading targets draws in a world pass.
 *
 * <p>Near terrain counts the world passes it has begun and notes, with the targets, the count at
 * which they were last cleared and drawn. The stage draws exactly when the two are equal: the
 * targets are this pass's. Before the first pass, or with own terrain off, either is -1 and the
 * stage does not draw. The targets' generation is no sign of a frame: it counts how often they
 * were made anew, at first use and at a resize, and is for reading sizes and attachments again.
 */
public final class ShadedGate {

    private ShadedGate() {
    }

    /**
     * Whether to draw into the targets now.
     *
     * @param frame the count of world passes near terrain has begun, or -1
     */
    public static boolean draws(ShadingTargets targets, long frame) {
        return targets != null && targets.framebuffer() != 0 && frame != -1 && targets.drawnFrame() == frame;
    }
}
