package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.core.platform.GpuMemorySource;
import cc.orthrus.quadrature.core.platform.PlatformProfile;
import cc.orthrus.quadrature.core.platform.PlatformProfile.Profile;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the platform profile from the current graphics context. Call on the thread that owns it. */
final class PlatformReader {

    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)");

    private PlatformReader() {
    }

    static PlatformProfile read() {
        String versionString = string(GL11.GL_VERSION);
        Matcher matcher = VERSION.matcher(versionString);
        int major = 0;
        int minor = 0;
        if (matcher.find()) {
            major = Integer.parseInt(matcher.group(1));
            minor = Integer.parseInt(matcher.group(2));
        }

        // The profile mask exists from 3.2; older contexts have no profiles at all.
        Profile profile = Profile.UNKNOWN;
        if (major > 3 || major == 3 && minor >= 2) {
            int mask = GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK);
            if ((mask & GL32.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0) {
                profile = Profile.COMPATIBILITY;
            } else if ((mask & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0) {
                profile = Profile.CORE;
            }
        }

        Set<String> extensions = new HashSet<>();
        if (major >= 3) {
            int count = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            for (int i = 0; i < count; i++) {
                String name = GL30.glGetStringi(GL11.GL_EXTENSIONS, i);
                if (name != null) {
                    extensions.add(name);
                }
            }
        } else {
            for (String name : string(GL11.GL_EXTENSIONS).split(" ")) {
                if (!name.isEmpty()) {
                    extensions.add(name);
                }
            }
        }

        long memory = memorySource(extensions).read().dedicatedKiB();

        return new PlatformProfile(major, minor, profile, string(GL11.GL_VENDOR), string(GL11.GL_RENDERER),
            versionString, extensions, memory);
    }

    /** The source of GPU memory figures for the driver that offers these extensions (D8). */
    static GpuMemorySource memorySource(Set<String> extensions) {
        return extensions.contains(NvxMemorySource.EXTENSION) ? new NvxMemorySource() : GpuMemorySource.NONE;
    }

    private static String string(int name) {
        String value = GL11.glGetString(name);
        return value == null ? "" : value;
    }
}
