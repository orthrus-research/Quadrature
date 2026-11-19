package cc.orthrus.quadrature.nvidia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkExtensionProperties;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;

class EnumerationsTest {

    /** What the RTX 3060's driver 610.88 lists as device extensions. */
    private static final int NVIDIA_DEVICE_EXTENSIONS = 276;

    /** A query that answers like a driver listing {@code entries} extensions. */
    private static Enumerations.ExtensionQuery driver(int entries) {
        return (count, properties) -> {
            if (properties != null) {
                for (int i = 0; i < Math.min(entries, properties.capacity()); i++) {
                    ByteBuffer name = properties.get(i).extensionName();
                    name.put(0, ("VK_TEST_extension_" + i).getBytes(StandardCharsets.UTF_8));
                    name.put(("VK_TEST_extension_" + i).length(), (byte) 0);
                }
            }
            count.put(0, entries);
            return VK10.VK_SUCCESS;
        };
    }

    @Test
    void aDriversExtensionsDoNotFitTheThreadStackAndAreReadOffIt() {
        // The failure turn 1 met in the game: 276 entries of 260 bytes overflow LWJGL's 64 KiB thread stack, which
        // LWJGL reports as an OutOfMemoryError.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            assertTrue((long) NVIDIA_DEVICE_EXTENSIONS * VkExtensionProperties.SIZEOF > stack.getSize());
            assertThrows(OutOfMemoryError.class, () -> VkExtensionProperties.malloc(NVIDIA_DEVICE_EXTENSIONS, stack));
        }
        Set<String> names = Enumerations.extensionNames("vkEnumerateDeviceExtensionProperties",
            driver(NVIDIA_DEVICE_EXTENSIONS));
        assertEquals(NVIDIA_DEVICE_EXTENSIONS, names.size());
        assertTrue(names.contains("VK_TEST_extension_275"));
    }

    @Test
    void noEntriesAndManyEntriesAreRead() {
        assertEquals(Set.of(), Enumerations.extensionNames("none", driver(0)));
        assertEquals(Enumerations.MOST, Enumerations.extensionNames("most", driver(Enumerations.MOST)).size());
    }

    @Test
    void aCountPastAnyDriversIsRefusedNotAllocated() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> Enumerations.extensionNames("broken", driver(1_000_000)));
        assertTrue(refused.getMessage().contains("1000000"), refused.getMessage());
        assertThrows(IllegalStateException.class, () -> Enumerations.bounded("negative", -1));
    }
}
