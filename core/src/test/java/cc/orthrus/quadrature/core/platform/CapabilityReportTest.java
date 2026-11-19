package cc.orthrus.quadrature.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CapabilityReportTest {

    @Test
    void sectionsAreSeparatedByOneBlankLine() {
        List<String> lines = new CapabilityReport()
            .section("limits").value("GL_MAX_TEXTURE_SIZE", 32768)
            .section("compute").value("GL_MAX_COMPUTE_SHARED_MEMORY_SIZE", 49152)
            .lines();
        assertEquals(List.of("[limits]", "GL_MAX_TEXTURE_SIZE = 32768", "", "[compute]",
            "GL_MAX_COMPUTE_SHARED_MEMORY_SIZE = 49152"), lines);
    }

    @Test
    void errorsAndSkippedQueriesAreRecordedInPlaceOfValues() {
        List<String> lines = new CapabilityReport()
            .error("GL_SUBGROUP_SIZE_KHR", 0x0500)
            .notQueried("GL_MAX_MESH_OUTPUT_VERTICES_NV", "GL_NV_mesh_shader")
            .lines();
        assertEquals("GL_SUBGROUP_SIZE_KHR = error 0x0500", lines.get(0));
        assertEquals("GL_MAX_MESH_OUTPUT_VERTICES_NV = not queried, GL_NV_mesh_shader absent", lines.get(1));
    }

    @Test
    void everyWatchedExtensionGetsOneLineInOrder() {
        List<String> lines = new CapabilityReport().watched(Set.of("GL_NV_mesh_shader", "GL_KHR_debug")).lines();
        assertEquals(CapabilityReport.WATCHED.size(), lines.size());
        assertEquals("GL_NV_mesh_shader = present", lines.get(0));
        assertEquals("GL_EXT_mesh_shader = absent", lines.get(1));
        assertEquals("GL_KHR_debug = present", lines.getLast());
    }

    @Test
    void watchedListHasNoDuplicates() {
        assertEquals(CapabilityReport.WATCHED.size(), new HashSet<>(CapabilityReport.WATCHED).size());
    }

    @Test
    void bitsNameEachSetBitAndShowUnnamedOnesByPosition() {
        assertEquals("0x0", CapabilityReport.bits(0, "basic"));
        assertEquals("0x5 (basic, arithmetic)", CapabilityReport.bits(0b101, "basic", "vote", "arithmetic"));
        assertEquals("0x102 (vote, bit 8)", CapabilityReport.bits(0x102, "basic", "vote"));
    }

    @Test
    void fingerprintIsShortStableAndNotTheIdentifier() {
        byte[] uuid = new byte[16];
        for (int i = 0; i < uuid.length; i++) {
            uuid[i] = (byte) (i * 17);
        }
        String once = CapabilityReport.fingerprint(uuid);
        assertEquals(once, CapabilityReport.fingerprint(uuid.clone()));
        assertTrue(once.matches("sha256 [0-9a-f]{8}"));
        assertFalse(once.contains("00112233"));
        uuid[15] ^= 1;
        assertNotEquals(once, CapabilityReport.fingerprint(uuid));
    }

    @Test
    void textEndsInOneNewline() {
        assertEquals("a = 1\n\n[b]\n", new String(CapabilityReport.text(List.of("a = 1", "", "[b]")),
            StandardCharsets.UTF_8));
    }

    @Test
    void noSourceReportsNothing() {
        assertEquals("none", GpuMemorySource.NONE.name());
        assertEquals(GpuMemory.NOT_REPORTED, GpuMemorySource.NONE.read());
        assertEquals(PlatformProfile.MEMORY_UNKNOWN, GpuMemory.NOT_REPORTED.dedicatedKiB());
    }
}
