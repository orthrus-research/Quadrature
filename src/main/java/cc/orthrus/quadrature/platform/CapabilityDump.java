package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.platform.CapabilityReport;
import cc.orthrus.quadrature.core.platform.GpuMemory;
import cc.orthrus.quadrature.core.platform.GpuMemorySource;
import cc.orthrus.quadrature.core.platform.PlatformProfile;

import net.minecraft.client.Minecraft;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBSparseBuffer;
import org.lwjgl.opengl.ARBSparseTexture;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL46;
import org.lwjgl.opengl.KHRShaderSubgroup;
import org.lwjgl.opengl.NVMeshShader;
import org.lwjgl.opengl.NVPrimitiveShadingRate;
import org.lwjgl.opengl.NVShaderThreadGroup;
import org.lwjgl.opengl.NVShadingRateImage;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Behind {@code -Dquadrature.capabilities=true}: reads, once at startup, the limits and the
 * extension set that the gap matrix and the quality tiers depend on, logs them and writes them,
 * with the full extension list, to the client's {@code quadrature-capabilities.txt}.
 *
 * <p>It only queries and changes no state. A limit whose extension is absent is recorded as not
 * queried, so the dump raises no GL error on a driver that lacks it; an error a query does raise
 * is recorded in place of the value and cleared. Device and driver identifiers are written as
 * fingerprints only ({@link CapabilityReport#fingerprint}).
 */
final class CapabilityDump {

    static final String SWITCH = "quadrature.capabilities";
    private static final String FILE = "quadrature-capabilities.txt";
    /** How many stale errors are cleared before giving up; a driver never queues more than a few. */
    private static final int ERROR_DRAIN_LIMIT = 64;

    private static final String MESH = "GL_NV_mesh_shader";
    private static final String SUBGROUP = "GL_KHR_shader_subgroup";
    private static final String THREAD_GROUP = "GL_NV_shader_thread_group";
    private static final String SHADING_RATE = "GL_NV_shading_rate_image";
    private static final String PRIMITIVE_RATE = "GL_NV_primitive_shading_rate";
    private static final String SPARSE_BUFFER = "GL_ARB_sparse_buffer";
    private static final String SPARSE_TEXTURE = "GL_ARB_sparse_texture";
    private static final String MEMORY_OBJECT = "GL_EXT_memory_object";
    private static final String MEMORY_OBJECT_WIN32 = "GL_EXT_memory_object_win32";

    /** GL shader stage bits, by bit position, for the subgroup stage mask. */
    private static final String[] STAGES = {"vertex", "fragment", "geometry", "tessellation control",
        "tessellation evaluation", "compute", "mesh", "task"};
    /** KHR_shader_subgroup feature bits, by bit position. */
    private static final String[] SUBGROUP_FEATURES = {"basic", "vote", "arithmetic", "ballot", "shuffle",
        "shuffle relative", "clustered", "quad", "partitioned"};
    /** GL_CONTEXT_FLAGS bits, by bit position. */
    private static final String[] CONTEXT_FLAGS = {"forward compatible", "debug", "robust access", "no error"};

    private final Set<String> extensions;
    private final CapabilityReport report = new CapabilityReport();

    private CapabilityDump(Set<String> extensions) {
        this.extensions = extensions;
    }

    static boolean enabled() {
        return Boolean.getBoolean(SWITCH);
    }

    /** Reads, logs and writes the dump. Call on the thread that owns the context. */
    static void run(PlatformProfile profile, GpuMemorySource memory) {
        CapabilityDump dump = new CapabilityDump(profile.extensions());
        int stale = dump.drain();
        dump.read(profile, memory, stale);
        List<String> lines = dump.report.lines();
        for (String line : lines) {
            if (!line.isEmpty()) {
                Quadrature.LOGGER.info("Capabilities: {}", line);
            }
        }
        List<String> file = new ArrayList<>();
        file.add("Quadrature capability dump, format 1");
        file.add(profile.describe());
        file.add("");
        file.addAll(lines);
        file.add("");
        file.add("[extensions, " + profile.extensions().size() + "]");
        file.addAll(new TreeSet<>(profile.extensions()));
        File target = new File(Minecraft.getMinecraft().gameDir, FILE);
        try {
            Files.write(target.toPath(), CapabilityReport.text(file));
            Quadrature.LOGGER.info("Capabilities written to {}", target.getAbsolutePath());
        } catch (IOException e) {
            Quadrature.LOGGER.error("Capabilities could not be written to {}", target.getAbsolutePath(), e);
        }
    }

    private void read(PlatformProfile profile, GpuMemorySource memory, int stale) {
        report.section("context");
        report.value("GL_VERSION", profile.versionString());
        report.value("GL_VENDOR", profile.vendor());
        report.value("GL_RENDERER", profile.renderer());
        report.value("GL_SHADING_LANGUAGE_VERSION", string(GL20.GL_SHADING_LANGUAGE_VERSION));
        report.value("profile", profile.profile().name().toLowerCase(Locale.ROOT));
        flags("GL_CONTEXT_FLAGS", GL30.GL_CONTEXT_FLAGS, CONTEXT_FLAGS);
        report.value("GLSL versions", glslVersions());
        report.value("extensions", profile.extensions().size());
        report.value("GL errors pending before the dump", stale);

        report.section("memory, KiB");
        GpuMemory figures = memory.read();
        report.value("source", memory.name());
        report.value("dedicated", figures.dedicatedKiB());
        report.value("total available", figures.totalAvailableKiB());
        report.value("currently available", figures.currentAvailableKiB());
        report.value("evictions since boot", figures.evictions());
        report.value("evicted since boot", figures.evictedKiB());
        report.value("GL errors", drain());

        report.section("watched extensions");
        report.watched(extensions);

        report.section("limits");
        integer("GL_MAX_TEXTURE_SIZE", GL11.GL_MAX_TEXTURE_SIZE);
        integer("GL_MAX_3D_TEXTURE_SIZE", GL12.GL_MAX_3D_TEXTURE_SIZE);
        integer("GL_MAX_ARRAY_TEXTURE_LAYERS", GL30.GL_MAX_ARRAY_TEXTURE_LAYERS);
        integer("GL_MAX_TEXTURE_BUFFER_SIZE", GL31.GL_MAX_TEXTURE_BUFFER_SIZE);
        real("GL_MAX_TEXTURE_MAX_ANISOTROPY", GL46.GL_MAX_TEXTURE_MAX_ANISOTROPY);
        integer("GL_MAX_TEXTURE_IMAGE_UNITS", GL20.GL_MAX_TEXTURE_IMAGE_UNITS);
        integer("GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS", GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        integer("GL_MAX_IMAGE_UNITS", GL42.GL_MAX_IMAGE_UNITS);
        integer("GL_MAX_COLOR_ATTACHMENTS", GL30.GL_MAX_COLOR_ATTACHMENTS);
        integer("GL_MAX_DRAW_BUFFERS", GL20.GL_MAX_DRAW_BUFFERS);
        integer("GL_MAX_DUAL_SOURCE_DRAW_BUFFERS", GL33.GL_MAX_DUAL_SOURCE_DRAW_BUFFERS);
        integer("GL_MAX_SAMPLES", GL30.GL_MAX_SAMPLES);
        integer("GL_MAX_FRAMEBUFFER_WIDTH", GL43.GL_MAX_FRAMEBUFFER_WIDTH);
        integer("GL_MAX_FRAMEBUFFER_HEIGHT", GL43.GL_MAX_FRAMEBUFFER_HEIGHT);
        integer("GL_MAX_FRAMEBUFFER_LAYERS", GL43.GL_MAX_FRAMEBUFFER_LAYERS);
        integer("GL_MAX_VIEWPORTS", GL41.GL_MAX_VIEWPORTS);
        integer("GL_MAX_VERTEX_ATTRIBS", GL20.GL_MAX_VERTEX_ATTRIBS);
        integer("GL_MAX_UNIFORM_BLOCK_SIZE", GL31.GL_MAX_UNIFORM_BLOCK_SIZE);
        integer("GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT", GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
        integer("GL_MAX_UNIFORM_LOCATIONS", GL43.GL_MAX_UNIFORM_LOCATIONS);
        integer64("GL_MAX_SHADER_STORAGE_BLOCK_SIZE", GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        integer("GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS", GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
        integer("GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT", GL43.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);

        report.section("compute");
        integer("GL_MAX_COMPUTE_SHARED_MEMORY_SIZE", GL43.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE);
        integer("GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS", GL43.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS);
        triple("GL_MAX_COMPUTE_WORK_GROUP_SIZE", GL43.GL_MAX_COMPUTE_WORK_GROUP_SIZE);
        triple("GL_MAX_COMPUTE_WORK_GROUP_COUNT", GL43.GL_MAX_COMPUTE_WORK_GROUP_COUNT);

        report.section("compatibility profile");
        integer("GL_MAX_LIGHTS", GL11.GL_MAX_LIGHTS);
        integer("GL_MAX_CLIP_PLANES", GL11.GL_MAX_CLIP_PLANES);
        integer("GL_MAX_TEXTURE_UNITS", GL13.GL_MAX_TEXTURE_UNITS);
        integer("GL_MAX_MODELVIEW_STACK_DEPTH", GL11.GL_MAX_MODELVIEW_STACK_DEPTH);
        integer("GL_MAX_PROJECTION_STACK_DEPTH", GL11.GL_MAX_PROJECTION_STACK_DEPTH);
        integer("GL_MAX_TEXTURE_STACK_DEPTH", GL11.GL_MAX_TEXTURE_STACK_DEPTH);
        integer("GL_MAX_ATTRIB_STACK_DEPTH", GL11.GL_MAX_ATTRIB_STACK_DEPTH);

        report.section("program binaries");
        integer("GL_NUM_PROGRAM_BINARY_FORMATS", GL41.GL_NUM_PROGRAM_BINARY_FORMATS);
        report.value("GL_PROGRAM_BINARY_FORMATS", binaryFormats());
        integer("GL_NUM_SHADER_BINARY_FORMATS", GL41.GL_NUM_SHADER_BINARY_FORMATS);

        report.section(THREAD_GROUP);
        integer(THREAD_GROUP, "GL_SM_COUNT_NV", NVShaderThreadGroup.GL_SM_COUNT_NV);
        integer(THREAD_GROUP, "GL_WARPS_PER_SM_NV", NVShaderThreadGroup.GL_WARPS_PER_SM_NV);
        integer(THREAD_GROUP, "GL_WARP_SIZE_NV", NVShaderThreadGroup.GL_WARP_SIZE_NV);

        report.section(MESH);
        integer(MESH, "GL_MAX_MESH_OUTPUT_VERTICES_NV", NVMeshShader.GL_MAX_MESH_OUTPUT_VERTICES_NV);
        integer(MESH, "GL_MAX_MESH_OUTPUT_PRIMITIVES_NV", NVMeshShader.GL_MAX_MESH_OUTPUT_PRIMITIVES_NV);
        integer(MESH, "GL_MAX_MESH_WORK_GROUP_INVOCATIONS_NV", NVMeshShader.GL_MAX_MESH_WORK_GROUP_INVOCATIONS_NV);
        integer(MESH, "GL_MAX_TASK_WORK_GROUP_INVOCATIONS_NV", NVMeshShader.GL_MAX_TASK_WORK_GROUP_INVOCATIONS_NV);
        triple(MESH, "GL_MAX_MESH_WORK_GROUP_SIZE_NV", NVMeshShader.GL_MAX_MESH_WORK_GROUP_SIZE_NV);
        triple(MESH, "GL_MAX_TASK_WORK_GROUP_SIZE_NV", NVMeshShader.GL_MAX_TASK_WORK_GROUP_SIZE_NV);
        integer(MESH, "GL_MAX_MESH_TOTAL_MEMORY_SIZE_NV", NVMeshShader.GL_MAX_MESH_TOTAL_MEMORY_SIZE_NV);
        integer(MESH, "GL_MAX_TASK_TOTAL_MEMORY_SIZE_NV", NVMeshShader.GL_MAX_TASK_TOTAL_MEMORY_SIZE_NV);
        integer(MESH, "GL_MAX_TASK_OUTPUT_COUNT_NV", NVMeshShader.GL_MAX_TASK_OUTPUT_COUNT_NV);
        integer(MESH, "GL_MAX_DRAW_MESH_TASKS_COUNT_NV", NVMeshShader.GL_MAX_DRAW_MESH_TASKS_COUNT_NV);
        integer(MESH, "GL_MAX_MESH_VIEWS_NV", NVMeshShader.GL_MAX_MESH_VIEWS_NV);
        integer(MESH, "GL_MESH_OUTPUT_PER_VERTEX_GRANULARITY_NV",
            NVMeshShader.GL_MESH_OUTPUT_PER_VERTEX_GRANULARITY_NV);
        integer(MESH, "GL_MESH_OUTPUT_PER_PRIMITIVE_GRANULARITY_NV",
            NVMeshShader.GL_MESH_OUTPUT_PER_PRIMITIVE_GRANULARITY_NV);

        report.section(SUBGROUP);
        integer(SUBGROUP, "GL_SUBGROUP_SIZE_KHR", KHRShaderSubgroup.GL_SUBGROUP_SIZE_KHR);
        if (has(SUBGROUP)) {
            flags("GL_SUBGROUP_SUPPORTED_STAGES_KHR", KHRShaderSubgroup.GL_SUBGROUP_SUPPORTED_STAGES_KHR, STAGES);
            flags("GL_SUBGROUP_SUPPORTED_FEATURES_KHR", KHRShaderSubgroup.GL_SUBGROUP_SUPPORTED_FEATURES_KHR,
                SUBGROUP_FEATURES);
        }
        integer(SUBGROUP, "GL_SUBGROUP_QUAD_ALL_STAGES_KHR", KHRShaderSubgroup.GL_SUBGROUP_QUAD_ALL_STAGES_KHR);

        report.section(SHADING_RATE);
        integer(SHADING_RATE, "GL_SHADING_RATE_IMAGE_TEXEL_WIDTH_NV",
            NVShadingRateImage.GL_SHADING_RATE_IMAGE_TEXEL_WIDTH_NV);
        integer(SHADING_RATE, "GL_SHADING_RATE_IMAGE_TEXEL_HEIGHT_NV",
            NVShadingRateImage.GL_SHADING_RATE_IMAGE_TEXEL_HEIGHT_NV);
        integer(SHADING_RATE, "GL_SHADING_RATE_IMAGE_PALETTE_SIZE_NV",
            NVShadingRateImage.GL_SHADING_RATE_IMAGE_PALETTE_SIZE_NV);
        integer(SHADING_RATE, "GL_MAX_COARSE_FRAGMENT_SAMPLES_NV", NVShadingRateImage.GL_MAX_COARSE_FRAGMENT_SAMPLES_NV);
        integer(PRIMITIVE_RATE, "GL_SHADING_RATE_IMAGE_PALETTE_COUNT_NV",
            NVPrimitiveShadingRate.GL_SHADING_RATE_IMAGE_PALETTE_COUNT_NV);

        report.section("sparse resources");
        integer(SPARSE_BUFFER, "GL_SPARSE_BUFFER_PAGE_SIZE_ARB", ARBSparseBuffer.GL_SPARSE_BUFFER_PAGE_SIZE_ARB);
        integer(SPARSE_TEXTURE, "GL_MAX_SPARSE_TEXTURE_SIZE_ARB", ARBSparseTexture.GL_MAX_SPARSE_TEXTURE_SIZE_ARB);
        integer(SPARSE_TEXTURE, "GL_MAX_SPARSE_3D_TEXTURE_SIZE_ARB",
            ARBSparseTexture.GL_MAX_SPARSE_3D_TEXTURE_SIZE_ARB);
        integer(SPARSE_TEXTURE, "GL_MAX_SPARSE_ARRAY_TEXTURE_LAYERS_ARB",
            ARBSparseTexture.GL_MAX_SPARSE_ARRAY_TEXTURE_LAYERS_ARB);
        if (has(SPARSE_TEXTURE)) {
            pageSizes();
        }

        report.section("interop");
        if (has(MEMORY_OBJECT)) {
            identifiers();
        } else {
            report.notQueried("GL_DEVICE_UUID_EXT", MEMORY_OBJECT);
        }
        if (has(MEMORY_OBJECT) && has(MEMORY_OBJECT_WIN32)) {
            ByteBuffer luid = BufferUtils.createByteBuffer(EXTMemoryObjectWin32.GL_LUID_SIZE_EXT);
            EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT, luid);
            identifier("GL_DEVICE_LUID_EXT", luid);
            integer("GL_DEVICE_NODE_MASK_EXT", EXTMemoryObjectWin32.GL_DEVICE_NODE_MASK_EXT);
        } else {
            report.notQueried("GL_DEVICE_LUID_EXT", MEMORY_OBJECT_WIN32);
        }
    }

    /** Virtual page sizes of the formats the targets, the light's depth and the scene use. */
    private void pageSizes() {
        int[] targets = {GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_2D_ARRAY, GL12.GL_TEXTURE_3D};
        String[] targetNames = {"2D", "2D array", "3D"};
        int[] formats = {GL11.GL_RGBA8, GL30.GL_RGBA16F, GL30.GL_R11F_G11F_B10F, GL30.GL_R32F, GL30.GL_R8,
            GL30.GL_DEPTH_COMPONENT32F, GL14.GL_DEPTH_COMPONENT24};
        String[] formatNames = {"RGBA8", "RGBA16F", "R11F_G11F_B10F", "R32F", "R8", "DEPTH_COMPONENT32F",
            "DEPTH_COMPONENT24"};
        for (int t = 0; t < targets.length; t++) {
            for (int f = 0; f < formats.length; f++) {
                String name = "virtual page sizes, " + targetNames[t] + ", " + formatNames[f];
                int count = GL42.glGetInternalformati(targets[t], formats[f], ARBSparseTexture.GL_NUM_VIRTUAL_PAGE_SIZES_ARB);
                int error = GL11.glGetError();
                if (error != GL11.GL_NO_ERROR) {
                    report.error(name, error);
                    drain();
                    continue;
                }
                if (count <= 0) {
                    report.value(name, "none");
                    continue;
                }
                int[] x = new int[count];
                int[] y = new int[count];
                int[] z = new int[count];
                GL42.glGetInternalformativ(targets[t], formats[f], ARBSparseTexture.GL_VIRTUAL_PAGE_SIZE_X_ARB, x);
                GL42.glGetInternalformativ(targets[t], formats[f], ARBSparseTexture.GL_VIRTUAL_PAGE_SIZE_Y_ARB, y);
                GL42.glGetInternalformativ(targets[t], formats[f], ARBSparseTexture.GL_VIRTUAL_PAGE_SIZE_Z_ARB, z);
                error = GL11.glGetError();
                if (error != GL11.GL_NO_ERROR) {
                    report.error(name, error);
                    drain();
                    continue;
                }
                List<String> sizes = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    sizes.add(x[i] + "x" + y[i] + "x" + z[i]);
                }
                report.value(name, String.join(", ", sizes));
            }
        }
    }

    /** The device and driver identifiers interop with Vulkan matches on, as fingerprints. */
    private void identifiers() {
        int devices = GL11.glGetInteger(EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT);
        if (record("GL_NUM_DEVICE_UUIDS_EXT", devices)) {
            for (int i = 0; i < devices; i++) {
                ByteBuffer uuid = BufferUtils.createByteBuffer(EXTMemoryObject.GL_UUID_SIZE_EXT);
                EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, i, uuid);
                identifier("GL_DEVICE_UUID_EXT " + i, uuid);
            }
        }
        ByteBuffer driver = BufferUtils.createByteBuffer(EXTMemoryObject.GL_UUID_SIZE_EXT);
        EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObject.GL_DRIVER_UUID_EXT, driver);
        identifier("GL_DRIVER_UUID_EXT", driver);
    }

    private void identifier(String name, ByteBuffer value) {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            report.error(name, error);
            drain();
            return;
        }
        byte[] bytes = new byte[value.remaining()];
        value.get(bytes);
        report.value(name, CapabilityReport.fingerprint(bytes));
    }

    private String glslVersions() {
        int count = GL11.glGetInteger(GL43.GL_NUM_SHADING_LANGUAGE_VERSIONS);
        if (GL11.glGetError() != GL11.GL_NO_ERROR) {
            drain();
            return "not reported";
        }
        List<String> versions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String version = GL30.glGetStringi(GL20.GL_SHADING_LANGUAGE_VERSION, i);
            // The list holds an empty string for the oldest version, 1.10 without a #version line.
            versions.add(version == null || version.isEmpty() ? "(none)" : version);
        }
        drain();
        return String.join(", ", versions);
    }

    private String binaryFormats() {
        int count = GL11.glGetInteger(GL41.GL_NUM_PROGRAM_BINARY_FORMATS);
        if (GL11.glGetError() != GL11.GL_NO_ERROR || count <= 0) {
            drain();
            return "none";
        }
        int[] formats = new int[count];
        GL11.glGetIntegerv(GL41.GL_PROGRAM_BINARY_FORMATS, formats);
        if (GL11.glGetError() != GL11.GL_NO_ERROR) {
            drain();
            return "not reported";
        }
        List<String> hex = new ArrayList<>();
        for (int format : formats) {
            hex.add(String.format(Locale.ROOT, "0x%X", format));
        }
        return String.join(", ", hex);
    }

    private void integer(String extension, String name, int token) {
        if (has(extension)) {
            integer(name, token);
        } else {
            report.notQueried(name, extension);
        }
    }

    private void integer(String name, int token) {
        record(name, GL11.glGetInteger(token));
    }

    private void integer64(String name, int token) {
        record(name, GL32.glGetInteger64(token));
    }

    private void real(String name, int token) {
        float value = GL11.glGetFloat(token);
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            report.error(name, error);
            drain();
        } else {
            report.value(name, Float.toString(value));
        }
    }

    private void triple(String extension, String name, int token) {
        if (has(extension)) {
            triple(name, token);
        } else {
            report.notQueried(name, extension);
        }
    }

    /** An indexed limit with one value per dimension, x, y and z. */
    private void triple(String name, int token) {
        int x = GL30.glGetIntegeri(token, 0);
        int y = GL30.glGetIntegeri(token, 1);
        int z = GL30.glGetIntegeri(token, 2);
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            report.error(name, error);
            drain();
        } else {
            report.value(name, x + ", " + y + ", " + z);
        }
    }

    private void flags(String name, int token, String[] names) {
        int value = GL11.glGetInteger(token);
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            report.error(name, error);
            drain();
        } else {
            report.value(name, CapabilityReport.bits(value & 0xFFFFFFFFL, names));
        }
    }

    /** Records a value just read, or the error its query raised. Returns whether it was a value. */
    private boolean record(String name, long value) {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            report.error(name, error);
            drain();
            return false;
        }
        report.value(name, value);
        return true;
    }

    private boolean has(String extension) {
        return extensions.contains(extension);
    }

    /** Clears the errors raised since the last check and returns how many there were. */
    private int drain() {
        int count = 0;
        while (count < ERROR_DRAIN_LIMIT && GL11.glGetError() != GL11.GL_NO_ERROR) {
            count++;
        }
        return count;
    }

    private static String string(int name) {
        String value = GL11.glGetString(name);
        return value == null ? "" : value;
    }
}
