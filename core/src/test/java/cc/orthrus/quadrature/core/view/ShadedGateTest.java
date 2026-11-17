package cc.orthrus.quadrature.core.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShadedGateTest {

    @Test
    void theStageDrawsExactlyInThePassesTheTargetsWereDrawnIn() {
        assertFalse(ShadedGate.draws(null, 5), "no targets published: near terrain's switch is off");
        ShadingTargets targets = new ShadingTargets();
        assertFalse(ShadedGate.draws(targets, 5), "published and not yet made");
        assertFalse(ShadedGate.draws(targets, -1), "own terrain off, never drawn: -1 is not a pass");
        // Pass after pass with the generation unchanged: it counts remakes, not frames. A stage that
        // waited for it to move would draw once and stop.
        for (long frame = 10; frame < 15; frame++) {
            targets.set(7, 1, 2, 3, 4, 5, 1280, 720, 1L << 20, 3, frame);
            assertTrue(ShadedGate.draws(targets, frame), "pass " + frame);
        }
        // A pass in which near terrain did not draw the targets: they are the last pass's.
        assertFalse(ShadedGate.draws(targets, 15));
        // Made anew at another size and drawn in this pass.
        targets.set(9, 1, 2, 3, 4, 5, 1920, 1080, 1L << 21, 4, 15);
        assertTrue(ShadedGate.draws(targets, 15));
        // Own terrain went off: its count is -1 whatever the targets last held.
        assertFalse(ShadedGate.draws(targets, -1));
    }
}
