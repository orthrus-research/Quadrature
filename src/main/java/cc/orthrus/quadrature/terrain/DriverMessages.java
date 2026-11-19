package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageCallback;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A diagnostic: logs what the driver says about the calls it is given. Off unless
 * {@code quadrature.glDebug} is set. A distinct message is logged when first seen and again each time
 * its count reaches a power of ten, so a message that repeats cannot fill the log; no more than a
 * bound of distinct messages is kept. The driver's account of where it keeps a buffer arrives this way.
 *
 * <p>Messages are asked for synchronously, so that each arrives during the call it is about and can
 * be marked with the part of the frame that made the call.
 */
final class DriverMessages {

    static final boolean ON = Boolean.getBoolean("quadrature.glDebug");

    private static final int DISTINCT_LIMIT = 50_000;
    private static final int GROUP_ID = 0x5154;
    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
    /** Kept so that the callback is not collected while the driver holds it. */
    private static GLDebugMessageCallback callback;
    /** The part of the frame now issuing calls, or null outside any that is marked. */
    private static volatile String part;

    private DriverMessages() {
    }

    /** Asks the driver for every message, of any severity. Call once, with the context current. */
    static void install() {
        if (!ON || callback != null) {
            return;
        }
        callback = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
            if (type == GL43.GL_DEBUG_TYPE_PUSH_GROUP || type == GL43.GL_DEBUG_TYPE_POP_GROUP) {
                return;
            }
            String during = part;
            String text = (during == null ? "elsewhere" : "during " + during) + ", source 0x"
                + Integer.toHexString(source) + " type 0x" + Integer.toHexString(type) + " severity 0x"
                + Integer.toHexString(severity) + " id " + id + ": "
                + GLDebugMessageCallback.getMessage(length, message);
            AtomicLong count = COUNTS.get(text);
            if (count == null) {
                if (COUNTS.size() >= DISTINCT_LIMIT) {
                    return;
                }
                count = COUNTS.computeIfAbsent(text, key -> new AtomicLong());
            }
            long seen = count.incrementAndGet();
            if (powerOfTen(seen)) {
                Quadrature.LOGGER.info("Driver message, seen {}: {}", seen, text);
            }
        });
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT);
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        GL43.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, (int[]) null, true);
        GL43.glDebugMessageCallback(callback, 0L);
        Quadrature.LOGGER.info("Driver messages are logged: each distinct one at its first, tenth, hundredth ... time.");
    }

    /** Marks the calls from here to {@link #leave} as a named part of the frame, in a debug group. */
    static void enter(String name) {
        if (callback != null) {
            GL43.glPushDebugGroup(GL43.GL_DEBUG_SOURCE_APPLICATION, GROUP_ID, name);
            part = name;
        }
    }

    static void leave() {
        if (callback != null) {
            part = null;
            GL43.glPopDebugGroup();
        }
    }

    /**
     * How many times the driver has said it is copying or moving a buffer from one kind of memory to
     * another, by the buffer's name, to a kind named in the message ("HOST", "DMA CACHED") or to any where
     * {@code to} is null. 0 where messages are not asked for.
     */
    static long moves(int buffer, String to) {
        String object = "Buffer object " + buffer + " ";
        String destination = to == null ? "" : "to " + to + " memory";
        long moves = 0;
        for (Map.Entry<String, AtomicLong> entry : COUNTS.entrySet()) {
            String text = entry.getKey();
            if (text.contains(object) && text.contains("copied/moved") && text.contains(destination)) {
                moves += entry.getValue().get();
            }
        }
        return moves;
    }

    private static boolean powerOfTen(long n) {
        while (n % 10 == 0) {
            n /= 10;
        }
        return n == 1;
    }
}
