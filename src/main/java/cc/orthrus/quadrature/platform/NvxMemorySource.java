package cc.orthrus.quadrature.platform;

import cc.orthrus.quadrature.core.platform.GpuMemory;
import cc.orthrus.quadrature.core.platform.GpuMemorySource;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.NVXGPUMemoryInfo;

/** NVIDIA's GPU memory figures, from GL_NVX_gpu_memory_info. Read on the thread that owns the context. */
final class NvxMemorySource implements GpuMemorySource {

    static final String EXTENSION = "GL_NVX_gpu_memory_info";

    @Override
    public String name() {
        return EXTENSION;
    }

    @Override
    public GpuMemory read() {
        return new GpuMemory(
            unsigned(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX),
            unsigned(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX),
            unsigned(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX),
            unsigned(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_EVICTION_COUNT_NVX),
            unsigned(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_EVICTED_MEMORY_NVX));
    }

    /** The extension returns sizes in KiB and counts as signed integers that are never negative. */
    private static long unsigned(int token) {
        return GL11.glGetInteger(token) & 0xFFFFFFFFL;
    }
}
