package cc.orthrus.quadrature.core.platform;

import java.util.Set;

/**
 * What the game is running on, read once from the graphics context. Everything else asks
 * {@link CapabilitySet} what the platform can do; only {@link PlatformGate} looks at the vendor.
 *
 * @param dedicatedMemoryKiB total dedicated GPU memory, or {@link #MEMORY_UNKNOWN} if the driver did not report it
 */
public record PlatformProfile(int major, int minor, Profile profile, String vendor, String renderer,
                              String versionString, Set<String> extensions, long dedicatedMemoryKiB) {

    public static final long MEMORY_UNKNOWN = -1;

    public enum Profile { COMPATIBILITY, CORE, UNKNOWN }

    public PlatformProfile {
        extensions = Set.copyOf(extensions);
    }

    public boolean atLeast(int requiredMajor, int requiredMinor) {
        return major > requiredMajor || major == requiredMajor && minor >= requiredMinor;
    }

    /** One line for the log: what was found, in the terms a bug report needs. */
    public String describe() {
        String memory = dedicatedMemoryKiB == MEMORY_UNKNOWN ? "unknown" : dedicatedMemoryKiB / 1024 + " MiB";
        return "OpenGL " + major + "." + minor + " " + profile.name().toLowerCase(java.util.Locale.ROOT)
            + " profile, " + vendor + ", " + renderer + ", " + memory + " GPU memory, driver string \""
            + versionString + "\", " + extensions.size() + " extensions";
    }
}
