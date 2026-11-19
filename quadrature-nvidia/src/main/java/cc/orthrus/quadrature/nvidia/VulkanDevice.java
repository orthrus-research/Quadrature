package cc.orthrus.quadrature.nvidia;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_LUID_SIZE;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceMemoryProperties2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

import cc.orthrus.quadrature.core.handoff.DeviceIdentity;
import cc.orthrus.quadrature.core.handoff.Handoff;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTCalibratedTimestamps;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.EXTMemoryBudget;
import org.lwjgl.vulkan.KHRCalibratedTimestamps;
import org.lwjgl.vulkan.KHRExternalMemoryWin32;
import org.lwjgl.vulkan.KHRExternalSemaphoreWin32;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkCalibratedTimestampInfoKHR;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceIDProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryBudgetPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Vulkan instance and a device on the same GPU as the game's OpenGL context, with one queue. The device
 * is chosen by the identity OpenGL reports (UUIDs and LUID), never by name or vendor.
 */
final class VulkanDevice implements AutoCloseable {

    private static final String VALIDATION = "VK_LAYER_KHRONOS_validation";
    /** Validation messages logged before only every hundredth is. */
    private static final int MESSAGES_LOGGED = 40;

    /** One physical device as Vulkan reports it. */
    record Candidate(VkPhysicalDevice device, String name, int type, int apiVersion, int driverVersion,
                     DeviceIdentity identity, String mismatch) {
        String describe() {
            return String.format(Locale.ROOT, "%s (%s, Vulkan %d.%d.%d, driver 0x%08x; %s): %s", name,
                typeName(type), VK_API_VERSION_MAJOR(apiVersion), VK_API_VERSION_MINOR(apiVersion),
                VK_API_VERSION_PATCH(apiVersion), driverVersion, identity.describe(),
                mismatch == null ? "matches the OpenGL context" : mismatch);
        }
    }

    final VkInstance instance;
    final VkPhysicalDevice physical;
    final VkDevice device;
    final VkQueue queue;
    final int queueFamily;
    final Candidate chosen;
    final List<Candidate> candidates;
    /** Nanoseconds a timestamp tick. */
    final double timestampPeriod;
    final long timestampMask;
    final boolean validation;
    final AtomicInteger validationMessages = new AtomicInteger();
    private final int calibration;
    private final boolean memoryBudget;
    private final VkPhysicalDeviceMemoryProperties memory;
    private final VkDebugUtilsMessengerCallbackEXT callback;
    private long messenger;

    /** What kind of calibrated timestamps the device has: none, the KHR extension or the EXT one. */
    private static final int CALIBRATION_NONE = 0;
    private static final int CALIBRATION_KHR = 1;
    private static final int CALIBRATION_EXT = 2;

    /**
     * @param glDevices the OpenGL context's identity, one entry per device UUID it reports
     * @param policy    {@code match} to take the device OpenGL runs on; {@code other} to take one that does not
     *                  match, so the failure of a mismatched import can be observed
     * @param queueKind {@code graphics} for a family with graphics and compute, {@code compute} for one with
     *                  compute alone
     */
    VulkanDevice(Handoff.Host host, List<DeviceIdentity> glDevices, String policy, String queueKind,
                 boolean wantValidation) {
        try (MemoryStack stack = stackPush()) {
            Set<String> layers = instanceLayers(stack);
            Set<String> instanceExtensions = instanceExtensions(stack);
            validation = wantValidation && layers.contains(VALIDATION)
                && instanceExtensions.contains(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            if (wantValidation && !validation) {
                host.warn("Vulkan validation was asked for, but " + VALIDATION + " or debug utils is not installed.");
            }
            VkApplicationInfo application = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("Quadrature")).applicationVersion(1)
                .pEngineName(stack.UTF8("Quadrature")).engineVersion(1).apiVersion(VK13.VK_API_VERSION_1_3);
            PointerBuffer enabledLayers = validation ? stack.pointers(stack.UTF8(VALIDATION)) : null;
            PointerBuffer enabledExtensions = validation
                ? stack.pointers(stack.UTF8(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) : null;
            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .pApplicationInfo(application).ppEnabledLayerNames(enabledLayers)
                .ppEnabledExtensionNames(enabledExtensions);
            PointerBuffer handle = stack.mallocPointer(1);
            check(vkCreateInstance(instanceInfo, null, handle), "vkCreateInstance");
            instance = new VkInstance(handle.get(0), instanceInfo);
        }
        callback = validation ? VkDebugUtilsMessengerCallbackEXT.create((severity, types, data, user) -> {
            int count = validationMessages.incrementAndGet();
            if (count <= MESSAGES_LOGGED || count % 100 == 0) {
                host.warn("Vulkan validation (" + count + "): "
                    + VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
            }
            return VK_FALSE;
        }) : null;
        try {
            if (validation) {
                createMessenger();
            }
            candidates = candidates(glDevices);
            Candidate pick = null;
            for (Candidate candidate : candidates) {
                host.info("Vulkan device " + candidate.describe());
                boolean matches = candidate.mismatch() == null;
                if (pick == null && (policy.equals("other") != matches)) {
                    pick = candidate;
                }
            }
            if (pick == null) {
                throw new IllegalStateException(policy.equals("other")
                    ? "No Vulkan device differs from the OpenGL context's, so a mismatch cannot be shown here."
                    : "No Vulkan device matches the OpenGL context's device and driver UUIDs.");
            }
            chosen = pick;
            physical = pick.device();
            queueFamily = queueFamily(physical, queueKind);
            try (MemoryStack stack = stackPush()) {
                Set<String> available = deviceExtensions(stack, physical);
                List<String> wanted = new ArrayList<>(List.of(
                    KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,
                    KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME));
                for (String required : wanted) {
                    if (!available.contains(required)) {
                        throw new IllegalStateException("The Vulkan device lacks " + required + ".");
                    }
                }
                if (available.contains(KHRCalibratedTimestamps.VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)) {
                    wanted.add(KHRCalibratedTimestamps.VK_KHR_CALIBRATED_TIMESTAMPS_EXTENSION_NAME);
                    calibration = CALIBRATION_KHR;
                } else if (available.contains(EXTCalibratedTimestamps.VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME)) {
                    wanted.add(EXTCalibratedTimestamps.VK_EXT_CALIBRATED_TIMESTAMPS_EXTENSION_NAME);
                    calibration = CALIBRATION_EXT;
                } else {
                    calibration = CALIBRATION_NONE;
                }
                memoryBudget = available.contains(EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
                if (memoryBudget) {
                    wanted.add(EXTMemoryBudget.VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
                }
                VkPhysicalDeviceVulkan12Features supported = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
                vkGetPhysicalDeviceFeatures2(physical,
                    VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(supported.address()));
                if (!supported.timelineSemaphore()) {
                    throw new IllegalStateException("The Vulkan device has no timeline semaphores.");
                }
                VkPhysicalDeviceVulkan12Features enabled = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default()
                    .timelineSemaphore(true);
                PointerBuffer names = stack.mallocPointer(wanted.size());
                wanted.forEach(name -> names.put(stack.UTF8(name)));
                names.flip();
                VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack);
                queues.get(0).sType$Default().queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1.0f));
                VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(enabled.address())
                    .pQueueCreateInfos(queues).ppEnabledExtensionNames(names);
                PointerBuffer handle = stack.mallocPointer(1);
                check(vkCreateDevice(physical, deviceInfo, null, handle), "vkCreateDevice");
                device = new VkDevice(handle.get(0), physical, deviceInfo);
                vkGetDeviceQueue(device, queueFamily, 0, handle);
                queue = new VkQueue(handle.get(0), device);
                VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
                vkGetPhysicalDeviceProperties2(physical, properties);
                timestampPeriod = properties.properties().limits().timestampPeriod();
                timestampMask = timestampMask(physical, queueFamily);
                memory = VkPhysicalDeviceMemoryProperties.malloc();
                vkGetPhysicalDeviceMemoryProperties(physical, memory);
                host.info(String.format(Locale.ROOT, "Vulkan device opened: %s, queue family %d (%s), timestamps %.3f ns "
                        + "a tick, calibration %s, memory budget %s, validation %s", chosen.name(), queueFamily, queueKind,
                    timestampPeriod, new String[]{"none", "KHR", "EXT"}[calibration], memoryBudget ? "yes" : "no",
                    validation ? "on" : "off"));
            }
        } catch (RuntimeException e) {
            destroyInstance();
            throw e;
        }
    }

    private void createMessenger() {
        try (MemoryStack stack = stackPush()) {
            VkDebugUtilsMessengerCreateInfoEXT info = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                    | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                    | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                    | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(callback);
            LongBuffer handle = stack.mallocLong(1);
            check(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, info, null, handle), "vkCreateDebugUtilsMessengerEXT");
            messenger = handle.get(0);
        }
    }

    private List<Candidate> candidates(List<DeviceIdentity> glDevices) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
            PointerBuffer handles = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, handles), "vkEnumeratePhysicalDevices");
            List<Candidate> found = new ArrayList<>();
            for (int i = 0; i < count.get(0); i++) {
                VkPhysicalDevice physicalDevice = new VkPhysicalDevice(handles.get(i), instance);
                VkPhysicalDeviceIDProperties ids = VkPhysicalDeviceIDProperties.calloc(stack).sType$Default();
                VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                    .pNext(ids.address());
                vkGetPhysicalDeviceProperties2(physicalDevice, properties);
                byte[] deviceUuid = new byte[VK_UUID_SIZE];
                byte[] driverUuid = new byte[VK_UUID_SIZE];
                ids.deviceUUID().get(0, deviceUuid);
                ids.driverUUID().get(0, driverUuid);
                byte[] luid = null;
                if (ids.deviceLUIDValid()) {
                    luid = new byte[VK_LUID_SIZE];
                    ids.deviceLUID().get(0, luid);
                }
                DeviceIdentity identity = new DeviceIdentity(deviceUuid, driverUuid, luid,
                    ids.deviceLUIDValid() ? ids.deviceNodeMask() : 0);
                found.add(new Candidate(physicalDevice, properties.properties().deviceNameString(),
                    properties.properties().deviceType(), properties.properties().apiVersion(),
                    properties.properties().driverVersion(), identity, DeviceIdentity.mismatch(glDevices, identity)));
            }
            return found;
        }
    }

    private static int queueFamily(VkPhysicalDevice physical, String kind) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            for (int i = 0; i < families.capacity(); i++) {
                int flags = families.get(i).queueFlags();
                boolean compute = (flags & VK_QUEUE_COMPUTE_BIT) != 0;
                boolean graphics = (flags & VK_QUEUE_GRAPHICS_BIT) != 0;
                if (compute && families.get(i).timestampValidBits() > 0 && (kind.equals("compute") ? !graphics : graphics)) {
                    return i;
                }
            }
            throw new IllegalStateException("The Vulkan device has no " + kind + " queue family with timestamps.");
        }
    }

    private static long timestampMask(VkPhysicalDevice physical, int family) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
            int bits = families.get(family).timestampValidBits();
            return bits >= 64 ? -1L : (1L << bits) - 1;
        }
    }

    /** A memory type in {@code typeBits} with every property in {@code properties}. */
    int memoryType(int typeBits, int properties) {
        for (int i = 0; i < memory.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 && (memory.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        throw new IllegalStateException("No Vulkan memory type fits bits 0x" + Integer.toHexString(typeBits) + ".");
    }

    /**
     * Reads the device's timestamp counter and the performance counter together, or null without
     * calibrated timestamps. Returns {device ticks, performance counter, maximum deviation in ns}.
     */
    long[] calibrate() {
        if (calibration == CALIBRATION_NONE) {
            return null;
        }
        try (MemoryStack stack = stackPush()) {
            VkCalibratedTimestampInfoKHR.Buffer infos = VkCalibratedTimestampInfoKHR.calloc(2, stack);
            infos.get(0).sType$Default().timeDomain(KHRCalibratedTimestamps.VK_TIME_DOMAIN_DEVICE_KHR);
            infos.get(1).sType$Default().timeDomain(KHRCalibratedTimestamps.VK_TIME_DOMAIN_QUERY_PERFORMANCE_COUNTER_KHR);
            LongBuffer values = stack.mallocLong(2);
            LongBuffer deviation = stack.mallocLong(1);
            int result = calibration == CALIBRATION_KHR
                ? KHRCalibratedTimestamps.vkGetCalibratedTimestampsKHR(device, infos, values, deviation)
                : EXTCalibratedTimestamps.vkGetCalibratedTimestampsEXT(device, infos, values, deviation);
            if (result != VK_SUCCESS) {
                return null;
            }
            return new long[]{values.get(0) & timestampMask, values.get(1), deviation.get(0)};
        }
    }

    /** The process's use of device-local memory as VK_EXT_memory_budget estimates it, in bytes, or -1. */
    long deviceLocalUsage() {
        if (!memoryBudget) {
            return -1;
        }
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryBudgetPropertiesEXT budget = VkPhysicalDeviceMemoryBudgetPropertiesEXT.calloc(stack)
                .sType$Default();
            VkPhysicalDeviceMemoryProperties2 properties = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default()
                .pNext(budget.address());
            vkGetPhysicalDeviceMemoryProperties2(physical, properties);
            long used = 0;
            for (int i = 0; i < properties.memoryProperties().memoryHeapCount(); i++) {
                if ((properties.memoryProperties().memoryHeaps(i).flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    used += budget.heapUsage(i);
                }
            }
            return used;
        }
    }

    private static Set<String> instanceLayers(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumerateInstanceLayerProperties(count, null), "vkEnumerateInstanceLayerProperties");
        VkLayerProperties.Buffer layers = VkLayerProperties.malloc(count.get(0), stack);
        check(vkEnumerateInstanceLayerProperties(count, layers), "vkEnumerateInstanceLayerProperties");
        Set<String> names = new HashSet<>();
        layers.forEach(layer -> names.add(layer.layerNameString()));
        return names;
    }

    private static Set<String> instanceExtensions(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumerateInstanceExtensionProperties((java.nio.ByteBuffer) null, count, null),
            "vkEnumerateInstanceExtensionProperties");
        VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(count.get(0), stack);
        check(vkEnumerateInstanceExtensionProperties((java.nio.ByteBuffer) null, count, extensions),
            "vkEnumerateInstanceExtensionProperties");
        Set<String> names = new HashSet<>();
        extensions.forEach(extension -> names.add(extension.extensionNameString()));
        return names;
    }

    private static Set<String> deviceExtensions(MemoryStack stack, VkPhysicalDevice physical) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumerateDeviceExtensionProperties(physical, (java.nio.ByteBuffer) null, count, null),
            "vkEnumerateDeviceExtensionProperties");
        VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(count.get(0), stack);
        check(vkEnumerateDeviceExtensionProperties(physical, (java.nio.ByteBuffer) null, count, extensions),
            "vkEnumerateDeviceExtensionProperties");
        Set<String> names = new HashSet<>();
        extensions.forEach(extension -> names.add(extension.extensionNameString()));
        return names;
    }

    static String typeName(int type) {
        return switch (type) {
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "integrated";
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "discrete";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "virtual";
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU";
            default -> "other";
        };
    }

    static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(call + " returned " + result + ".");
        }
    }

    @Override
    public void close() {
        vkDeviceWaitIdle(device);
        vkDestroyDevice(device, null);
        memory.free();
        destroyInstance();
    }

    private void destroyInstance() {
        if (messenger != 0) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
            messenger = 0;
        }
        if (instance != null) {
            vkDestroyInstance(instance, null);
        }
        if (callback != null) {
            callback.free();
        }
    }
}
