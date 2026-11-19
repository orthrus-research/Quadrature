package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.platform.OutOfMemoryNotice;

import net.minecraft.client.Minecraft;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Says so when the client runs out of memory. The game catches an out-of-memory error from its loop,
 * frees what it can, drops the world, shows its memory screen and goes on, logging nothing; a lab run
 * then waits for views that never come while it holds the GPU. Here the error is noted where it is
 * caught, allocating nothing, and logged with its stack trace at the start of the loop's next pass,
 * once the game has freed memory. With {@code -Dquadrature.exitOnOutOfMemory=true} the client quits
 * after the notice, so a launcher waiting on it fails at once.
 */
public final class OutOfMemoryWatch {

    /** Off unless {@code -Dquadrature.exitOnOutOfMemory=true}: the client quits after the notice. */
    static final boolean EXIT = Boolean.getBoolean("quadrature.exitOnOutOfMemory");
    /**
     * A diagnostic, off unless {@code -Dquadrature.testOutOfMemory=<seconds>}: that many seconds after the
     * first world is entered, the game loop asks for an array no heap can hold, so the notice and the quit
     * can be seen in a real run.
     */
    private static final int TEST_SECONDS = Integer.getInteger("quadrature.testOutOfMemory", 0);

    private static volatile Throwable pending;
    private static volatile String where;
    private static final AtomicInteger caught = new AtomicInteger();
    private static long testDue = Long.MIN_VALUE;
    private static boolean tested;

    private OutOfMemoryWatch() {
    }

    /** Notes an out-of-memory error where it is caught. Allocates nothing; any thread. */
    public static void caught(Throwable error, String place) {
        caught.incrementAndGet();
        if (pending == null) {
            where = place;
            pending = error;
        }
    }

    /** At the start of each pass of the game loop: logs what was caught since the last pass, and quits if asked. */
    public static void pass() {
        test();
        Throwable error = pending;
        if (error == null) {
            return;
        }
        pending = null;
        int times = caught.getAndSet(0);
        try {
            Runtime runtime = Runtime.getRuntime();
            long directCount = 0;
            long directUsed = 0;
            for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if ("direct".equals(pool.getName())) {
                    directCount = pool.getCount();
                    directUsed = pool.getMemoryUsed();
                }
            }
            Quadrature.LOGGER.error(OutOfMemoryNotice.text(where, error.getMessage(), times,
                runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), directCount, directUsed, EXIT), error);
        } catch (Throwable unable) {
            // Memory may still be short: the quit below must not wait on a notice.
            System.err.println(OutOfMemoryNotice.MARK + " in " + where);
        }
        if (EXIT) {
            Minecraft.getMinecraft().shutdown();
        }
    }

    private static void test() {
        if (TEST_SECONDS <= 0 || tested || Minecraft.getMinecraft().world == null) {
            return;
        }
        long now = System.nanoTime();
        if (testDue == Long.MIN_VALUE) {
            testDue = now + TEST_SECONDS * 1_000_000_000L;
            Quadrature.LOGGER.warn("Out-of-memory test: the game loop asks for an array no heap holds in {} s.", TEST_SECONDS);
        } else if (now >= testDue) {
            tested = true;
            long[] impossible = new long[Integer.MAX_VALUE - 8];
            impossible[0] = 1;
        }
    }
}
