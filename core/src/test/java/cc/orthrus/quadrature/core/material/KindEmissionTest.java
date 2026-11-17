package cc.orthrus.quadrature.core.material;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class KindEmissionTest {

    /** Nine colors, from black to nearly white. */
    private static final float[][] COLORS = {
        {0f, 0f, 0f}, {0.05f, 0.10f, 0.02f}, {0.12f, 0.12f, 0.12f}, {0.20f, 0.15f, 0.10f}, {0.30f, 0.25f, 0.05f},
        {0.45f, 0.40f, 0.20f}, {0.65f, 0.50f, 0.30f}, {0.70f, 0.70f, 0.70f}, {1.00f, 0.90f, 0.60f},
    };

    private static void rows(int kind, double[] values, int[] stored) {
        for (int color = 0; color < COLORS.length; color++) {
            float[] c = COLORS[color];
            assertEquals(values[color], KindEmission.of(kind, c[0], c[1], c[2]), 1e-6, "kind " + kind + ", color " + color);
            assertEquals(stored[color], KindEmission.stored(kind, c[0], c[1], c[2]), "kind " + kind + ", color " + color);
        }
    }

    @Test
    void lavaGivesATenthWhateverItsColor() {
        rows(101, new double[] {0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1}, new int[] {26, 26, 26, 26, 26, 26, 26, 26, 26});
    }

    @Test
    void aDimEmitterRisesWithItsColorToTwoThirds() {
        rows(120, new double[] {0, 0, 0, 0, 0.067600, 0.325000, 0.631800, 0.650000, 0.650000},
            new int[] {0, 0, 0, 0, 17, 83, 161, 166, 166});
    }

    @Test
    void aBrightEmitterRisesSoonerToNineTenths() {
        rows(121, new double[] {0, 0, 0, 0.055326, 0.240916, 0.612246, 0.900000, 0.900000, 0.900000},
            new int[] {0, 0, 0, 14, 61, 156, 230, 230, 230});
    }

    @Test
    void everyOtherKindGivesNothing() {
        double[] nothing = new double[9];
        int[] none = new int[9];
        rows(199, nothing, none);
        rows(140, nothing, none);
    }

    /** One branch of the GLSL: a kind and what it returns, a constant or a peak times a smoothstep between two limits. */
    private static final Pattern BRANCH = Pattern.compile(
        "if \\(kind == (\\d+)u\\) \\{ return ([0-9.]+)(?: \\* smoothstep\\(([0-9.]+), ([0-9.]+), m\\))?; \\}");

    /** The branches of the GLSL, by kind: {value} or {most, lower, upper}. */
    private static Map<Integer, float[]> branches() {
        Map<Integer, float[]> found = new TreeMap<>();
        Matcher matcher = BRANCH.matcher(KindEmission.GLSL);
        while (matcher.find()) {
            float[] numbers = matcher.group(3) == null ? new float[] {Float.parseFloat(matcher.group(2))}
                : new float[] {Float.parseFloat(matcher.group(2)), Float.parseFloat(matcher.group(3)),
                    Float.parseFloat(matcher.group(4))};
            assertNull(found.put(Integer.parseInt(matcher.group(1)), numbers), "kind " + matcher.group(1) + " twice");
        }
        return found;
    }

    @Test
    void theShadersConstantsAreTheRulesOwn() {
        Map<Integer, float[]> found = branches();
        assertEquals(java.util.Arrays.asList(KindEmission.LAVA, KindEmission.DIM, KindEmission.BRIGHT),
            new java.util.ArrayList<>(found.keySet()));
        assertArrayEquals(new float[] {KindEmission.LAVA_VALUE}, found.get(KindEmission.LAVA));
        assertArrayEquals(new float[] {KindEmission.DIM_MOST, KindEmission.DIM_LOWER, KindEmission.DIM_UPPER},
            found.get(KindEmission.DIM));
        assertArrayEquals(new float[] {KindEmission.BRIGHT_MOST, KindEmission.BRIGHT_LOWER, KindEmission.BRIGHT_UPPER},
            found.get(KindEmission.BRIGHT));
        // The greatest channel, and nothing for every other kind.
        assertTrue(KindEmission.GLSL.contains("float m = max(linearAlbedo.r, max(linearAlbedo.g, linearAlbedo.b));"));
        assertTrue(KindEmission.GLSL.trim().endsWith("return 0.0;\n}"));
        assertTrue(KindEmission.GLSL.startsWith("float rivenKindEmission(uint kind, vec3 linearAlbedo) {"));
    }

    @Test
    void theShaderGivesTheWorkedValues() {
        // The GLSL's own numbers, with smoothstep as the GLSL 4.60 specification defines it.
        Map<Integer, float[]> found = branches();
        for (int kind : new int[] {101, 120, 121, 140, 199}) {
            for (float[] c : COLORS) {
                float m = Math.max(c[0], Math.max(c[1], c[2]));
                float[] n = found.get(kind);
                float shader = n == null ? 0f : n.length == 1 ? n[0] : n[0] * smoothstep(n[1], n[2], m);
                assertEquals(KindEmission.of(kind, c[0], c[1], c[2]), shader, 1e-7f, "kind " + kind);
                assertEquals(KindEmission.stored(kind, c[0], c[1], c[2]), Math.round(shader * 255f), "kind " + kind);
            }
        }
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    @Test
    void anUnnamedBlockThatGivesLightGoesIntoTheTableAsAnEmitter() {
        MaterialTable table = new MaterialTable();
        table.set(1, MaterialKinds.fallback(MaterialKinds.Family.OTHER, 12), 12);
        table.set(2, MaterialKinds.fallback(MaterialKinds.Family.OTHER, 11), 11);
        table.set(3, MaterialKinds.fallback(MaterialKinds.Family.OTHER, 15), 15);
        table.set(4, MaterialKinds.fallback(MaterialKinds.Family.OTHER, 0), 0);
        assertEquals(KindEmission.BRIGHT, table.kind(1));
        assertEquals(KindEmission.DIM, table.kind(2));
        assertEquals(KindEmission.BRIGHT, table.kind(3));
        assertEquals(MaterialKinds.NONE, table.kind(4));
        // The light value stays filled in byte 3, though the targets no longer read it.
        assertEquals(204, table.bytes()[1 * MaterialTable.BYTES_A_STATE + 3] & 0xFF);
    }

    @Test
    void aBlockThatGivesLightAndIsNotNamedIsBrightFromTwelve() {
        assertEquals(MaterialKinds.NONE, KindEmission.fallbackKind(0));
        assertEquals(KindEmission.DIM, KindEmission.fallbackKind(1));
        assertEquals(KindEmission.DIM, KindEmission.fallbackKind(11));
        assertEquals(KindEmission.BRIGHT, KindEmission.fallbackKind(12));
        assertEquals(KindEmission.BRIGHT, KindEmission.fallbackKind(15));
    }
}
