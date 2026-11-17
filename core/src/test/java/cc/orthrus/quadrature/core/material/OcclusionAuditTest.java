package cc.orthrus.quadrature.core.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OcclusionAuditTest {

    @Test
    void aFaceIsShadedByTheWayItIsTurned() {
        assertEquals(0.5f, OcclusionAudit.shade(0, -1, 0), 1e-6f);
        assertEquals(1.0f, OcclusionAudit.shade(0, 1, 0), 1e-6f);
        assertEquals(0.8f, OcclusionAudit.shade(0, 0, -1), 1e-6f);
        assertEquals(0.8f, OcclusionAudit.shade(0, 0, 1), 1e-6f);
        assertEquals(0.6f, OcclusionAudit.shade(-1, 0, 0), 1e-6f);
        assertEquals(0.6f, OcclusionAudit.shade(1, 0, 0), 1e-6f);
    }

    @Test
    void theOcclusionIsWhatIsLeftOfTheColorWithoutTintAndShading() {
        // A face to the west, tinted 0.8 at most, with two of its four front cells closed: 0.6 times 0.8 times 0.6.
        float west = OcclusionAudit.shade(-1, 0, 0);
        assertEquals(0.6f, OcclusionAudit.occlusion(0.6f * 0.8f * 0.6f, 0.8f, west, true, true), 1e-6f);
        // The same color on a quad the game did not shade by its turn holds no shading to take out.
        assertEquals(0.36f, OcclusionAudit.occlusion(0.6f * 0.8f * 0.6f, 0.8f, west, true, false), 1e-6f);
        // A quad that was not lit smoothly had nothing multiplied in.
        assertEquals(1f, OcclusionAudit.occlusion(0.3f, 1f, 1f, false, true), 0f);
        // A black tint leaves nothing to divide by, and rounding never carries the result past 1.
        assertEquals(1f, OcclusionAudit.occlusion(0f, 0f, 1f, true, true), 0f);
        assertEquals(1f, OcclusionAudit.occlusion(0.61f, 1f, 0.6f, true, true), 0f);
        // A top face in the open, untinted, is whole.
        assertEquals(1f, OcclusionAudit.occlusion(1f, 1f, OcclusionAudit.shade(0, 1, 0), true, true), 0f);
        // A face inside foliage: every one of its four front cells closed.
        assertEquals(0.2f, OcclusionAudit.occlusion(0.2f * 0.8f, 1f, OcclusionAudit.shade(0, 0, -1), true, true), 1e-6f);
    }

    @Test
    void cornersAreCountedByTurnAndFrontAndAFaultIsOnlyBelowTheLeastWithAnOpenFront() {
        OcclusionAudit audit = new OcclusionAudit();
        audit.corner(1, OcclusionAudit.FRONT_OPEN, 1f);
        audit.corner(1, OcclusionAudit.FRONT_OPEN, 0.4f);
        // One level of 255 under the least is the least as a color holds it.
        audit.corner(1, OcclusionAudit.FRONT_OPEN, 0.4f - 0.5f / 255f);
        assertEquals(0, audit.faulty());
        // Inside foliage 0.2 is what the game gives, and no fault.
        audit.corner(2, OcclusionAudit.FRONT_FOLIAGE, 0.2f);
        audit.corner(2, OcclusionAudit.FRONT_OTHER, 0.2f);
        assertEquals(0, audit.faulty());
        // With the front cell open it would be one.
        audit.corner(4, OcclusionAudit.FRONT_OPEN, 0.2f);
        assertEquals(1, audit.faulty());
        assertEquals(6, audit.corners());
        assertEquals(1, audit.count(1, OcclusionAudit.FRONT_OPEN, 10));
        assertEquals(2, audit.count(1, OcclusionAudit.FRONT_OPEN, 4));
        assertEquals(1, audit.count(2, OcclusionAudit.FRONT_FOLIAGE, 2));
        assertEquals(0.2f, audit.least(2, OcclusionAudit.FRONT_FOLIAGE), 0f);
        assertEquals(Float.POSITIVE_INFINITY, audit.least(0, OcclusionAudit.FRONT_OPEN), 0f);
        assertTrue(audit.summary().contains("6 corners"));
        assertTrue(audit.summary().contains("1 below 0.4"));
        assertTrue(audit.summary().contains("front foliage: 1, least 0.200"));
        assertTrue(audit.table().contains("north"));
    }
}
