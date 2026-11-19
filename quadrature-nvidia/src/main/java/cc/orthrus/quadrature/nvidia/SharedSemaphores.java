package cc.orthrus.quadrature.nvidia;

import static cc.orthrus.quadrature.nvidia.VulkanDevice.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceExternalSemaphoreProperties;
import static org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_BINARY;
import static org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_TIMELINE;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.EXTSemaphoreWin32;
import org.lwjgl.opengl.NVTimelineSemaphore;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalSemaphoreWin32;
import org.lwjgl.vulkan.VkExportSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkExternalSemaphoreProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceExternalSemaphoreInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;

import java.nio.LongBuffer;

/**
 * The semaphores of the round trip, created by Vulkan, exported as opaque Win32 handles and imported by
 * OpenGL. Two kinds: one timeline semaphore (Vulkan 1.2, GL_NV_timeline_semaphore), which both sides
 * signal and wait on with rising values, or two binary semaphores, one each way, which need every signal
 * submitted before its wait.
 */
final class SharedSemaphores implements AutoCloseable {

    final boolean timeline;
    private final VulkanDevice vulkan;
    /** The semaphore OpenGL signals and Vulkan waits on, and the one Vulkan signals and OpenGL waits on. */
    final long vkToVulkan;
    final long vkToOpenGl;
    final int glToVulkan;
    final int glToOpenGl;

    SharedSemaphores(VulkanDevice vulkan, boolean timeline) {
        this.vulkan = vulkan;
        this.timeline = timeline;
        exportable();
        vkToVulkan = create();
        long other = 0;
        int glOne = 0;
        int glOther = 0;
        try {
            glOne = imported(vkToVulkan);
            if (timeline) {
                other = vkToVulkan;
                glOther = glOne;
            } else {
                other = create();
                glOther = imported(other);
            }
        } catch (RuntimeException e) {
            if (glOne != 0) {
                EXTSemaphore.glDeleteSemaphoresEXT(glOne);
            }
            if (other != 0 && other != vkToVulkan) {
                vkDestroySemaphore(vulkan.device, other, null);
            }
            vkDestroySemaphore(vulkan.device, vkToVulkan, null);
            throw e;
        }
        vkToOpenGl = other;
        glToVulkan = glOne;
        glToOpenGl = glOther;
    }

    private void exportable() {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                .semaphoreType(timeline ? VK_SEMAPHORE_TYPE_TIMELINE : VK_SEMAPHORE_TYPE_BINARY);
            VkExternalSemaphoreProperties properties = VkExternalSemaphoreProperties.calloc(stack).sType$Default();
            vkGetPhysicalDeviceExternalSemaphoreProperties(vulkan.physical, VkPhysicalDeviceExternalSemaphoreInfo.calloc(stack)
                .sType$Default().pNext(type.address()).handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT),
                properties);
            if ((properties.externalSemaphoreFeatures() & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) == 0) {
                throw new IllegalStateException((timeline ? "Timeline" : "Binary")
                    + " semaphores cannot be exported as opaque Win32 handles on this device.");
            }
        }
    }

    private long create() {
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                .semaphoreType(timeline ? VK_SEMAPHORE_TYPE_TIMELINE : VK_SEMAPHORE_TYPE_BINARY).initialValue(0);
            VkExportSemaphoreCreateInfo export = VkExportSemaphoreCreateInfo.calloc(stack).sType$Default()
                .pNext(type.address()).handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT);
            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateSemaphore(vulkan.device, VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(export.address()),
                null, handle), "vkCreateSemaphore");
            return handle.get(0);
        }
    }

    /** OpenGL's name for a Vulkan semaphore, imported through an NT handle that is closed again. */
    private int imported(long semaphore) {
        long exported;
        try (MemoryStack stack = stackPush()) {
            PointerBuffer win32 = stack.mallocPointer(1);
            check(KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR(vulkan.device, VkSemaphoreGetWin32HandleInfoKHR
                .calloc(stack).sType$Default().semaphore(semaphore)
                .handleType(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT), win32), "vkGetSemaphoreWin32HandleKHR");
            exported = win32.get(0);
        }
        try {
            Gl.clearErrors();
            int name;
            if (timeline) {
                // The type is set before the import: the payload is a timeline, and OpenGL's default is binary.
                name = NVTimelineSemaphore.glCreateSemaphoresNV();
                try (MemoryStack stack = stackPush()) {
                    NVTimelineSemaphore.glSemaphoreParameterivNV(name, NVTimelineSemaphore.GL_SEMAPHORE_TYPE_NV,
                        stack.ints(NVTimelineSemaphore.GL_SEMAPHORE_TYPE_TIMELINE_NV));
                }
            } else {
                name = EXTSemaphore.glGenSemaphoresEXT();
            }
            EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT(name, EXTSemaphoreWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, exported);
            Gl.check("importing a " + (timeline ? "timeline" : "binary") + " semaphore");
            return name;
        } finally {
            Win32.closeHandle(exported);
        }
    }

    @Override
    public void close() {
        EXTSemaphore.glDeleteSemaphoresEXT(glToVulkan);
        vkDestroySemaphore(vulkan.device, vkToVulkan, null);
        if (!timeline) {
            EXTSemaphore.glDeleteSemaphoresEXT(glToOpenGl);
            vkDestroySemaphore(vulkan.device, vkToOpenGl, null);
        }
    }
}
