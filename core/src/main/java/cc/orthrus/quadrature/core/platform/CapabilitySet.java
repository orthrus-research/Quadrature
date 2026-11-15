package cc.orthrus.quadrature.core.platform;

import java.util.EnumSet;
import java.util.Set;

/** The capabilities a platform profile provides. Derived once; nothing here names a vendor. */
public final class CapabilitySet {

    private final Set<Capability> present;

    private CapabilitySet(Set<Capability> present) {
        this.present = present;
    }

    public static CapabilitySet of(PlatformProfile profile) {
        EnumSet<Capability> present = EnumSet.noneOf(Capability.class);
        if (profile.atLeast(4, 6)) {
            present.add(Capability.OPENGL_4_6);
            // Each of these is in the core specification by 4.6. A platform below it would be asked
            // for the extension that carries each one instead.
            present.add(Capability.PERSISTENT_MAPPING);
            present.add(Capability.SYNC_OBJECTS);
            present.add(Capability.MULTI_DRAW_INDIRECT);
            present.add(Capability.SHADER_STORAGE_BUFFERS);
            present.add(Capability.DRAW_PARAMETERS);
        }
        if (profile.profile() == PlatformProfile.Profile.COMPATIBILITY) {
            present.add(Capability.COMPATIBILITY_PROFILE);
        }
        Set<String> extensions = profile.extensions();
        if (extensions.contains("GL_NV_mesh_shader") || extensions.contains("GL_EXT_mesh_shader")) {
            present.add(Capability.MESH_SHADERS);
        }
        return new CapabilitySet(present);
    }

    public boolean has(Capability capability) {
        return present.contains(capability);
    }
}
