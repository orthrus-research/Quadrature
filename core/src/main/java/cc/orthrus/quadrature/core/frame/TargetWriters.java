package cc.orthrus.quadrature.core.frame;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/**
 * Those that write into the targets for shading besides near terrain, such as distant terrain: each a
 * piece of work called once in every world pass in which the targets were drawn, after near terrain's
 * last opaque layer has written them and before a shading stage reads them.
 *
 * <p>Writers are called in the order they were added. One added or removed while writers are being
 * called is called, or not, from the next pass on: a pass calls those that were there when it began.
 * A writer that throws is removed and reported once, and the writers after it are still called.
 *
 * <p>Not safe for use from more than one thread: writers are added, removed and called on the thread
 * that draws.
 */
public final class TargetWriters {

    private final List<Runnable> writers = new ArrayList<>();

    /** Adds a writer after those already there. One that is there already is not added again. */
    public void add(Runnable writer) {
        if (writer == null) {
            throw new IllegalArgumentException("No writer");
        }
        if (index(writer) < 0) {
            writers.add(writer);
        }
    }

    /** Removes a writer; nothing happens where it is not there. */
    public void remove(Runnable writer) {
        int at = index(writer);
        if (at >= 0) {
            writers.remove(at);
        }
    }

    /** A writer is known by being the same object, whatever it says of equality. */
    private int index(Runnable writer) {
        for (int i = 0; i < writers.size(); i++) {
            if (writers.get(i) == writer) {
                return i;
            }
        }
        return -1;
    }

    public int size() {
        return writers.size();
    }

    /**
     * Calls every writer that is there now, in order.
     *
     * @param before called before each writer with its place in this pass's order, or null
     * @param failed called with a writer that threw and what it threw, after it has been removed, or null
     */
    public void run(IntConsumer before, BiConsumer<Runnable, Throwable> failed) {
        if (writers.isEmpty()) {
            return;
        }
        Runnable[] pass = writers.toArray(new Runnable[0]);
        for (int i = 0; i < pass.length; i++) {
            if (before != null) {
                before.accept(i);
            }
            try {
                pass[i].run();
            } catch (Throwable thrown) {
                remove(pass[i]);
                if (failed != null) {
                    failed.accept(pass[i], thrown);
                }
            }
        }
    }
}
