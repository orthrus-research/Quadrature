package cc.orthrus.quadrature.nvidia;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.Kernel32;

import java.nio.LongBuffer;

/**
 * The two Windows calls the handoff needs that neither graphics binding makes: closing the NT handles
 * exported memory and semaphores arrive in, and reading the performance counter that Vulkan's calibrated
 * timestamps and Java's {@link System#nanoTime} both count in. Called through LWJGL's own kernel32 binding.
 */
final class Win32 {

    private static final long CLOSE_HANDLE = function("CloseHandle");
    private static final long QUERY_PERFORMANCE_COUNTER = function("QueryPerformanceCounter");
    private static final long QUERY_PERFORMANCE_FREQUENCY = function("QueryPerformanceFrequency");

    private Win32() {
    }

    private static long function(String name) {
        long address = Kernel32.getLibrary().getFunctionAddress(name);
        if (address == 0) {
            throw new IllegalStateException("kernel32 has no " + name + ".");
        }
        return address;
    }

    /**
     * Closes an NT handle. Neither OpenGL nor Vulkan takes ownership of a Win32 handle it imports, and an
     * exported NT handle holds a reference to its payload until it is closed (EXT_external_objects_win32,
     * 6.1; vkGetMemoryWin32HandleKHR).
     */
    static void closeHandle(long handle) {
        if (handle != 0 && JNI.invokePI(handle, CLOSE_HANDLE) == 0) {
            throw new IllegalStateException("CloseHandle failed for an exported handle.");
        }
    }

    /** The performance counter's ticks a second. */
    static long performanceFrequency() {
        return read(QUERY_PERFORMANCE_FREQUENCY);
    }

    /** The performance counter now. */
    static long performanceCounter() {
        return read(QUERY_PERFORMANCE_COUNTER);
    }

    private static long read(long function) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer value = stack.mallocLong(1);
            if (JNI.invokePI(org.lwjgl.system.MemoryUtil.memAddress(value), function) == 0) {
                throw new IllegalStateException("The performance counter could not be read.");
            }
            return value.get(0);
        }
    }
}
