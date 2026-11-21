package cc.orthrus.quadrature.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.orthrus.quadrature.core.platform.PlatformProfile.Profile;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class DriverFloorTest {

    private static final DriverFloor NVIDIA = new DriverFloor("NVIDIA", 580, 595, List.of("596.36"));
    private static final PlatformRequirements REQUIRED = new PlatformRequirements(
        EnumSet.allOf(Capability.class), List.of("NVIDIA"), 8192, List.of(NVIDIA));
    private static final Set<String> MESH = Set.of("GL_NV_mesh_shader", "GL_NVX_gpu_memory_info");

    private static PlatformProfile card(String versionString, Set<String> extensions) {
        return new PlatformProfile(4, 6, Profile.COMPATIBILITY, "NVIDIA Corporation", "NVIDIA GeForce RTX 3060/PCIe/SSE2",
            versionString, extensions, 12288 * 1024L);
    }

    private static PlatformProfile card(String versionString) {
        return card(versionString, MESH);
    }

    @Test
    void theVersionAndBranchAreReadAsNvidiaWritesThem() {
        assertEquals(new DriverVersion("610.88", 610), DriverVersion.parse("4.6.0 NVIDIA 610.88", "NVIDIA"));
        assertEquals(new DriverVersion("610.57.04", 610), DriverVersion.parse("4.6.0 NVIDIA 610.57.04", "NVIDIA"));
        assertEquals(new DriverVersion("621.14", 621), DriverVersion.parse(" 4.6.0 NVIDIA 621.14 ", "NVIDIA"));
        assertEquals(new DriverVersion("580.88", 580), DriverVersion.parse("4.6.0 nvidia 580.88", "NVIDIA"));
    }

    @Test
    void withoutTheVendorsNameTheLastVersionAfterOpenGlsIsTaken() {
        assertEquals(new DriverVersion("595.99.02", 595), DriverVersion.parse("4.6.0 595.99.02", "NVIDIA"));
        assertEquals(new DriverVersion("24.10.1", 24), DriverVersion.parse("4.6.0 Compatibility Profile Context 24.10.1", null));
    }

    @Test
    void aStringWithNoDriverVersionGivesNone() {
        assertNull(DriverVersion.parse("4.6.0", "NVIDIA"));
        assertNull(DriverVersion.parse("4.6.0 NVIDIA", "NVIDIA"));
        assertNull(DriverVersion.parse(null, "NVIDIA"));
    }

    @Test
    void currentDriversPassWithoutWarning() {
        for (String version : List.of("4.6.0 NVIDIA 610.88", "4.6.0 NVIDIA 610.57.04", "4.6.0 NVIDIA 621.14",
            "4.6.0 NVIDIA 595.99.02")) {
            assertTrue(PlatformGate.evaluate(card(version), REQUIRED).isEmpty(), version);
            assertTrue(PlatformGate.warnings(card(version), REQUIRED).isEmpty(), version);
        }
    }

    @Test
    void aDriverBelowTheFloorIsRefusedWithTheRemedy() {
        // Debian 13's packaged driver.
        List<GateFailure> failures = PlatformGate.evaluate(card("4.6.0 NVIDIA 550.163.01"), REQUIRED);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).found().contains("550.163.01"));
        assertTrue(failures.get(0).required().contains("R580"));
        assertTrue(failures.get(0).remedy().contains("Linux"));
    }

    @Test
    void theFloorBranchItselfPassesAndIsWarnedAbout() {
        assertTrue(PlatformGate.evaluate(card("4.6.0 NVIDIA 581.57"), REQUIRED).isEmpty());
        List<GateFailure> warnings = PlatformGate.warnings(card("4.6.0 NVIDIA 581.57"), REQUIRED);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).found().contains("581.57"));
        assertTrue(warnings.get(0).required().contains("R595"));
    }

    @Test
    void aKnownBadBuildIsRefusedWhateverItsBranch() {
        List<GateFailure> failures = PlatformGate.evaluate(card("4.6.0 NVIDIA 596.36"), REQUIRED);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).found().contains("known to fail"));
    }

    @Test
    void anUnreadableVersionPassesWithAWarning() {
        assertTrue(PlatformGate.evaluate(card("4.6.0"), REQUIRED).isEmpty());
        List<GateFailure> warnings = PlatformGate.warnings(card("4.6.0"), REQUIRED);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).found().contains("could not be read"));
    }

    @Test
    void aMissingCapabilityIsReportedBeforeTheDriver() {
        List<GateFailure> failures = PlatformGate.evaluate(card("4.6.0 NVIDIA 550.163.01", Set.of("GL_NVX_gpu_memory_info")),
            REQUIRED);
        assertEquals(2, failures.size());
        assertTrue(failures.get(0).found().contains("mesh shaders"));
        assertTrue(failures.get(1).found().contains("550.163.01"));
    }

    @Test
    void requirementsWithoutAFloorDoNotCheckTheDriver() {
        PlatformRequirements unfloored = new PlatformRequirements(EnumSet.allOf(Capability.class), List.of("NVIDIA"), 8192);
        assertTrue(PlatformGate.evaluate(card("4.6.0 NVIDIA 470.256.02"), unfloored).isEmpty());
        assertTrue(PlatformGate.warnings(card("4.6.0 NVIDIA 470.256.02"), unfloored).isEmpty());
    }
}
