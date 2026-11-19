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

    /** A short name for the log. */
    String name();

    /** Opens beside the current OpenGL context. Returns null when it is ready, or why it cannot run. */
    String open(Host host);

    /** One round trip, at the end of the world pass, before the interface is drawn. */
    void frame(Frame frame);

    /** The client's world was replaced. The next frame belongs to another world. */
    void worldChanged();

    /** Releases everything it holds. */
    void close();
}
