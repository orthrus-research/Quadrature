package cc.orthrus.quadrature.core.fade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

class FadePatternTest {

    @Test
    void exactlyOneSideKeepsEveryPixelAtEveryFade() {
        for (int step = -4; step <= 132; step++) {
            float fade = step / 128f;
            for (int frame = 0; frame < 20; frame++) {
                for (int y = 0; y < 9; y++) {
                    for (int x = 0; x < 9; x++) {
                        assertNotEquals(FadePattern.keeps(x, y, fade, false, frame),
                            FadePattern.keeps(x, y, fade, true, frame), "pixel " + x + " " + y + " fade " + fade);
                    }
                }
            }
        }
    }

    @Test
    void noFadeIsAllTheOtherSideAndFullFadeAllThePrimary() {
        for (int frame = 0; frame < 16; frame++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    assertFalse(FadePattern.keeps(x, y, 0f, false, frame));
                    assertTrue(FadePattern.keeps(x, y, 0f, true, frame));
                    assertTrue(FadePattern.keeps(x, y, 1f, false, frame));
                    assertFalse(FadePattern.keeps(x, y, 1f, true, frame));
                }
            }
        }
    }

    @Test
    void theShareKeptFollowsTheFadeInSixteenths() {
        int before = 0;
        for (int step = 0; step <= 64; step++) {
            float fade = step / 64f;
            int kept = 0;
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    kept += FadePattern.keeps(x, y, fade, false, 0) ? 1 : 0;
                }
            }
            // A place is kept once the fade passes its threshold, n + a half sixteenths.
            assertEquals(Math.max(0, Math.min(16, (int) Math.ceil(fade * 16 - 0.5))), kept, "fade " + fade);
            assertTrue(kept >= before, "a larger fade keeps no fewer pixels");
            before = kept;
        }
    }

    @Test
    void aPixelKeptAtAFadeIsKeptAtEveryLargerOne() {
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                boolean seen = false;
                for (int step = 0; step <= 32; step++) {
                    boolean kept = FadePattern.keeps(x, y, step / 32f, false, 0);
                    assertTrue(kept || !seen, "pixel " + x + " " + y + " does not flicker as the fade rises");
                    seen |= kept;
                }
            }
        }
    }

    @Test
    void thePatternRepeatsEveryFourPixelsAndVisitsEveryPlaceInSixteenFrames() {
        assertEquals(FadePattern.threshold(1, 2), FadePattern.threshold(5, 6));
        assertEquals(FadePattern.threshold(3, 0), FadePattern.threshold(-1, 4));
        for (float fade : new float[] {0.25f, 0.5f, 0.8125f}) {
            int frames = 0;
            for (int frame = 0; frame < 16; frame++) {
                frames += FadePattern.keeps(7, 3, fade, false, frame) ? 1 : 0;
            }
            assertEquals(Math.round(fade * 16), frames, "over 16 frames a pixel shows the primary in fade of them");
        }
        assertEquals(FadePattern.keeps(2, 1, 0.4f, false, 5), FadePattern.keeps(2, 1, 0.4f, false, 21));
    }

    @Test
    void thresholdsAreTheSixteenOddThirtySeconds() {
        boolean[] seen = new boolean[16];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                float threshold = FadePattern.threshold(x, y);
                int n = Math.round(threshold * 16 - 0.5f);
                assertEquals((n + 0.5f) / 16f, threshold);
                assertFalse(seen[n]);
                seen[n] = true;
            }
        }
    }

    @Test
    void theShaderTextCarriesTheSameThresholdsInTheSameOrder() {
        String glsl = FadePattern.glsl();
        assertTrue(glsl.contains("bool " + FadePattern.GLSL_FUNCTION + "(vec2 window, float fade, bool complement, int frame)"));
        Matcher array = Pattern.compile("float\\[16\\]\\(([^)]*)\\)").matcher(glsl);
        assertTrue(array.find());
        String[] values = array.group(1).split(",");
        assertEquals(16, values.length);
        for (int i = 0; i < 16; i++) {
            assertEquals(FadePattern.threshold(i % 4, i / 4), Float.parseFloat(values[i].trim()), "place " + i);
        }
        // The shader's rule, written out: the same place, the same comparison, the same complement.
        assertTrue(glsl.contains("ivec2(frame & 3, (frame >> 2) & 3)"));
        assertTrue(glsl.contains("[place.y * 4 + place.x] < fade"));
        assertTrue(glsl.contains("return complement != primary;"));
    }
}
