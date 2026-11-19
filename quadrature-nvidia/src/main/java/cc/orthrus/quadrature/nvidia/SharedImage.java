package cc.orthrus.quadrature.nvidia;

import static cc.orthrus.quadrature.nvidia.VulkanDevice.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT;
import static org.lwjgl.vulkan.VK11.VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK11.vkGetImageMemoryRequirements2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceImageFormatProperties2;

import cc.orthrus.quadrature.core.handoff.SharedImageSizes;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.VkExportMemoryAllocateInfo;
import org.lwjgl.vulkan.VkExternalImageFormatProperties;
import org.lwjgl.vulkan.VkExternalMemoryImageCreateInfo;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageFormatProperties2;
import org.lwjgl.vulkan.VkImageMemoryRequirementsInfo2;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedRequirements;
import org.lwjgl.vulkan.VkMemoryGetWin32HandleInfoKHR;
import org.lwjgl.vulkan.VkMemoryRequirements2;
import org.lwjgl.vulkan.VkPhysicalDeviceExternalImageFormatInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceImageFormatInfo2;

import java.nio.LongBuffer;
import java.util.Locale;

/**
 * One image both APIs use: created by Vulkan in memory it exports as an opaque Win32 handle, imported by
 * OpenGL as a texture over the same memory, with a framebuffer of its own for OpenGL's copies.
 *
 * <p>The image carries every usage its format supports with optimal tiling, as OpenGL's external objects
 * require of an image that aliases a texture (issue 13), and the mutable-format bit, since OpenGL 4.3 and
 * later can view a texture in another format (issue 17). Its memory is a dedicated allocation, and OpenGL
 * is told so, as the two must agree (section 6.2).
 */
final class SharedImage implements AutoCloseable {

    /** What the image holds, and the formats it takes on each side. */
    enum Role {
        COLOR, DEPTH, MOTION, OUTPUT
    }

    final Role role;
    final int vkFormat;
    final int glFormat;
    final int width;
    final int height;
    final boolean depth;
    final boolean stencil;
    private final VulkanDevice vulkan;
    long image;
    long memory;
    long view;
    int glMemory;
    int glTexture;
    int glFramebuffer;

    /** @param texel bytes a texel of the format takes, which bounds the allocation the driver may ask for */
    SharedImage(VulkanDevice vulkan, Role role, int vkFormat, int glFormat, int width, int height, int texel, boolean depth,
                boolean stencil) {
        this.vulkan = vulkan;
        this.role = role;
        this.vkFormat = vkFormat;
        this.glFormat = glFormat;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.stencil = stencil;
        this.texelBytes = (long) width * height * texel;
        try (MemoryStack stack = stackPush()) {
            create(stack);
        } catch (RuntimeException e) {
            // Whatever was made before the failure goes again; a mismatched device fails here, at the import.
            close();
            throw e;
        }
    }

    private final long texelBytes;
    private int usage;
    private long size;
    private boolean dedicatedRequired;
    private boolean dedicatedPreferred;
    private boolean dedicatedOnly;

    private void create(MemoryStack stack) {
        usage = usage(stack);
        int required = role == Role.OUTPUT ? VK_IMAGE_USAGE_STORAGE_BIT : VK_IMAGE_USAGE_SAMPLED_BIT;
        if ((usage & required) == 0) {
            throw new IllegalStateException(role + " format " + vkFormat + " lacks the usage DLSS needs ("
                + (role == Role.OUTPUT ? "storage" : "sampled") + ").");
        }
        dedicatedOnly = exportable(stack);

        VkExternalMemoryImageCreateInfo external = VkExternalMemoryImageCreateInfo.calloc(stack).sType$Default()
            .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
        VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default().pNext(external.address())
            .flags(VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT).imageType(VK_IMAGE_TYPE_2D).format(vkFormat)
            .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        info.extent().set(width, height, 1);
        LongBuffer handle = stack.mallocLong(1);
        check(vkCreateImage(vulkan.device, info, null, handle), "vkCreateImage " + role);
        image = handle.get(0);

        VkMemoryDedicatedRequirements dedicated = VkMemoryDedicatedRequirements.calloc(stack).sType$Default();
        VkMemoryRequirements2 requirements = VkMemoryRequirements2.calloc(stack).sType$Default()
            .pNext(dedicated.address());
        vkGetImageMemoryRequirements2(vulkan.device,
            VkImageMemoryRequirementsInfo2.calloc(stack).sType$Default().image(image), requirements);
        size = requirements.memoryRequirements().size();
        String refusal = SharedImageSizes.allocationRefusal(role.name().toLowerCase(java.util.Locale.ROOT), size, texelBytes);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        dedicatedRequired = dedicated.requiresDedicatedAllocation();
        dedicatedPreferred = dedicated.prefersDedicatedAllocation();

        VkMemoryDedicatedAllocateInfo dedicatedAllocation = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default()
            .image(image);
        VkExportMemoryAllocateInfo export = VkExportMemoryAllocateInfo.calloc(stack).sType$Default()
            .pNext(dedicatedAllocation.address()).handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
        VkMemoryAllocateInfo allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().pNext(export.address())
            .allocationSize(size).memoryTypeIndex(vulkan.memoryType(requirements.memoryRequirements().memoryTypeBits(),
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
        check(vkAllocateMemory(vulkan.device, allocation, null, handle), "vkAllocateMemory " + role);
        memory = handle.get(0);
        check(vkBindImageMemory(vulkan.device, image, memory, 0), "vkBindImageMemory " + role);

        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
            .viewType(VK_IMAGE_VIEW_TYPE_2D).format(vkFormat);
        viewInfo.subresourceRange().aspectMask(depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT)
            .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        check(vkCreateImageView(vulkan.device, viewInfo, null, handle), "vkCreateImageView " + role);
        view = handle.get(0);

        PointerBuffer win32 = stack.mallocPointer(1);
        check(KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR(vulkan.device, VkMemoryGetWin32HandleInfoKHR.calloc(stack)
            .sType$Default().memory(memory).handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT), win32),
            "vkGetMemoryWin32HandleKHR " + role);
        long exported = win32.get(0);
        try {
            Gl.clearErrors();
            glMemory = EXTMemoryObject.glCreateMemoryObjectsEXT();
            EXTMemoryObject.glMemoryObjectParameteriEXT(glMemory, EXTMemoryObject.GL_DEDICATED_MEMORY_OBJECT_EXT,
                GL11.GL_TRUE);
            EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT(glMemory, size,
                EXTMemoryObjectWin32.GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, exported);
            Gl.check("importing the " + role + " memory");
        } finally {
            Win32.closeHandle(exported);
        }
        glTexture = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureParameteri(glTexture, EXTMemoryObject.GL_TEXTURE_TILING_EXT, EXTMemoryObject.GL_OPTIMAL_TILING_EXT);
        GL45.glTextureParameteri(glTexture, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(glTexture, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL45.glTextureParameteri(glTexture, GL30.GL_TEXTURE_MAX_LEVEL, 0);
        EXTMemoryObject.glTextureStorageMem2DEXT(glTexture, 1, glFormat, width, height, glMemory, 0);
        Gl.check("making the " + role + " texture from its memory");
        glFramebuffer = GL45.glCreateFramebuffers();
        if (depth) {
            GL45.glNamedFramebufferTexture(glFramebuffer,
                stencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT, glTexture, 0);
            GL45.glNamedFramebufferDrawBuffer(glFramebuffer, GL11.GL_NONE);
            GL45.glNamedFramebufferReadBuffer(glFramebuffer, GL11.GL_NONE);
        } else {
            GL45.glNamedFramebufferTexture(glFramebuffer, GL30.GL_COLOR_ATTACHMENT0, glTexture, 0);
            GL45.glNamedFramebufferDrawBuffer(glFramebuffer, GL30.GL_COLOR_ATTACHMENT0);
            GL45.glNamedFramebufferReadBuffer(glFramebuffer, GL30.GL_COLOR_ATTACHMENT0);
        }
        int status = GL45.glCheckNamedFramebufferStatus(glFramebuffer, GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("The " + role + " framebuffer is incomplete: 0x" + Integer.toHexString(status));
        }
        Gl.check("attaching the " + role + " texture");
    }

    /** Every usage the format supports with optimal tiling, from its format features. */
    private int usage(MemoryStack stack) {
        VkFormatProperties properties = VkFormatProperties.calloc(stack);
        vkGetPhysicalDeviceFormatProperties(vulkan.physical, vkFormat, properties);
        int features = properties.optimalTilingFeatures();
        int flags = 0;
        if ((features & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0) {
            flags |= VK_IMAGE_USAGE_SAMPLED_BIT;
        }
        if ((features & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0) {
            flags |= VK_IMAGE_USAGE_STORAGE_BIT;
        }
        if ((features & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) != 0) {
            flags |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
        }
        if ((features & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
            flags |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
        }
        if ((features & VK_FORMAT_FEATURE_TRANSFER_SRC_BIT) != 0) {
            flags |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        }
        if ((features & VK_FORMAT_FEATURE_TRANSFER_DST_BIT) != 0) {
            flags |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        }
        return flags;
    }

    /** Whether the image can be exported as an opaque Win32 handle; returns whether only dedicated memory can. */
    private boolean exportable(MemoryStack stack) {
        VkPhysicalDeviceExternalImageFormatInfo externalInfo = VkPhysicalDeviceExternalImageFormatInfo.calloc(stack)
            .sType$Default().handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT);
        VkPhysicalDeviceImageFormatInfo2 info = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default()
            .pNext(externalInfo.address()).format(vkFormat).type(VK_IMAGE_TYPE_2D).tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(usage).flags(VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT);
        VkExternalImageFormatProperties external = VkExternalImageFormatProperties.calloc(stack).sType$Default();
        VkImageFormatProperties2 properties = VkImageFormatProperties2.calloc(stack).sType$Default().pNext(external.address());
        int result = vkGetPhysicalDeviceImageFormatProperties2(vulkan.physical, info, properties);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(role + " format " + vkFormat + " with usage 0x" + Integer.toHexString(usage)
                + " cannot be exported as an opaque Win32 handle (" + result + ").");
        }
        int features = external.externalMemoryProperties().externalMemoryFeatures();
        if ((features & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) == 0) {
            throw new IllegalStateException(role + " format " + vkFormat + " is not exportable.");
        }
        return (features & VK_EXTERNAL_MEMORY_FEATURE_DEDICATED_ONLY_BIT) != 0;
    }

    String describe() {
        return String.format(Locale.ROOT, "%s %dx%d Vulkan format %d / GL 0x%04x, usage 0x%x, %.2f MiB, dedicated %s%s%s",
            role, width, height, vkFormat, glFormat, usage, size / 1048576.0,
            dedicatedRequired ? "required" : dedicatedPreferred ? "preferred" : "chosen",
            dedicatedOnly ? ", dedicated only for export" : "", stencil ? ", with stencil" : "");
    }

    long size() {
        return size;
    }

    /** Releases both sides. The caller has made sure neither API still uses the image. */
    @Override
    public void close() {
        if (glFramebuffer != 0) {
            GL45.glDeleteFramebuffers(glFramebuffer);
        }
        if (glTexture != 0) {
            GL11.glDeleteTextures(glTexture);
        }
        if (glMemory != 0) {
            EXTMemoryObject.glDeleteMemoryObjectsEXT(glMemory);
        }
        if (view != 0) {
            vkDestroyImageView(vulkan.device, view, null);
        }
        if (image != 0) {
            vkDestroyImage(vulkan.device, image, null);
        }
        if (memory != 0) {
            vkFreeMemory(vulkan.device, memory, null);
        }
        glFramebuffer = glTexture = glMemory = 0;
        view = image = memory = 0;
    }
}
