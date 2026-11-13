package cc.orthrus.quadrature.core.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Decides whether the game may run Quadrature. This is the one place that reads the vendor:
 * support is admitted per vendor because only an admitted vendor's capability set is tested.
 */
public final class PlatformGate {

    private PlatformGate() {
    }

    /** Every unmet requirement, in a stable order; empty when the platform is supported. */
    public static List<GateFailure> evaluate(PlatformProfile profile, PlatformRequirements requirements) {
        List<GateFailure> failures = new ArrayList<>();
        CapabilitySet capabilities = CapabilitySet.of(profile);

        if (requirements.capabilities().contains(Capability.OPENGL_4_6) && !capabilities.has(Capability.OPENGL_4_6)) {
            failures.add(new GateFailure(
                "The graphics context is OpenGL " + profile.major() + "." + profile.minor() + ".",
                "Quadrature requires OpenGL 4.6.",
                "Update the graphics driver, and check that Cleanroom's OpenGL version setting is 4.6."));
        }
        if (requirements.capabilities().contains(Capability.COMPATIBILITY_PROFILE)
                && !capabilities.has(Capability.COMPATIBILITY_PROFILE)) {
            failures.add(new GateFailure(
                "The graphics context uses the " + profile.profile().name().toLowerCase(Locale.ROOT) + " profile.",
                "Quadrature requires the OpenGL compatibility profile.",
                "Turn Cleanroom's OpenGL compatibility profile setting back on."));
        }
        if (!vendorAdmitted(profile.vendor(), requirements.admittedVendors())) {
            failures.add(new GateFailure(
                "The graphics card is reported as \"" + profile.vendor() + "\" (" + profile.renderer() + ").",
                "Quadrature supports " + String.join(", ", requirements.admittedVendors()) + " graphics cards only.",
                "Run the game on a supported graphics card. On a laptop, select the dedicated card for Java."));
            // Without an admitted vendor the remaining checks would describe hardware Quadrature never tested.
            return failures;
        }
        if (requirements.capabilities().contains(Capability.MESH_SHADERS) && !capabilities.has(Capability.MESH_SHADERS)) {
            failures.add(new GateFailure(
                "The graphics card (" + profile.renderer() + ") does not provide mesh shaders.",
                "Quadrature requires an RTX 20 series card or later.",
                "Use a newer graphics card, or update the graphics driver if the card is an RTX 20 series or later."));
        }
        long memoryMiB = profile.dedicatedMemoryKiB() / 1024;
        if (profile.dedicatedMemoryKiB() == PlatformProfile.MEMORY_UNKNOWN) {
            failures.add(new GateFailure(
                "The graphics driver did not report how much GPU memory the card has.",
                "Quadrature requires " + requirements.minimumMemoryMiB() / 1024 + " GB of GPU memory.",
                "Update the graphics driver."));
        } else if (memoryMiB < requirements.minimumMemoryMiB()) {
            failures.add(new GateFailure(
                "The graphics card (" + profile.renderer() + ") has " + memoryMiB + " MiB of GPU memory.",
                "Quadrature requires " + requirements.minimumMemoryMiB() / 1024 + " GB of GPU memory.",
                "Use a graphics card with more memory."));
        }
        return failures;
    }

    /** One failure per installed renderer Quadrature does not run beside. */
    public static List<GateFailure> foreignRenderers(List<ForeignRenderer> known, Predicate<String> modLoaded,
                                                     Predicate<String> resourcePresent) {
        List<GateFailure> failures = new ArrayList<>();
        for (ForeignRenderer renderer : known) {
            boolean present = renderer.modId() != null && modLoaded.test(renderer.modId())
                || renderer.markerResource() != null && resourcePresent.test(renderer.markerResource());
            if (present) {
                failures.add(new GateFailure(
                    renderer.name() + " is installed.",
                    "Quadrature replaces the game's renderer and does not run beside another one.",
                    "Remove " + renderer.name() + " to use Quadrature, or remove Quadrature to use " + renderer.name() + "."));
            }
        }
        return failures;
    }

    private static boolean vendorAdmitted(String vendor, List<String> admitted) {
        String found = vendor.toLowerCase(Locale.ROOT);
        for (String name : admitted) {
            if (found.startsWith(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
