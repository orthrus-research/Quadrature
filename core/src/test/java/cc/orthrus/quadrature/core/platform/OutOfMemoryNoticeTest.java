package cc.orthrus.quadrature.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OutOfMemoryNoticeTest {

    @Test
    void theNoticeCarriesItsMarkWhereAndMemory() {
        String text = OutOfMemoryNotice.text("the game loop", "Java heap space", 1, 512L << 20, 8192L << 20, 3, 6L << 20,
            false);
        assertTrue(text.startsWith(OutOfMemoryNotice.MARK + " in the game loop: Java heap space;"), text);
        assertTrue(text.contains("heap 512 of 8192 MiB in use now, 3 direct buffers using 6.0 MiB"), text);
        assertTrue(text.endsWith("set -Dquadrature.exitOnOutOfMemory=true to quit instead"), text);
    }

    @Test
    void repeatsAndTheQuitAreSaid() {
        String text = OutOfMemoryNotice.text("a build thread", null, 4, 0, 1L << 30, 0, 0, true);
        assertTrue(text.contains("a build thread: no message (4 caught since the last notice);"), text);
        assertTrue(text.endsWith("the client quits now (quadrature.exitOnOutOfMemory)"), text);
    }

    @Test
    void theMarkIsFixed() {
        assertEquals("CLIENT OUT OF MEMORY", OutOfMemoryNotice.MARK);
    }
}
