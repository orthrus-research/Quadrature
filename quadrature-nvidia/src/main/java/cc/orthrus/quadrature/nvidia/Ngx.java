package cc.orthrus.quadrature.nvidia;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * DLSS Super Resolution through NVIDIA's NGX runtime, by way of the shim library (ngx_shim.cpp) built against an
 * SDK the builder supplies. NGX's own entry points are compiled into whatever calls them, so the shim links them
 * and this class calls the shim through the Foreign Function and Memory API. The shim is loaded from a path given
 * at run time; neither it nor any NVIDIA library is in the module's JAR.
 *
 * <p>Every call answers an NGX result: success is 1, and failures carry 0xBAD in their top bits. A failure here
 * becomes an exception naming the call and NGX's text for the result.
 */
final class Ngx implements AutoCloseable {

    /** The project's identifier for NGX: a GUID-like string, as an engine of its own (engine type CUSTOM) gives. */
    static final String PROJECT_ID = "c9a8af32-0230-4846-a446-d50a216990f7";
    static final String ENGINE_VERSION = "1";

    /** NGX's quality modes, as its enumeration numbers them. */
    static final int PERFORMANCE = 0;
    static final int BALANCED = 1;
    static final int QUALITY = 2;
    static final int ULTRA_PERFORMANCE = 3;
    static final int DLAA = 5;

    /** Feature flags: motion vectors at render resolution, and depth with near at one. */
    static final int MOTION_LOW_RESOLUTION = 1 << 1;
    static final int DEPTH_INVERTED = 1 << 3;

    /** One image as the shim reads it: image, view, format, aspect, width, height. */
    static final MemoryLayout IMAGE = MemoryLayout.structLayout(JAVA_LONG.withName("image"), JAVA_LONG.withName("view"),
        JAVA_INT.withName("format"), JAVA_INT.withName("aspect"), JAVA_INT.withName("width"), JAVA_INT.withName("height"));

    private static final int NAMES = 16384;

    private final Arena arena = Arena.ofShared();
    private final MethodHandle instanceExtensions;
    private final MethodHandle deviceExtensions;
    private final MethodHandle init;
    private final MethodHandle capability;
    private final MethodHandle optimal;
    private final MethodHandle create;
    private final MethodHandle evaluate;
    private final MethodHandle release;
    private final MethodHandle shutdown;
    private final MethodHandle resultText;
    private final MemorySegment projectId;
    private final MemorySegment engineVersion;
    private final MemorySegment dataPath;
    private final MemorySegment dllFolder;
    private boolean initialized;

    /**
     * @param shim      the shim library
     * @param dataPath  where NGX writes its logs
     * @param dllFolder where NGX finds nvngx_dlss.dll, besides the program's own folder
     */
    Ngx(Path shim, Path dataPath, Path dllFolder) {
        if (!Files.isRegularFile(shim)) {
            arena.close();
            throw new IllegalStateException("The NGX shim is not at " + shim + ".");
        }
        SymbolLookup lookup = SymbolLookup.libraryLookup(shim, arena);
        Linker linker = Linker.nativeLinker();
        instanceExtensions = handle(linker, lookup, "qn_instance_extensions", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS,
            ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        deviceExtensions = handle(linker, lookup, "qn_device_extensions", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS,
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        init = handle(linker, lookup, "qn_init", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS,
            ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        capability = handle(linker, lookup, "qn_capability", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS,
            ADDRESS));
        optimal = handle(linker, lookup, "qn_optimal", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        create = handle(linker, lookup, "qn_create", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT,
            JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        evaluate = handle(linker, lookup, "qn_evaluate", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT,
            JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_INT));
        release = handle(linker, lookup, "qn_release", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        shutdown = handle(linker, lookup, "qn_shutdown", FunctionDescriptor.of(JAVA_INT));
        resultText = handle(linker, lookup, "qn_result_text", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, JAVA_INT));
        projectId = arena.allocateFrom(PROJECT_ID);
        engineVersion = arena.allocateFrom(ENGINE_VERSION);
        this.dataPath = wide(dataPath.toAbsolutePath().toString());
        this.dllFolder = wide(dllFolder == null ? "" : dllFolder.toAbsolutePath().toString());
    }

    private static MethodHandle handle(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(lookup.find(name)
            .orElseThrow(() -> new IllegalStateException("The NGX shim has no " + name + ".")), descriptor);
    }

    /** A zero-ended UTF-16 string, as Windows' wchar_t is. */
    private MemorySegment wide(String text) {
        return arena.allocateFrom(ValueLayout.JAVA_CHAR, (text + '\0').toCharArray());
    }

    /** The instance extensions NGX needs for DLSS, before the instance is made. */
    List<String> instanceExtensions() {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment out = call.allocate(NAMES);
            MemorySegment count = call.allocate(JAVA_INT);
            check("instance extension requirements", (int) instanceExtensions.invokeExact(projectId, engineVersion, dataPath,
                dllFolder, out, NAMES, count));
            return names(out.getString(0, StandardCharsets.UTF_8));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** The device extensions NGX needs for DLSS on a physical device, before the device is made. */
    List<String> deviceExtensions(long instance, long physicalDevice) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment out = call.allocate(NAMES);
            MemorySegment count = call.allocate(JAVA_INT);
            check("device extension requirements", (int) deviceExtensions.invokeExact(projectId, engineVersion, dataPath,
                dllFolder, MemorySegment.ofAddress(instance), MemorySegment.ofAddress(physicalDevice), out, NAMES, count));
            return names(out.getString(0, StandardCharsets.UTF_8));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** NGX's number for a render preset named by its letter (J, K, L, M), or 0, NGX's own choice, for "default". */
    static int preset(String name) {
        String letter = name.trim().toUpperCase(Locale.ROOT);
        return switch (letter) {
            case "DEFAULT" -> 0;
            case "J" -> 10;
            case "K" -> 11;
            case "L" -> 12;
            case "M" -> 13;
            default -> throw new IllegalArgumentException("DLSS preset " + name + " is not one of default, J, K, L, M.");
        };
    }

    /**
     * The quality mode whose render size is nearest a ratio of render to output side: DLAA at 1, Quality at two
     * thirds, Balanced at 0.58, Performance at one half, Ultra Performance at one third (DLSS's guide, 3.7.1.1).
     * The scaler fixes the render size, so the mode follows it; NGX's optimal settings then say whether the size
     * lies in the mode's range.
     */
    static int qualityFor(double ratio) {
        if (!(ratio > 0) || ratio > 1) {
            throw new IllegalArgumentException("A render-to-output ratio of " + ratio + " is not in (0, 1].");
        }
        double[] ratios = {1, 2 / 3.0, 1 / 1.724, 0.5, 1 / 3.0};
        int[] modes = {DLAA, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE};
        int best = 0;
        for (int i = 1; i < ratios.length; i++) {
            if (Math.abs(ratios[i] - ratio) < Math.abs(ratios[best] - ratio)) {
                best = i;
            }
        }
        return modes[best];
    }

    /** A quality mode's name as the handoff's options name it. */
    static String qualityName(int quality) {
        return switch (quality) {
            case PERFORMANCE -> "performance";
            case BALANCED -> "balanced";
            case QUALITY -> "quality";
            case ULTRA_PERFORMANCE -> "ultraPerformance";
            case DLAA -> "dlaa";
            default -> "mode " + quality;
        };
    }

    /** NGX's number for a quality mode named as the handoff's options name it. */
    static int quality(String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "performance" -> PERFORMANCE;
            case "balanced" -> BALANCED;
            case "quality" -> QUALITY;
            case "ultraperformance" -> ULTRA_PERFORMANCE;
            case "dlaa" -> DLAA;
            default -> throw new IllegalArgumentException("DLSS quality " + name
                + " is not one of performance, balanced, quality, ultraPerformance, dlaa.");
        };
    }

    /** Extension names as the shim writes them: each ended by a newline. */
    static List<String> names(String written) {
        List<String> names = new ArrayList<>();
        for (String name : written.split("\n")) {
            if (!name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    /** Starts NGX on the handoff's device; the Vulkan loader's two lookups are handed over so NGX uses the same one. */
    void init(long instance, long physicalDevice, long device, long getInstanceProcAddr, long getDeviceProcAddr) {
        try {
            check("initialization", (int) init.invokeExact(projectId, engineVersion, dataPath, dllFolder,
                MemorySegment.ofAddress(instance), MemorySegment.ofAddress(physicalDevice), MemorySegment.ofAddress(device),
                MemorySegment.ofAddress(getInstanceProcAddr), MemorySegment.ofAddress(getDeviceProcAddr)));
            initialized = true;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Null when DLSS Super Resolution runs here; otherwise why not. */
    String unavailable() {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment available = call.allocate(JAVA_INT);
            MemorySegment driver = call.allocate(JAVA_INT);
            MemorySegment major = call.allocate(JAVA_INT);
            MemorySegment minor = call.allocate(JAVA_INT);
            int result = (int) capability.invokeExact(available, driver, major, minor);
            if (failed(result) || available.get(JAVA_INT, 0) == 0) {
                return driver.get(JAVA_INT, 0) != 0
                    ? String.format(Locale.ROOT, "DLSS needs driver %d.%d or later", major.get(JAVA_INT, 0), minor.get(JAVA_INT, 0))
                    : "DLSS Super Resolution is not available here (" + text(result) + ")";
            }
            return null;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The render sizes DLSS takes at an output size and quality mode: {optimal width, height, largest width,
     * height, smallest width, height}. A size between the smallest and the largest is accepted.
     */
    int[] optimal(int outputWidth, int outputHeight, int quality) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment sizes = call.allocate(JAVA_INT, 6);
            check("optimal settings", (int) optimal.invokeExact(outputWidth, outputHeight, quality, sizes));
            return sizes.toArray(JAVA_INT);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records a feature's creation into a command buffer, which the caller submits and waits for. */
    void create(int slot, long commandBuffer, int renderWidth, int renderHeight, int outputWidth, int outputHeight, int quality,
                int preset, int flags) {
        try {
            check("feature creation", (int) create.invokeExact(slot, MemorySegment.ofAddress(commandBuffer), renderWidth,
                renderHeight, outputWidth, outputHeight, quality, preset, flags));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Four images for {@link #evaluate}, in the order color, depth, motion, output; kept for as long as the images are. */
    MemorySegment images(SharedImage color, SharedImage depth, SharedImage motion, SharedImage output) {
        MemorySegment images = arena.allocate(IMAGE, 4);
        SharedImage[] four = {color, depth, motion, output};
        for (int i = 0; i < 4; i++) {
            MemorySegment one = images.asSlice(i * IMAGE.byteSize(), IMAGE.byteSize());
            one.set(JAVA_LONG, 0, four[i].image);
            one.set(JAVA_LONG, 8, four[i].view);
            one.set(JAVA_INT, 16, four[i].vkFormat);
            one.set(JAVA_INT, 20, four[i].viewAspect());
            one.set(JAVA_INT, 24, four[i].width);
            one.set(JAVA_INT, 28, four[i].height);
        }
        return images;
    }

    /** Records an evaluation of a slot's feature into a command buffer; returns NGX's result rather than throwing. */
    int evaluate(int slot, long commandBuffer, MemorySegment images, int renderWidth, int renderHeight, float jitterX,
                 float jitterY, boolean reset) {
        try {
            return (int) evaluate.invokeExact(slot, MemorySegment.ofAddress(commandBuffer), images, renderWidth, renderHeight,
                jitterX, jitterY, 1.0f, 1.0f, reset ? 1 : 0);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    void release(int slot) {
        try {
            check("feature release", (int) release.invokeExact(slot));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean failed(int result) {
        return (result & 0xFFF00000) == 0xBAD00000;
    }

    void check(String what, int result) {
        if (failed(result)) {
            throw new IllegalStateException("NGX " + what + " failed: " + text(result) + String.format(Locale.ROOT,
                " (0x%08X)", result));
        }
    }

    String text(int result) {
        try (Arena call = Arena.ofConfined()) {
            MemorySegment out = call.allocate(ValueLayout.JAVA_CHAR, 256);
            resultText.invokeExact(result, out, 256);
            char[] chars = out.toArray(ValueLayout.JAVA_CHAR);
            int end = 0;
            while (end < chars.length && chars[end] != 0) {
                end++;
            }
            return new String(chars, 0, end);
        } catch (Throwable e) {
            return String.format(Locale.ROOT, "result 0x%08X", result);
        }
    }

    /** Releases every feature and shuts NGX down on the device; call while the device still lives, after it is idle. */
    @Override
    public void close() {
        try {
            if (initialized) {
                int result = (int) shutdown.invokeExact();
                initialized = false;
                check("shutdown", result);
            }
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        } finally {
            arena.close();
        }
    }
}
