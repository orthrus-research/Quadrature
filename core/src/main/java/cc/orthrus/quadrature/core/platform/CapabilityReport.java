package cc.orthrus.quadrature.core.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The text of a capability dump: sections of {@code name = value} lines, in the order they were
 * added. The host fills it from the graphics context; this class only formats, so the format is
 * tested without a context.
 */
public final class CapabilityReport {

    /**
     * Extensions whose presence the gap matrix and the quality tiers depend on, in report order:
     * the mesh pipeline, subgroups and shading rate, sparse resources and textures, shader
     * sources, memory reports, interop with Vulkan, and the rest.
     */
    public static final List<String> WATCHED = List.of(
        "GL_NV_mesh_shader", "GL_EXT_mesh_shader",
        "GL_KHR_shader_subgroup", "GL_NV_shader_subgroup_partitioned", "GL_NV_shader_thread_group",
        "GL_NV_shading_rate_image", "GL_NV_primitive_shading_rate",
        "GL_ARB_sparse_buffer", "GL_ARB_sparse_texture", "GL_ARB_sparse_texture2", "GL_ARB_sparse_texture_clamp",
        "GL_ARB_bindless_texture", "GL_EXT_texture_shadow_lod", "GL_ARB_texture_filter_minmax",
        "GL_ARB_shading_language_include", "GL_ARB_gl_spirv", "GL_KHR_parallel_shader_compile",
        "GL_NVX_gpu_memory_info", "GL_ATI_meminfo",
        "GL_EXT_memory_object", "GL_EXT_memory_object_win32", "GL_EXT_memory_object_fd",
        "GL_EXT_semaphore", "GL_EXT_semaphore_win32", "GL_EXT_semaphore_fd", "GL_NV_timeline_semaphore",
        "GL_NV_draw_vulkan_image",
        "GL_ARB_fragment_shader_interlock", "GL_NV_fragment_shader_interlock", "GL_NV_command_list",
        "GL_EXT_shader_realtime_clock", "GL_ARB_compatibility", "GL_KHR_debug");

    private final List<String> lines = new ArrayList<>();

    /** Starts a section; its lines follow until the next. */
    public CapabilityReport section(String name) {
        if (!lines.isEmpty()) {
            lines.add("");
        }
        lines.add("[" + name + "]");
        return this;
    }

    public CapabilityReport value(String name, long value) {
        return value(name, Long.toString(value));
    }

    public CapabilityReport value(String name, String value) {
        lines.add(name + " = " + value);
        return this;
    }

    /** The query raised a GL error, which is recorded instead of a value. */
    public CapabilityReport error(String name, int glError) {
        return value(name, String.format(Locale.ROOT, "error 0x%04X", glError));
    }

    /** The query was not made because the extension that defines it is absent. */
    public CapabilityReport notQueried(String name, String extension) {
        return value(name, "not queried, " + extension + " absent");
    }

    /** One line per watched extension, present or absent. */
    public CapabilityReport watched(Set<String> extensions) {
        for (String extension : WATCHED) {
            value(extension, extensions.contains(extension) ? "present" : "absent");
        }
        return this;
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    /**
     * A bit field as hexadecimal followed by the names of its set bits, {@code names[i]} naming
     * bit i; a set bit with no name is shown by its position.
     */
    public static String bits(long value, String... names) {
        List<String> set = new ArrayList<>();
        for (int bit = 0; bit < 64; bit++) {
            if ((value >>> bit & 1) != 0) {
                set.add(bit < names.length && names[bit] != null ? names[bit] : "bit " + bit);
            }
        }
        return String.format(Locale.ROOT, "0x%X", value) + (set.isEmpty() ? "" : " (" + String.join(", ", set) + ")");
    }

    /**
     * A device or driver identifier reduced to the first 8 hexadecimal digits of its SHA-256, so
     * two reports can be matched (a GL context against a Vulkan device) without the identifier
     * itself being written anywhere.
     */
    public static String fingerprint(byte[] identifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identifier);
            return "sha256 " + HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(e);
        }
    }

    /** Joins the report into the file's text, one line each, ending in a newline. */
    public static byte[] text(List<String> lines) {
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
