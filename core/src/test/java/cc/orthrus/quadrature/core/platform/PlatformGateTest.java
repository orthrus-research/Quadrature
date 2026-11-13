package cc.orthrus.quadrature.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.orthrus.quadrature.core.platform.PlatformProfile.Profile;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

final class PlatformGateTest {

    private static final PlatformRequirements REQUIRED = new PlatformRequirements(
        EnumSet.allOf(Capability.class), List.of("NVIDIA"), 8192);

    private static PlatformProfile profile(int major, int minor, Profile profile, String vendor,
                                           Set<String> extensions, long memoryMiB) {
        long memory = memoryMiB < 0 ? PlatformProfile.MEMORY_UNKNOWN : memoryMiB * 1024;
        return new PlatformProfile(major, minor, profile, vendor, "Test Card/PCIe/SSE2", major + "." + minor + ".0 Test",
            extensions, memory);
    }

    private static final Set<String> MESH = Set.of("GL_NV_mesh_shader", "GL_NVX_gpu_memory_info");

    @Test
    void supportedPlatformPasses() {
        assertTrue(PlatformGate.evaluate(
            profile(4, 6, Profile.COMPATIBILITY, "NVIDIA Corporation", MESH, 12288), REQUIRED).isEmpty());
    }

    @Test
    void exactlyTheMemoryFloorPasses() {
        assertTrue(PlatformGate.evaluate(
            profile(4, 6, Profile.COMPATIBILITY, "NVIDIA Corporation", MESH, 8192), REQUIRED).isEmpty());
    }

    @Test
    void crossVendorMeshShaderExtensionSatisfiesTheCapability() {
        assertTrue(PlatformGate.evaluate(profile(4, 6, Profile.COMPATIBILITY, "NVIDIA Corporation",
            Set.of("GL_EXT_mesh_shader"), 8192), REQUIRED).isEmpty());
    }

    @Test
    void sixGigabyteCardIsRefusedForMemoryOnly() {
        List<GateFailure> failures = PlatformGate.evaluate(
            profile(4, 6, Profile.COMPATIBILITY, "NVIDIA Corporation", MESH, 6144), REQUIRED);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).found().contains("6144 MiB"));
    }

    @Test
    void cardWithoutMeshShadersIsRefused() {
        List<GateFailure> failures = PlatformGate.evaluate(
            profile(4, 6, Profile.COMPATIBILITY, "NVIDIA Corporation", Set.of(), 8192), REQUIRED);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).found().contains("mesh shaders"));
    }

    @Test
    void olderVersionAndCoreProfileAreBothReported() {
        List<GateFailure> failures = PlatformGate.evaluate(
            profile(4, 5, Profile.CORE, "NVIDIA Corporation", MESH, 8192), REQUIRED);
        assertEquals(2, failures.size());
        assertTrue(failures.get(0).found().contains("OpenGL 4.5"));
        assertTrue(failures.get(1).found().contains("core profile"));
    }

    @Test
    void otherVendorIsRefusedWithoutHardwareDetailChecks() {
        List<GateFailure> failures = PlatformGate.evaluate(
            profile(4, 6, Profile.COMPATIBILITY, "ATI Technologies Inc.", Set.of(), -1), REQUIRED);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).found().contains("ATI Technologies Inc."));
    }

    @Test
    void unreportedMemoryIsRefused() {
        List<GateFailure> failures = PlatformGate.evaluate(
            profile(4, 6, Profile.COMPATIBILITY, "NVIDIA Corporation", MESH, -1), REQUIRED);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).found().contains("did not report"));
    }

    @Test
    void foreignRendererIsFoundByModIdOrByMarker() {
        List<ForeignRenderer> known = List.of(
            new ForeignRenderer("Alpha", "alpha", null),
            new ForeignRenderer("Beta", null, "beta/Marker.class"),
            new ForeignRenderer("Gamma", "gamma", "gamma/Marker.class"));
        List<GateFailure> failures = PlatformGate.foreignRenderers(known, "alpha"::equals, "beta/Marker.class"::equals);
        assertEquals(2, failures.size());
        assertTrue(failures.get(0).found().startsWith("Alpha"));
        assertTrue(failures.get(1).found().startsWith("Beta"));
    }

    @Test
    void noForeignRendererMeansNoFailure() {
        List<ForeignRenderer> known = List.of(new ForeignRenderer("Alpha", "alpha", "alpha/Marker.class"));
        assertTrue(PlatformGate.foreignRenderers(known, id -> false, resource -> false).isEmpty());
    }
}
