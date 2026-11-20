package cc.orthrus.quadrature.core.handoff;

import java.nio.file.Path;

/**
 * A pass evaluated on another graphics API, on images shared with the OpenGL frame (D21): OpenGL renders
 * into the shared images and signals, the other API evaluates and signals back, and OpenGL finishes the
 * frame at output resolution. The host finds an implementation through {@link java.util.ServiceLoader};
 * when the module that holds one is not installed there is none, and the frame runs without it.
 *
 * <p>Every call comes on the client thread with the game's OpenGL context current. An implementation
 * leaves every piece of OpenGL state it touches as it found it.
 */
public interface Handoff {

    /** What the host gives a handoff. */
    interface Host {
        void info(String message);

        void warn(String message);

        /** The setting {@code quadrature.handoff.<name>}, or the fallback when it is not set. */
        String option(String name, String fallback);

        /** A folder of the handoff's own in the client's directory. It exists. */
        Path outputDirectory();
    }

    /**
     * The game's framebuffer as the world pass leaves it, color and depth at output resolution.
     *
     * @param number            frames handed over so far, from 0
     * @param framebuffer       the framebuffer object's name
     * @param colorTexture      its color texture
     * @param depthRenderbuffer its depth renderbuffer, 0 when it has none
     */
    record Frame(long number, int framebuffer, int colorTexture, int depthRenderbuffer, int width, int height) {
    }

    /**
     * The scaler's picture at render resolution, handed over in place of its own temporal and scale passes:
     * the world as drawn with this frame's sub-pixel offset, its depth, and each pixel's motion, with the
     * output's framebuffer to write the result into.
     *
     * @param number            evaluations so far, from 0
     * @param framebuffer       the drawn framebuffer: color and depth at render resolution
     * @param depthRenderbuffer the drawn framebuffer's depth renderbuffer
     * @param motionTexture     {@code RG16F} at render resolution: where each pixel's point lay in the previous
     *                          frame minus where it lies now, in pixels, with rows counted as OpenGL counts them
     *                          and without the offset
     * @param jitterX           the offset the projection was moved by this frame, in render pixels, -0.5 to 0.5
     * @param reset             no history applies: the first frame, a teleport or another dimension
     * @param depthInverted     depth runs from 1 at the near plane to 0 at the far one
     * @param output            the framebuffer the result is written into, at output resolution
     */
    record Evaluation(long number, int framebuffer, int depthRenderbuffer, int motionTexture, int renderWidth,
                      int renderHeight, double jitterX, double jitterY, boolean reset, boolean depthInverted, int output,
                      int outputWidth, int outputHeight) {
    }

    /** A short name for the log. */
    String name();

    /** Opens beside the current OpenGL context. Returns null when it is ready, or why it cannot run. */
    String open(Host host);

    /**
     * Whether this handoff, with the host's settings, takes the scaler's picture through {@link #evaluate} in place
     * of the scaler's own passes, rather than the game's framebuffer through {@link #frame}. Asked before
     * {@link #open}, so a handoff that does not is turned away without opening anything.
     */
    default boolean evaluates(Host host) {
        return false;
    }

    /**
     * One evaluation of the scaler's picture into the output's framebuffer. Returns false where nothing was
     * written, and the scaler makes the picture itself this frame.
     */
    default boolean evaluate(Evaluation evaluation) {
        return false;
    }

    /** One round trip, at the end of the world pass, before the interface is drawn. */
    void frame(Frame frame);

    /** The client's world was replaced. The next frame belongs to another world. */
    void worldChanged();

    /** Releases everything it holds. */
    void close();
}
