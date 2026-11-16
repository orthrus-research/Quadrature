package cc.orthrus.quadrature.core.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class StagePlanTest {

    private static final Set<String> FILES = new HashSet<>(Arrays.asList("fullscreen.vert", "lighting.frag", "display.frag",
        "probe.frag", "targetA.rgba8", "depth.f32", "shadowDepth.f32", "table.rgba8", "frame.std140", "expected.rgba32f",
        "expectedDisplay.rgba8", "mask.r8"));
    private static final Set<String> PROGRAMS = new HashSet<>(Arrays.asList("lighting", "display", "probe"));
    private static final Set<String> TOLERANCES = new HashSet<>(Arrays.asList("radiance", "display"));

    private static final String NEAREST = "{\"filter\":\"nearest\",\"wrap\":\"clamp to edge\",\"comparison\":\"none\"}";

    /** A manifest in the shape of the fifth version, with a persistent target at half size besides. */
    private static final String WHOLE = "{ \"manifest\": 5, \"frameBlockBytes\": 592, \"teleportResetBlocks\": 64,"
        + " \"uniformBlocks\": {\"QFrame\": 0}, \"frameBlockFile\": \"frame.std140\", \"shaderStorageBindings\": [],"
        + " \"hostInputs\": ["
        + "  {\"name\":\"targetA\",\"file\":\"targetA.rgba8\",\"format\":\"RGBA8\",\"sizeDivisor\":1,\"sampler\":" + NEAREST + "},"
        + "  {\"name\":\"depth\",\"file\":\"depth.f32\",\"format\":\"R32F depth\",\"sizeDivisor\":1,\"sampler\":" + NEAREST + "},"
        + "  {\"name\":\"lightViewDepth\",\"file\":\"shadowDepth.f32\",\"format\":\"depth array\",\"sampler\":" + NEAREST + "},"
        + "  {\"name\":\"lightViewCompare\",\"file\":\"shadowDepth.f32\",\"format\":\"the same\",\"sampler\":"
        + "{\"filter\":\"linear\",\"wrap\":\"clamp to edge\",\"comparison\":\"less or equal\"}} ],"
        + " \"assets\": [ {\"name\":\"table\",\"file\":\"table.rgba8\",\"kind\":\"2D\",\"format\":\"RGBA8\",\"size\":[128,64],"
        + "\"sampler\":{\"filter\":\"linear\",\"wrap\":\"repeat\",\"comparison\":\"none\"},\"mipmaps\":false} ],"
        + " \"targets\": ["
        + "  {\"name\":\"sceneRadiance\",\"format\":\"RGBA16F\",\"sizeDivisor\":1,\"persistent\":false,\"clear\":\"none\",\"sampler\":"
        + NEAREST + "},"
        + "  {\"name\":\"kept\",\"format\":\"RGBA8\",\"sizeDivisor\":2,\"persistent\":true,\"clear\":\"zero on reset\",\"sampler\":"
        + NEAREST + "} ],"
        + " \"passDefaults\": {\"vertices\":3,\"vertexBuffers\":false,\"blending\":false,\"depthTest\":false,\"depthWrite\":false,"
        + "\"clear\":false},"
        + " \"passes\": ["
        + "  {\"name\":\"keep\",\"program\":\"lighting\",\"vertex\":\"fullscreen.vert\",\"fragment\":\"lighting.frag\","
        + "\"uniformBlocks\":{\"QFrame\":0},\"inputs\":[{\"sampler\":\"qBefore\",\"unit\":0,\"source\":\"history:kept\"}],"
        + "\"outputs\":[{\"location\":0,\"target\":\"kept\"}]},"
        + "  {\"name\":\"lighting\",\"program\":\"lighting\",\"vertex\":\"fullscreen.vert\",\"fragment\":\"lighting.frag\","
        + "\"uniformBlocks\":{\"QFrame\":0},\"inputs\":[{\"sampler\":\"qTargetA\",\"unit\":0,\"source\":\"host:targetA\"},"
        + "{\"sampler\":\"qDepth\",\"unit\":4,\"source\":\"host:depth\"},{\"sampler\":\"qTable\",\"unit\":5,\"source\":\"asset:table\"},"
        + "{\"sampler\":\"qKept\",\"unit\":6,\"source\":\"target:kept\"}],\"outputs\":[{\"location\":0,\"target\":\"sceneRadiance\"}]},"
        + "  {\"name\":\"display\",\"program\":\"display\",\"vertex\":\"fullscreen.vert\",\"fragment\":\"display.frag\","
        + "\"inputs\":[{\"sampler\":\"qSceneRadiance\",\"unit\":0,\"source\":\"target:sceneRadiance\"}],"
        + "\"outputs\":[{\"location\":0,\"target\":\"display\"}]} ],"
        + " \"diagnosticPasses\": ["
        + "  {\"name\":\"probe\",\"program\":\"probe\",\"vertex\":\"fullscreen.vert\",\"fragment\":\"probe.frag\","
        + "\"inputs\":[{\"sampler\":\"qShadowCompare\",\"unit\":7,\"source\":\"host:lightViewCompare\"}],"
        + "\"outputs\":[{\"location\":0,\"target\":\"sceneRadiance\"}]} ],"
        + " \"checks\": ["
        + "  {\"pass\":\"lighting\",\"output\":0,\"expected\":\"expected.rgba32f\",\"mask\":\"mask.r8\",\"tolerance\":\"radiance\"},"
        + "  {\"pass\":\"display\",\"output\":0,\"expected\":\"expectedDisplay.rgba8\",\"mask\":\"mask.r8\",\"tolerance\":\"display\"},"
        + "  {\"pass\":\"probe\",\"output\":0,\"expected\":\"expected.rgba32f\",\"tolerance\":\"radiance\"} ] }";

    @SuppressWarnings("unchecked")
    private static StagePlan plan(String text) {
        return new StagePlan((Map<String, Object>) Json.parse(text), FILES, PROGRAMS, TOLERANCES);
    }

    private static String refused(String from, String to) {
        String changed = WHOLE.replace(from, to);
        assertFalse(changed.equals(WHOLE), from);
        return assertThrows(IllegalArgumentException.class, () -> plan(changed)).getMessage();
    }

    @Test
    void thePlanSaysWhatIsRunAndInWhatOrder() {
        StagePlan plan = plan(WHOLE);
        assertEquals(592, plan.frameBlockBytes());
        assertEquals(64.0, plan.teleportResetBlocks(), 0);
        assertEquals("frame.std140", plan.frameBlockFile());
        assertEquals(4, plan.hostInputs().size());
        // The light view's size is the host's own.
        assertEquals(0, plan.hostInputs().get("lightViewDepth").sizeDivisor);
        assertEquals(StagePlan.Comparison.LESS_OR_EQUAL, plan.hostInputs().get("lightViewCompare").sampler.comparison);
        assertEquals(StagePlan.Filter.LINEAR, plan.hostInputs().get("lightViewCompare").sampler.filter);
        // Two that read alike share a key; one that reads otherwise has another.
        assertEquals(plan.hostInputs().get("targetA").sampler.key(), plan.hostInputs().get("depth").sampler.key());
        assertFalse(plan.hostInputs().get("targetA").sampler.key() == plan.assets().get("table").sampler.key());
        assertEquals(StagePlan.Wrap.REPEAT, plan.assets().get("table").sampler.wrap);
        assertEquals(128, plan.assets().get("table").size[0]);
        assertEquals(2, plan.assets().get("table").dimensions);
        assertTrue(plan.targets().get("kept").persistent);
        assertTrue(plan.targets().get("kept").zeroOnReset);
        assertFalse(plan.targets().get("sceneRadiance").zeroOnReset);

        assertEquals(3, plan.passes().size());
        assertEquals("keep", plan.passes().get(0).name);
        StagePlan.Pass lighting = plan.passes().get(1);
        assertEquals(4, lighting.inputs.size());
        assertEquals(StagePlan.Source.HOST, lighting.inputs.get(0).source);
        assertEquals("targetA", lighting.inputs.get(0).name);
        assertEquals(StagePlan.Source.ASSET, lighting.inputs.get(2).source);
        assertEquals(StagePlan.Source.TARGET, lighting.inputs.get(3).source);
        assertEquals(6, lighting.inputs.get(3).unit);
        assertEquals(StagePlan.Source.HISTORY, plan.passes().get(0).inputs.get(0).source);
        assertEquals(3, lighting.vertices);
        assertEquals(0, lighting.uniformBlocks.get("QFrame"));
        // A pass that names no blocks of its own reads the manifest's.
        assertEquals(0, plan.passes().get(2).uniformBlocks.get("QFrame"));
        assertTrue(plan.passes().get(2).writesDisplay());
        assertFalse(lighting.writesDisplay());
        assertEquals(1, plan.divisor(lighting));
        assertEquals(2, plan.divisor(plan.passes().get(0)));
        assertEquals("sceneRadiance", StagePlan.target(lighting, 0));
        assertEquals(null, StagePlan.target(lighting, 1));

        assertEquals(1, plan.diagnosticPasses().size());
        assertTrue(plan.diagnosticPasses().get(0).diagnostic);
        assertEquals(3, plan.checks().size());
        assertEquals("mask.r8", plan.checks().get(0).mask);
        assertEquals(null, plan.checks().get(2).mask);
        assertEquals("probe", plan.pass("probe").name);
        assertEquals(null, plan.pass("none"));
    }

    @Test
    void aSideIsDividedAndRoundedUp() {
        assertEquals(480, StagePlan.side(480, 1));
        assertEquals(240, StagePlan.side(480, 2));
        assertEquals(241, StagePlan.side(481, 2));
        assertEquals(68, StagePlan.side(270, 4));
        assertEquals(1, StagePlan.side(1, 4));
        // 480 by 270: radiance at 8 bytes a pixel, and the kept target at half size, 4 bytes, twice.
        assertEquals(480L * 270 * 8 + 240L * 135 * 4 * 2, plan(WHOLE).targetBytes(480, 270));
    }

    @Test
    void whatTheHostDoesNotDoIsRefusedByName() {
        assertTrue(refused("\"frameBlockBytes\": 592", "\"frameBlockBytes\": 496").contains("496"));
        assertTrue(refused("\"shaderStorageBindings\": []", "\"shaderStorageBindings\": [3]").contains("shader storage"));
        assertTrue(refused("\"teleportResetBlocks\": 64", "\"teleportResetBlocks\": 0").contains("teleportResetBlocks"));
        assertTrue(refused("\"format\":\"RGBA16F\"", "\"format\":\"RGB9_E5\"").contains("RGB9_E5"));
        assertTrue(refused("\"filter\":\"linear\",\"wrap\":\"repeat\"", "\"filter\":\"cubic\",\"wrap\":\"repeat\"").contains("cubic"));
        assertTrue(refused("\"wrap\":\"repeat\"", "\"wrap\":\"mirror\"").contains("mirror"));
        assertTrue(refused("\"comparison\":\"less or equal\"", "\"comparison\":\"greater\"").contains("greater"));
        assertTrue(refused("\"kind\":\"2D\"", "\"kind\":\"cube\"").contains("cube"));
        assertTrue(refused("\"clear\":\"zero on reset\"", "\"clear\":\"ones\"").contains("ones"));
        assertTrue(refused("\"uniformBlocks\":{\"QFrame\":0},\"inputs\":[{\"sampler\":\"qBefore\"",
            "\"uniformBlocks\":{\"QOther\":1},\"inputs\":[{\"sampler\":\"qBefore\"").contains("QOther"));
        assertTrue(refused("\"vertexBuffers\":false", "\"vertexBuffers\":true").contains("vertex buffers"));
        assertTrue(refused("\"depthTest\":false", "\"depthTest\":true").contains("depth"));
        assertTrue(refused("\"sizeDivisor\":2", "\"sizeDivisor\":0").contains("divisor"));
    }

    @Test
    void aNameThatIsNotThereIsRefused() {
        assertTrue(refused("\"source\":\"host:targetA\"", "\"source\":\"host:targetZ\"").contains("host:targetZ"));
        assertTrue(refused("\"source\":\"asset:table\"", "\"source\":\"asset:other\"").contains("asset:other"));
        assertTrue(refused("\"source\":\"asset:table\"", "\"source\":\"file:table\"").contains("file:table"));
        assertTrue(refused("\"location\":0,\"target\":\"sceneRadiance\"}]},  {\"name\":\"display\"",
            "\"location\":0,\"target\":\"nowhere\"}]},  {\"name\":\"display\"").contains("nowhere"));
        assertTrue(refused("\"program\":\"display\"", "\"program\":\"absent\"").contains("absent"));
        assertTrue(refused("\"fragment\":\"display.frag\"", "\"fragment\":\"absent.frag\"").contains("not listed"));
        assertTrue(refused("\"file\":\"table.rgba8\"", "\"file\":\"absent.rgba8\"").contains("absent.rgba8"));
        assertTrue(refused("{\"pass\":\"display\",\"output\":0", "{\"pass\":\"absent\",\"output\":0").contains("absent"));
        assertTrue(refused("{\"pass\":\"display\",\"output\":0", "{\"pass\":\"display\",\"output\":2").contains("output"));
        assertTrue(refused("\"tolerance\":\"display\"", "\"tolerance\":\"loose\"").contains("loose"));
        assertTrue(refused("\"expected\":\"expectedDisplay.rgba8\"", "\"expected\":\"absent.rgba8\"").contains("not listed"));
        assertTrue(refused("{\"name\":\"display\",\"program\"", "{\"name\":\"lighting\",\"program\"").contains("Two passes"));
    }

    @Test
    void aPassReadsOnlyWhatIsThereToRead() {
        // A target read before any pass of the frame has written it.
        assertTrue(refused("\"source\":\"history:kept\"", "\"source\":\"target:sceneRadiance\"").contains("before any pass"));
        // A target read and written by one pass.
        assertTrue(refused("{\"sampler\":\"qKept\",\"unit\":6,\"source\":\"target:kept\"}],\"outputs\":[{\"location\":0,"
            + "\"target\":\"sceneRadiance\"}]", "{\"sampler\":\"qKept\",\"unit\":6,\"source\":\"target:kept\"}],\"outputs\":"
            + "[{\"location\":0,\"target\":\"kept\"}]").contains("reads kept and writes it"));
        // History of a target that is not kept.
        assertTrue(refused("\"source\":\"history:kept\"", "\"source\":\"history:sceneRadiance\"").contains("history:sceneRadiance"));
        // Two samplers on one unit.
        assertTrue(refused("{\"sampler\":\"qDepth\",\"unit\":4", "{\"sampler\":\"qDepth\",\"unit\":0").contains("unit"));
        // Outputs of two sizes.
        assertTrue(refused("\"outputs\":[{\"location\":0,\"target\":\"kept\"}]",
            "\"outputs\":[{\"location\":0,\"target\":\"kept\"},{\"location\":1,\"target\":\"sceneRadiance\"}]").contains("one size"));
        // The display and something besides.
        assertTrue(refused("\"outputs\":[{\"location\":0,\"target\":\"display\"}]",
            "\"outputs\":[{\"location\":0,\"target\":\"display\"},{\"location\":1,\"target\":\"sceneRadiance\"}]")
            .contains("display"));
    }
}
