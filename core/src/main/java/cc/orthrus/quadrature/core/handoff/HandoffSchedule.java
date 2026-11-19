package cc.orthrus.quadrature.core.handoff;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which part of the handoff runs on each frame of a measurement. The parts take turns in segments of a
 * fixed number of frames, so every part sees the same mix of views, loading and frame rate over a run;
 * the first frames of a segment are left out of the statistics, so a part is not charged for the one
 * before it.
 */
public final class HandoffSchedule {

    /** A part of the handoff, from nothing to the whole round trip. */
    public enum Phase {
        /** Nothing but a timestamp: the frame without the handoff. */
        OFF(false, false, false),
        /** The semaphore round trip and the ownership barriers, with no work on the other API. */
        SYNC(false, true, false),
        /** The round trip with the stand-in evaluated where DLSS would be. */
        STANDIN(false, true, true),
        /** OpenGL's copies into the shared images and back out, with no round trip. */
        COPY(true, false, false),
        /** The copies, the round trip and the stand-in: a whole frame with the bridge. */
        FULL(true, true, true);

        private final boolean copies;
        private final boolean roundTrip;
        private final boolean evaluates;

        Phase(boolean copies, boolean roundTrip, boolean evaluates) {
            this.copies = copies;
            this.roundTrip = roundTrip;
            this.evaluates = evaluates;
        }

        /** OpenGL fills the inputs from the game's frame and copies the output back into it. */
        public boolean copies() {
            return copies;
        }

        /** OpenGL signals the other API and waits for it. */
        public boolean roundTrip() {
            return roundTrip;
        }

        /** The other API runs the stand-in evaluation. */
        public boolean evaluates() {
            return evaluates;
        }
    }

    private final List<Phase> order;
    private final int segment;
    private final int settle;

    /**
     * @param order   the parts, in the order they take turns
     * @param segment frames a part keeps its turn
     * @param settle  frames at the start of each turn left out of the statistics
     */
    public HandoffSchedule(List<Phase> order, int segment, int settle) {
        if (order.isEmpty()) {
            throw new IllegalArgumentException("A schedule needs at least one phase.");
        }
        if (segment < 1 || settle < 0 || settle >= segment) {
            throw new IllegalArgumentException("A segment of " + segment + " frames cannot settle for " + settle + ".");
        }
        this.order = List.copyOf(order);
        this.segment = segment;
        this.settle = settle;
    }

    /** Reads phases from a comma-separated list of their names, in any case. */
    public static HandoffSchedule parse(String phases, int segment, int settle) {
        List<Phase> order = new ArrayList<>();
        for (String name : phases.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                order.add(Phase.valueOf(trimmed.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown handoff phase '" + trimmed + "'; expected one of "
                    + List.of(Phase.values()) + ".", e);
            }
        }
        return new HandoffSchedule(order, segment, settle);
    }

    /** The part that runs on this frame. */
    public Phase phase(long frame) {
        return order.get((int) ((frame / segment) % order.size()));
    }

    /** Whether this frame counts in the statistics: past the settling frames of its turn. */
    public boolean measured(long frame) {
        return frame % segment >= settle;
    }

    /** Frames in one turn of every part. */
    public long cycle() {
        return (long) segment * order.size();
    }

    public List<Phase> order() {
        return order;
    }

    public int segment() {
        return segment;
    }

    public int settle() {
        return settle;
    }
}
