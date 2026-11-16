package cc.orthrus.quadrature.core.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShadedGateTest {

    @Test
    void theStageDrawsInEveryFrameTheTargetsStandWhateverTheirGeneration() {
        assertFalse(ShadedGate.draws(null), "no targets published: near terrain's switch is off");
        ShadingTargets targets = new ShadingTargets();
        assertFalse(ShadedGate.draws(targets), "published and not yet made");
        targets.set(7, 1, 2, 3, 4, 5, 1280, 720, 1L << 20, 3);
        // Frame after frame with the generation unchanged: it counts remakes, not frames.
        for (int frame = 0; frame < 5; frame++) {
            assertTrue(ShadedGate.draws(targets), "frame " + frame);
        }
        // Made anew at another size: still drawn.
        targets.set(9, 1, 2, 3, 4, 5, 1920, 1080, 1L << 21, 4);
        assertTrue(ShadedGate.draws(targets));
    }
}
