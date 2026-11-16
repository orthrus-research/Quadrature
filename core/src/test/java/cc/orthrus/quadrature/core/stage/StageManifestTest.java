package cc.orthrus.quadrature.core.stage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

class StageManifestTest {

    private static final int ROWS = 3;
    private static final int COLUMNS = 4;

    /** Makes a folder laid out as a stage's is, small: two programs, some inputs, expected outputs and a mask. */
    private static Path generate(String name) throws IOException {
        Path folder = Paths.get("build", "stage-test", name);
        Files.createDirectories(folder);
        try (java.util.stream.Stream<Path> old = Files.list(folder)) {
            for (Path file : (Iterable<Path>) old::iterator) {
                Files.delete(file);
            }
        }
        StringBuilder files = new StringBuilder();
        add(folder, files, "fullscreen.vert", "program source: vertex", "GLSL 4.60 core, UTF-8", null,
            "#version 460 core\nvoid main() { gl_Position = vec4(0.0); }\n".getBytes(StandardCharsets.UTF_8));
        add(folder, files, "lighting.frag", "program source: lighting", "GLSL 4.60 core, UTF-8", null,
            "#version 460 core\nout vec4 o;\nvoid main() { o = vec4(1.0); }\n".getBytes(StandardCharsets.UTF_8));
        add(folder, files, "display.frag", "program source: display", "GLSL 4.60 core, UTF-8", null,
            "#version 460 core\nout vec4 o;\nvoid main() { o = vec4(0.5); }\n".getBytes(StandardCharsets.UTF_8));
        byte[] target = new byte[ROWS * COLUMNS * 4];
        for (int i = 0; i < target.length; i++) {
            target[i] = (byte) (i * 7);
        }
        add(folder, files, "targetA.rgba8", "input: target A", "RGBA8", new int[] {ROWS, COLUMNS, 4}, target);
        ByteBuffer depth = ByteBuffer.allocate(ROWS * COLUMNS * 4).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer radiance = ByteBuffer.allocate(ROWS * COLUMNS * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < ROWS * COLUMNS; i++) {
            depth.putFloat(i / 12f);
            radiance.putFloat(i).putFloat(0.5f).putFloat(0.25f).putFloat(1f);
        }
        add(folder, files, "depth.f32", "input: depth", "float32", new int[] {ROWS, COLUMNS}, depth.array());
        add(folder, files, "expectedRadiance.rgba32f", "expected: radiance", "float32 RGBA", new int[] {ROWS, COLUMNS, 4},
            radiance.array());
        add(folder, files, "shadowEdgeMask.r8", "comparison: mask", "8-bit", new int[] {ROWS, COLUMNS}, new byte[ROWS * COLUMNS]);
        add(folder, files, "frame.std140", "input: frame block", "std140 block", null, new byte[496]);
        String manifest = "{ \"manifest\": 1,\n \"programs\": { \"lighting\": {\"vertex\": \"fullscreen.vert\", \"fragment\": "
            + "\"lighting.frag\"}, \"display\": {\"vertex\": \"fullscreen.vert\", \"fragment\": \"display.frag\"} },\n"
            + " \"samplers\": { \"lighting\": {\"qTargetA\": 0, \"qDepth\": 4}, \"display\": {\"qSceneRadiance\": 0} },\n"
            + " \"byteOrder\": \"little\", \"rowOrder\": \"bottom row first\", \"materialKindsRevision\": 6,\n"
            + " \"tolerance\": { \"radiance\": \"0.004 + 1%\", \"display\": 2, \"shadowEdge\": \"masked\" },\n"
            + " \"report\": null,\n \"files\": [" + files + "\n ] }\n";
        Files.write(folder.resolve(StageManifest.FILE), manifest.getBytes(StandardCharsets.UTF_8));
        return folder;
    }

    private static void add(Path folder, StringBuilder files, String name, String role, String format, int[] shape,
        byte[] bytes) throws IOException {
        Files.write(folder.resolve(name), bytes);
        files.append(files.length() == 0 ? "" : ",").append("\n  { \"file\": \"").append(name).append("\", \"role\": \"")
            .append(role).append("\", \"format\": \"").append(format).append("\", \"shape\": ")
            .append(shape == null ? "[]" : java.util.Arrays.toString(shape)).append(", \"bytes\": ").append(bytes.length)
            .append(", \"sha256\": \"").append(StageManifest.sha256(bytes)).append("\" }");
    }

    @Test
    void theManifestSaysWhatTheFolderHolds() throws IOException {
        StageManifest manifest = StageManifest.read(generate("whole"));
        assertEquals(1, manifest.version());
        assertEquals("fullscreen.vert", manifest.programs().get("lighting").vertex);
        assertEquals("display.frag", manifest.programs().get("display").fragment);
        assertEquals(4, manifest.samplers("lighting").get("qDepth"));
        assertEquals(0, manifest.samplers("display").get("qSceneRadiance"));
        assertTrue(manifest.samplers("none").isEmpty());
        assertEquals(6, manifest.materialKindsRevision());
        assertEquals(8, manifest.files().size());
        assertEquals(3, manifest.withRole("program source:").size());
        assertEquals(3, manifest.withRole("input:").size());
        assertEquals(1, manifest.withRole("expected:").size());
        assertEquals(1, manifest.withRole("comparison:").size());
        StageManifest.Entry radiance = manifest.files().get("expectedRadiance.rgba32f");
        assertArrayEquals(new int[] {ROWS, COLUMNS, 4}, radiance.shape);
        assertEquals(4, radiance.elementBytes());
        assertEquals(1, manifest.files().get("targetA.rgba8").elementBytes());
        assertEquals(0, manifest.files().get("lighting.frag").elementBytes());
        float[] values = StageCompare.floats(manifest.load("expectedRadiance.rgba32f"));
        assertEquals(ROWS * COLUMNS * 4, values.length);
        assertEquals(5f, values[5 * 4], 0f);
        assertEquals(0.25f, values[5 * 4 + 2], 0f);
        assertEquals(496, manifest.load("frame.std140").length);
        assertTrue(new String(manifest.load("lighting.frag"), StandardCharsets.UTF_8).startsWith("#version 460 core"));
    }

    @Test
    void aFileThatIsNotWhatTheManifestSaysIsRefused() throws IOException {
        Path folder = generate("changed");
        StageManifest manifest = StageManifest.read(folder);
        // The same size, another content: the hash differs.
        byte[] depth = Files.readAllBytes(folder.resolve("depth.f32"));
        depth[0] ^= 1;
        Files.write(folder.resolve("depth.f32"), depth);
        assertTrue(assertThrows(IOException.class, () -> manifest.load("depth.f32")).getMessage().contains("sha256"));
        // Another size.
        Files.write(folder.resolve("targetA.rgba8"), new byte[5]);
        assertTrue(assertThrows(IOException.class, () -> manifest.load("targetA.rgba8")).getMessage().contains("bytes"));
        // Gone.
        Files.delete(folder.resolve("display.frag"));
        assertTrue(assertThrows(IOException.class, () -> manifest.load("display.frag")).getMessage().contains("Missing"));
        // Never named.
        Files.write(folder.resolve("extra.bin"), new byte[1]);
        assertThrows(IOException.class, () -> manifest.load("extra.bin"));
    }

    @Test
    void aShapeThatDoesNotGiveTheSizeIsRefused() throws IOException {
        Path folder = generate("shape");
        String text = new String(Files.readAllBytes(folder.resolve(StageManifest.FILE)), StandardCharsets.UTF_8);
        Files.write(folder.resolve(StageManifest.FILE), text.replace("\"shape\": [3, 4], \"bytes\": 48", "\"shape\": [3, 5], \"bytes\": 48")
            .getBytes(StandardCharsets.UTF_8));
        StageManifest manifest = StageManifest.read(folder);
        assertTrue(assertThrows(IOException.class, () -> manifest.load("depth.f32")).getMessage().contains("shape"));
    }

    @Test
    void aNameThatLeadsOutOfTheFolderIsRefused() throws IOException {
        Path folder = generate("names");
        String text = new String(Files.readAllBytes(folder.resolve(StageManifest.FILE)), StandardCharsets.UTF_8);
        for (String name : new String[] {"../depth.f32", "sub/depth.f32", "C:depth.f32", "..\\\\depth.f32", ""}) {
            Files.write(folder.resolve(StageManifest.FILE), text.replace("\"file\": \"depth.f32\"", "\"file\": \"" + name + "\"")
                .getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> StageManifest.read(folder));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonIsReadAndWhatIsNotJsonIsRefused() {
        Map<String, Object> value = (Map<String, Object>) Json.parse(
            " {\"a\": [1, -2.5e1, true, false, null, \"x\\n\\u0041\\\"\"], \"b\": {}, \"c\": []} ");
        List<Object> list = (List<Object>) value.get("a");
        assertEquals(1.0, list.get(0));
        assertEquals(-25.0, list.get(1));
        assertEquals(Boolean.TRUE, list.get(2));
        assertEquals(Boolean.FALSE, list.get(3));
        assertEquals(null, list.get(4));
        assertEquals("x\nA\"", list.get(5));
        assertTrue(((Map<String, Object>) value.get("b")).isEmpty());
        assertTrue(((List<Object>) value.get("c")).isEmpty());
        for (String wrong : new String[] {"", "{", "{\"a\" 1}", "[1,]", "{\"a\": 1} x", "\"open", "[1 2]", "nul"}) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(wrong));
        }
    }

    @Test
    void radianceAgreesWithinItsToleranceAndAlphaExactly() {
        int width = 4;
        int height = 2;
        float[] expected = new float[width * height * 4];
        for (int pixel = 0; pixel < width * height; pixel++) {
            expected[pixel * 4] = pixel;
            expected[pixel * 4 + 1] = 0.5f;
            expected[pixel * 4 + 3] = 1f;
        }
        float[] got = expected.clone();
        assertTrue(StageCompare.radiance(got, expected, null, width, height).passes());
        // Within 0.004 + 1%: 5 may be 5.05.
        got[5 * 4] = 5.05f;
        got[1 * 4 + 1] = 0.5f + 0.008f;
        assertTrue(StageCompare.radiance(got, expected, null, width, height).passes());
        // Past it.
        got[5 * 4] = 5.06f;
        StageCompare.Result result = StageCompare.radiance(got, expected, null, width, height);
        assertFalse(result.passes());
        assertEquals(1, result.differing);
        assertEquals(1, result.worstX);
        assertEquals(1, result.worstY);
        assertEquals(1, result.minX);
        assertEquals(1, result.maxY);
        assertTrue(result.difference[5] != 0);
        assertEquals(0, result.difference[0]);
        // Alpha must be exact, and is counted apart.
        got[5 * 4] = 5f;
        got[2 * 4 + 3] = 0.999f;
        result = StageCompare.radiance(got, expected, null, width, height);
        assertEquals(1, result.differing);
        assertEquals(1, result.alphaOnly);
        // The mask leaves a pixel out.
        byte[] mask = new byte[width * height];
        mask[2] = (byte) 255;
        result = StageCompare.radiance(got, expected, mask, width, height);
        assertTrue(result.passes());
        assertEquals(1, result.masked);
        assertEquals(7, result.compared);
        // Not a number never agrees.
        got[0] = Float.NaN;
        assertFalse(StageCompare.radiance(got, expected, mask, width, height).passes());
    }

    @Test
    void displayAgreesWithinTwoLevels() {
        int width = 3;
        int height = 1;
        byte[] expected = {10, 20, 30, (byte) 255, (byte) 200, (byte) 250, 0, (byte) 255, 0, 0, 0, (byte) 255};
        byte[] got = expected.clone();
        got[0] = 12;
        got[5] = (byte) 248;
        assertTrue(StageCompare.display(got, expected, null, width, height).passes());
        got[0] = 13;
        StageCompare.Result result = StageCompare.display(got, expected, null, width, height);
        assertEquals(1, result.differing);
        assertEquals(3.0, result.worst, 0);
        assertEquals(0, result.worstX);
        got[0] = 10;
        got[11] = (byte) 250;
        result = StageCompare.display(got, expected, null, width, height);
        assertEquals(1, result.differing);
        assertEquals(1, result.alphaOnly);
        assertTrue(result.toString().startsWith("FAIL"));
    }

    @Test
    void theTolerancesAreTheManifestsWhereItStatesThemAndTheWrittenRuleWhereNot() throws IOException {
        Path folder = generate("tolerance");
        StageManifest old = StageManifest.read(folder);
        // Version 1 states them as words: the written rule stands.
        assertEquals(0.004, old.radianceAbsolute(), 1e-9);
        assertEquals(0.01, old.radianceRelative(), 1e-9);
        assertTrue(old.radianceAlphaExact());
        assertEquals(2, old.displayLevels());
        assertTrue(old.displayAlphaCompared());
        assertEquals("shadowEdgeMask.r8", old.maskFile());
        assertEquals(0, old.maskComparedValue());
        String text = new String(Files.readAllBytes(folder.resolve(StageManifest.FILE)), StandardCharsets.UTF_8);
        String stated = text.replace("\"manifest\": 1", "\"manifest\": 2").replace(
            "\"tolerance\": { \"radiance\": \"0.004 + 1%\", \"display\": 2, \"shadowEdge\": \"masked\" }",
            "\"tolerance\": { \"radiance\": { \"absolute\": 0.5, \"relative\": 0.25, \"alphaExact\": false, \"rule\": \"x\" }, "
                + "\"display\": { \"maxCodeValueDifference\": 7, \"alphaCompared\": false, \"rule\": \"y\" }, "
                + "\"shadowEdge\": { \"maskFile\": \"shadowEdgeMask.r8\", \"maskedValue\": 255, \"comparedValue\": 3 } }");
        assertTrue(!stated.equals(text));
        Files.write(folder.resolve(StageManifest.FILE), stated.getBytes(StandardCharsets.UTF_8));
        StageManifest manifest = StageManifest.read(folder);
        assertEquals(2, manifest.version());
        assertEquals(0.5, manifest.radianceAbsolute(), 0);
        assertEquals(0.25, manifest.radianceRelative(), 0);
        assertFalse(manifest.radianceAlphaExact());
        assertEquals(7, manifest.displayLevels());
        assertFalse(manifest.displayAlphaCompared());
        assertEquals(3, manifest.maskComparedValue());
        // The third version is read as the second is.
        Files.write(folder.resolve(StageManifest.FILE), text.replace("\"manifest\": 1", "\"manifest\": 3").getBytes(StandardCharsets.UTF_8));
        assertEquals(3, StageManifest.read(folder).version());
        // The fourth was never handed over, and a version later than the newest that is read is not known: both are refused.
        Files.write(folder.resolve(StageManifest.FILE), text.replace("\"manifest\": 1", "\"manifest\": 6").getBytes(StandardCharsets.UTF_8));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> StageManifest.read(folder)).getMessage().contains("version 6"));
        // A manifest before the fifth does not say what is to be run.
        assertEquals(null, old.plan());
        Files.write(folder.resolve(StageManifest.FILE), text.replace("\"manifest\": 1", "\"manifest\": 4").getBytes(StandardCharsets.UTF_8));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> StageManifest.read(folder)).getMessage().contains("version 4"));
    }

    @Test
    void theStatedTolerancesDecideAndAlphaIsExactOrComparedAsColor() {
        float[] expected = {1f, 1f, 1f, 1f};
        float[] got = {1.2f, 1f, 1f, 0f};
        assertFalse(StageCompare.radiance(got, expected, null, 0, 1, 1, 0.004, 0.01, true).passes());
        // Alpha that need not be exact is compared as a color channel is: 0 is not within 0.25 of 1.
        assertFalse(StageCompare.radiance(got, expected, null, 0, 1, 1, 0.15, 0.1, false).passes());
        assertTrue(StageCompare.radiance(new float[] {1.2f, 1f, 1f, 0.8f}, expected, null, 0, 1, 1, 0.15, 0.1, false).passes());
        assertFalse(StageCompare.radiance(new float[] {1.2f, 1f, 1f, 0.8f}, expected, null, 0, 1, 1, 0.15, 0.1, true).passes());
        byte[] want = {10, 10, 10, (byte) 255};
        byte[] has = {15, 10, 10, 0};
        assertFalse(StageCompare.display(has, want, null, 0, 1, 1, 2, false).passes());
        assertTrue(StageCompare.display(has, want, null, 0, 1, 1, 5, false).passes());
        assertFalse(StageCompare.display(has, want, null, 0, 1, 1, 5, true).passes());
        // A mask's value that is not the compared one leaves the pixel out.
        assertTrue(StageCompare.display(has, want, new byte[] {(byte) 255}, 0, 1, 1, 2, true).passes());
        assertFalse(StageCompare.display(has, want, new byte[] {3}, 3, 1, 1, 2, true).passes());
    }
}
