package cc.orthrus.quadrature.core.platform;

import java.util.Locale;

/**
 * What the log says when the client runs out of memory. The game catches an out-of-memory error
 * from its own loop, drops the world, shows its memory screen and logs nothing, so without this the
 * only trace is what fails afterward for want of a world.
 */
public final class OutOfMemoryNotice {

    /** The line a log reader searches for. */
    public static final String MARK = "CLIENT OUT OF MEMORY";

    private OutOfMemoryNotice() {
    }

    /**
     * @param where          where the error was caught, as a phrase ("the game loop")
     * @param message        the error's own message, or null
     * @param times          how many were caught since the last notice, this one included
     * @param heapUsed       bytes of heap in use when the notice is written, after the game freed what it could
     * @param heapMax        the heap's limit in bytes
     * @param directCount    direct buffers held
     * @param directUsed     bytes those buffers use
     * @param exiting        whether the client quits after the notice
     */
    public static String text(String where, String message, int times, long heapUsed, long heapMax, long directCount,
        long directUsed, boolean exiting) {
        return String.format(Locale.ROOT, "%s in %s: %s%s; heap %.0f of %.0f MiB in use now, %d direct buffers using "
                + "%.1f MiB; %s", MARK, where, message == null || message.isEmpty() ? "no message" : message,
            times > 1 ? String.format(Locale.ROOT, " (%d caught since the last notice)", times) : "",
            heapUsed / 1048576.0, heapMax / 1048576.0, directCount, directUsed / 1048576.0,
            exiting ? "the client quits now (quadrature.exitOnOutOfMemory)"
                : "the game has dropped the world; set -Dquadrature.exitOnOutOfMemory=true to quit instead");
    }
}
