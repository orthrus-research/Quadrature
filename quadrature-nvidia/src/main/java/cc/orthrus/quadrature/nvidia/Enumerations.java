package cc.orthrus.quadrature.nvidia;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkLayerProperties;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Vulkan's two-call enumerations of names, read into memory allocated for the purpose. The driver decides
 * how many entries there are, so they never go on LWJGL's thread stack: that stack holds 64 KiB, an NVIDIA
 * driver lists 276 device extensions of 260 bytes each, and LWJGL reports an overflowing stack as an
 * {@link OutOfMemoryError}, which the game takes for a full heap and answers by dropping the world.
 */
final class Enumerations {

    /** More entries than any driver lists; a count past it is taken for a broken query, not allocated. */
    static final int MOST = 4096;

    /** One enumeration's call: the count alone when {@code properties} is null, else the entries. */
    interface ExtensionQuery {
        int run(IntBuffer count, VkExtensionProperties.Buffer properties);
    }

    interface LayerQuery {
        int run(IntBuffer count, VkLayerProperties.Buffer properties);
    }

    private Enumerations() {
    }

    static Set<String> extensionNames(String what, ExtensionQuery query) {
        IntBuffer count = MemoryUtil.memAllocInt(1).put(0, 0);
        try {
            VulkanDevice.check(query.run(count, null), what);
            int entries = bounded(what, count.get(0));
            VkExtensionProperties.Buffer properties = VkExtensionProperties.malloc(entries);
            try {
                VulkanDevice.check(complete(query.run(count, properties)), what);
                Set<String> names = new HashSet<>();
                for (int i = 0; i < Math.min(entries, count.get(0)); i++) {
                    names.add(properties.get(i).extensionNameString());
                }
                return names;
            } finally {
                properties.free();
            }
        } finally {
            MemoryUtil.memFree(count);
        }
    }

    static Set<String> layerNames(String what, LayerQuery query) {
        IntBuffer count = MemoryUtil.memAllocInt(1).put(0, 0);
        try {
            VulkanDevice.check(query.run(count, null), what);
            int entries = bounded(what, count.get(0));
            VkLayerProperties.Buffer properties = VkLayerProperties.malloc(entries);
            try {
                VulkanDevice.check(complete(query.run(count, properties)), what);
                Set<String> names = new HashSet<>();
                for (int i = 0; i < Math.min(entries, count.get(0)); i++) {
                    names.add(properties.get(i).layerNameString());
                }
                return names;
            } finally {
                properties.free();
            }
        } finally {
            MemoryUtil.memFree(count);
        }
    }

    static int bounded(String what, int count) {
        if (count < 0 || count > MOST) {
            throw new IllegalStateException(what + " reported " + count + " entries; more than " + MOST
                + " is taken for a broken query and not allocated.");
        }
        // A buffer of none is still allocated with room for one, so the second call has somewhere to point.
        return Math.max(1, count);
    }

    /** VK_INCOMPLETE on the second call means entries were added in between; the ones read are kept. */
    private static int complete(int result) {
        return result == VK10.VK_INCOMPLETE ? VK10.VK_SUCCESS : result;
    }
}
