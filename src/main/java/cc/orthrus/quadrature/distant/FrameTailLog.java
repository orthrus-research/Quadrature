package cc.orthrus.quadrature.distant;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.function.IntSupplier;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.frame.FrameTail;

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * A diagnostic, off unless {@code -Dquadrature.distant.frameTail=true}: once a second, what
 * Cubature's client preview governor reads of the frames, by its own rule, and what it does not
 * say. The governor takes the time from one frame's start to the next, which holds the world
 * pass, the client ticks run before the next frame, the screen and the swap, keeps the last 120,
 * and cuts the jobs it lets run at once when their 95th percentile reaches its budget (25 ms as
 * configured by default) while raising only under four fifths of it. This logs that percentile,
 * how many of the 120 are at or over each mark, the worst, how many of the long ones held a
 * client tick and how long the longest such tick was, the time the collectors report for the
 * second, and how many jobs the pool lets run.
 *
 * <p>It reads the clock at each frame's start and at each client tick's two ends and writes two
 * array cells a frame. The line is made once a second.
 */
public final class FrameTailLog {

    static final boolean ENABLED = Boolean.getBoolean("quadrature.distant.frameTail");

    /** The governor's default budget and its raise mark; a changed configuration is not read here. */
    private static final double BUDGET_MS = 25;
    private static final double RAISE_MS = 20;

    private final FrameTail tail = new FrameTail();
    private final FrameTail.Reading reading = new FrameTail.Reading();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final IntSupplier atOnce;
    private long lastFrame;
    private long tickStart;
    private long tickNanos;
    private long lastLine;
    private long lastCollected = collected();

    /** @param atOnce how many jobs the preview pool lets run now, 0 where there is no pool */
    FrameTailLog(IntSupplier atOnce) {
        this.atOnce = atOnce;
    }

    @SubscribeEvent
    public void frame(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrame != 0) {
            tail.frame(now - lastFrame, tickNanos);
        }
        tickNanos = 0;
        lastFrame = now;
        if (now - lastLine >= 1_000_000_000L) {
            lastLine = now;
            line();
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        long now = System.nanoTime();
        if (event.phase == TickEvent.Phase.START) {
            tickStart = now;
        } else if (tickStart != 0) {
            tickNanos += now - tickStart;
        }
    }

    private void line() {
        tail.read(reading, RAISE_MS, BUDGET_MS);
        long total = collected();
        long collecting = total - lastCollected;
        lastCollected = total;
        if (reading.frames == 0) {
            return;
        }
        Quadrature.LOGGER.info(String.format("Frame tail: 95th %.1f ms of the last %d frame intervals, %d at or over "
                + "%.0f ms, %d at or over %.0f, worst %.1f; of those over %.0f, %d held a client tick, the longest "
                + "tick %.1f ms; %d frames held a tick, the longest %.1f ms; collectors %d ms in the second; preview "
                + "pool %d at once", reading.tailMs, reading.frames, reading.overRaise, RAISE_MS, reading.overBudget,
            BUDGET_MS, reading.worstMs, BUDGET_MS, reading.overBudgetWithTick, reading.longestTickOverBudgetMs,
            reading.withTick, reading.longestTickMs, collecting, atOnce.getAsInt()));
    }

    private long collected() {
        long total = 0;
        for (int i = 0; i < collectors.size(); i++) {
            total += Math.max(0, collectors.get(i).getCollectionTime());
        }
        return total;
    }
}
