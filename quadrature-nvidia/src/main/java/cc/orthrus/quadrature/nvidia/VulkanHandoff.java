package cc.orthrus.quadrature.nvidia;

import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memFree;

import cc.orthrus.quadrature.core.handoff.DeviceIdentity;
import cc.orthrus.quadrature.core.handoff.Handoff;
import cc.orthrus.quadrature.core.handoff.HandoffSchedule;
import cc.orthrus.quadrature.core.handoff.HandoffSchedule.Phase;
import cc.orthrus.quadrature.core.handoff.RenderSize;
import cc.orthrus.quadrature.core.handoff.Samples;
import cc.orthrus.quadrature.core.handoff.SharedImageSizes;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTMemoryObject;
import org.lwjgl.opengl.EXTMemoryObjectWin32;
import org.lwjgl.opengl.EXTSemaphore;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.NVTimelineSemaphore;
import org.lwjgl.opengl.NVXGPUMemoryInfo;
import org.lwjgl.vulkan.VK10;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The measurement form of the GL to Vulkan handoff (D21): a Vulkan device beside the game's context on
 * the same GPU, color, depth and motion images at render resolution and an output image at output
 * resolution, all allocated by Vulkan and imported by OpenGL, and a round trip each frame in which OpenGL
 * signals, Vulkan evaluates, and OpenGL waits for it and finishes. The evaluation is a stand-in where DLSS would
 * evaluate, or, with {@code evaluate=dlss}, DLSS Super Resolution itself through NGX and the shim the builder makes.
 *
 * <p>The parts of the handoff take turns by {@link HandoffSchedule}, so each is measured over the same run.
 * Every frame records OpenGL timestamps around its parts and, with a round trip, Vulkan timestamps around
 * its batch; both are read a few frames later, without waiting, and put on the client's clock through
 * calibrations taken at the start of each turn. Statistics go to the log and to the client's
 * {@code quadrature-handoff} folder.
 */
public final class VulkanHandoff implements Handoff {

    /** Frames between issuing OpenGL timestamps and reading them. Fewer than {@link StandIn#RING}. */
    private static final int GL_RING = 4;
    /** Frame records kept: at least {@link #GL_RING}. */
    private static final int RECORDS = 8;
    /** The stamp's period: frame numbers below it are exact in a half float. */
    private static final int STAMP_PERIOD = 1024;

    private static final DateTimeFormatter RUN = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Host host;
    private VulkanDevice vulkan;
    private SharedSemaphores semaphores;
    private StandIn standIn;
    /** DLSS through NGX, or null when the stand-in evaluates. */
    private Ngx ngx;
    private int dlssQuality;
    private String evaluation = "stand-in";
    /** The quality option as set; empty, the evaluation of the scaler's picture takes the mode from its ratio. */
    private String qualitySetting = "";
    /** Multipliers on the scaler's offset as DLSS takes it, x then y ({@code jitterSign}, "1,1" unless set). */
    private double jitterSignX = 1;
    private double jitterSignY = 1;
    /** Evaluating the scaler's picture ({@link #evaluate}) rather than measuring the game's framebuffer. */
    private boolean forScaler;
    /** Whether the scaler's depth was reversed when the images and DLSS's feature were last made. */
    private boolean depthInvertedMade;
    private SharedImage color;
    private SharedImage depth;
    private SharedImage motion;
    private SharedImage output;

    private HandoffSchedule schedule;
    private double scale;
    private boolean present;
    private boolean recreateOnWorldChange;
    private int checkEvery;
    private int reportEvery;

    // OpenGL side, kept so a frame allocates nothing.
    private final int[][] glQueries = new int[GL_RING][4];
    private final boolean[][] glIssued = new boolean[GL_RING][4];
    private IntBuffer noBuffers;
    private IntBuffer textures;
    private IntBuffer signalLayouts;
    private IntBuffer waitLayouts;
    private FloatBuffer zero;
    private FloatBuffer stamp;
    private FloatBuffer readback;
    private long timeline;
    /** One texel OpenGL owns, which the check copies the output's stamp into and reads. */
    private int stampTexel;

    // Clocks: OpenGL's timestamp minus the client's nanoTime, and a Vulkan calibration pair.
    private long glOffset;
    private long vkTicks0;
    private long vkNanos0;
    private boolean vkCalibrated;
    private long performanceFrequency;
    private long worstDeviation;

    // Per-frame records, read back GL_RING frames later.
    private final FrameRecord[] records = new FrameRecord[RECORDS];
    private long lastStart = -1;
    private long lastFrame = -1;
    private boolean lastDisturbed;
    private long previousG0 = -1;
    private long previousG0Frame = -1;
    private Phase previousG0Phase;
    private boolean previousG0Measured;
    private long roundTrips;
    /** Round trips still to prove before OpenGL waits on one unproved. */
    private int unproven = 2;
    private static final long PROOF_TIMEOUT = 2_000_000_000L;
    /** Whether OpenGL can copy the game's depth into the shared depth image, which needs the same format. */
    private boolean depthFilled;

    // What the run found.
    private final Map<Phase, PhaseStatistics> statistics = new EnumMap<>(Phase.class);
    private final List<String> events = new ArrayList<>();
    private long droppedGl;
    private long droppedVk;
    private int checksPassed;
    private int checksFailed;
    private int glErrorFrames;
    private boolean checkDue = true;
    private long lastCheck;
    private int recreations;
    private int worldChanges;
    private long frames;
    private String run;
    private BufferedWriter csv;
    private long availableAtOpenKiB = -1;
    private long availableWithDeviceKiB = -1;
    private String imageReport = "none";

    @Override
    public String name() {
        return "Vulkan handoff";
    }

    @Override
    public String open(Host host) {
        this.host = host;
        GLCapabilities caps = GL.getCapabilities();
        List<String> missing = new ArrayList<>();
        boolean timelineWanted = !host.option("semaphores", "timeline").equals("binary");
        if (!caps.GL_EXT_memory_object) missing.add("GL_EXT_memory_object");
        if (!caps.GL_EXT_memory_object_win32) missing.add("GL_EXT_memory_object_win32");
        if (!caps.GL_EXT_semaphore) missing.add("GL_EXT_semaphore");
        if (!caps.GL_EXT_semaphore_win32) missing.add("GL_EXT_semaphore_win32");
        if (timelineWanted && !caps.GL_NV_timeline_semaphore) missing.add("GL_NV_timeline_semaphore");
        if (!missing.isEmpty()) {
            return "The OpenGL context lacks " + String.join(", ", missing) + ".";
        }
        String loaded = org.lwjgl.Version.getVersion();
        if (!loaded.startsWith(BindingRelease.LWJGL) || (loaded.length() > BindingRelease.LWJGL.length()
                && Character.isDigit(loaded.charAt(BindingRelease.LWJGL.length())))) {
            return "The game loaded LWJGL " + loaded + "; the module's Vulkan binding is LWJGL " + BindingRelease.LWJGL + ".";
        }
        try {
            schedule = HandoffSchedule.parse(host.option("phases", "off,sync,standin,copy,full"),
                Integer.parseInt(host.option("segment", "120")), Integer.parseInt(host.option("settle", "4")));
            scale = Double.parseDouble(host.option("scale", "0.6666667"));
            present = Boolean.parseBoolean(host.option("present", "true"));
            recreateOnWorldChange = host.option("world", "keep").equals("recreate");
            checkEvery = Integer.parseInt(host.option("check", "600"));
            reportEvery = Integer.parseInt(host.option("report", "6000"));
            String evaluate = host.option("evaluate", "standin");
            int dlssPreset = 0;
            int dlssFlags = 0;
            if (evaluate.equals("dlss")) {
                String shim = host.option("shim", "");
                if (shim.isEmpty()) {
                    throw new IllegalStateException("evaluate=dlss needs the NGX shim's path in the shim option.");
                }
                Path shimPath = Path.of(shim).toAbsolutePath();
                String folder = host.option("dlssPath", "");
                qualitySetting = host.option("quality", "");
                dlssQuality = Ngx.quality(qualitySetting.isEmpty() ? "performance" : qualitySetting);
                String[] signs = host.option("jitterSign", "1,1").split(",");
                if (signs.length != 2) {
                    throw new IllegalArgumentException("jitterSign=" + host.option("jitterSign", "") + " is not two numbers.");
                }
                jitterSignX = Double.parseDouble(signs[0].trim());
                jitterSignY = Double.parseDouble(signs[1].trim());
                dlssPreset = Ngx.preset(host.option("preset", "default"));
                boolean inverted = Boolean.parseBoolean(host.option("depthInverted", "false"));
                dlssFlags = Ngx.MOTION_LOW_RESOLUTION | (inverted ? Ngx.DEPTH_INVERTED : 0);
                ngx = new Ngx(shimPath, host.outputDirectory(), folder.isEmpty() ? shimPath.getParent() : Path.of(folder));
                evaluation = String.format(Locale.ROOT, "DLSS Super Resolution (quality %s, preset %s, depth %s; for the "
                        + "scaler's picture, offsets taken as x %+.0f, y %+.0f)",
                    qualitySetting.isEmpty() ? "performance, or from the scaler's ratio" : qualitySetting,
                    host.option("preset", "default"), inverted ? "inverted" : "not inverted, or as the scaler's", jitterSignX,
                    jitterSignY);
            } else if (!evaluate.equals("standin")) {
                throw new IllegalArgumentException("evaluate=" + evaluate + " is not one of standin, dlss.");
            }
            List<DeviceIdentity> glDevices = glIdentity();
            for (DeviceIdentity identity : glDevices) {
                host.info("OpenGL device " + identity.describe());
            }
            Gl.clearErrors();
            availableAtOpenKiB = availableKiB();
            Ngx dlss = ngx;
            vulkan = new VulkanDevice(host, glDevices, host.option("device", "match"), host.option("queue", "graphics"),
                Boolean.parseBoolean(host.option("validation", "false")),
                dlss == null ? List.of() : dlss.instanceExtensions(),
                (instance, physical) -> dlss == null ? List.of() : dlss.deviceExtensions(instance.address(), physical.address()),
                dlss != null);
            if (dlss != null) {
                dlss.init(vulkan.instance.address(), vulkan.physical.address(), vulkan.device.address(),
                    VulkanDevice.getInstanceProcAddr(), vulkan.getDeviceProcAddr());
                String unavailable = dlss.unavailable();
                if (unavailable != null) {
                    throw new IllegalStateException(unavailable + ".");
                }
            }
            availableWithDeviceKiB = availableKiB();
            semaphores = new SharedSemaphores(vulkan, timelineWanted);
            standIn = new StandIn(vulkan, semaphores, dlss, dlssQuality, dlssPreset, dlssFlags);
            for (int[] row : glQueries) {
                GL15.glGenQueries(row);
            }
            stampTexel = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(stampTexel, 1, GL30.GL_RGBA16F, 1, 1);
            // No buffers take part in the round trip; a buffer of one with nothing remaining says so.
            noBuffers = memAllocInt(1).limit(0);
            textures = memAllocInt(4);
            signalLayouts = memAllocInt(4).put(0, EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT)
                .put(1, EXTSemaphore.GL_LAYOUT_DEPTH_STENCIL_READ_ONLY_EXT).put(2, EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT)
                .put(3, EXTSemaphore.GL_LAYOUT_GENERAL_EXT);
            waitLayouts = memAllocInt(4).put(0, EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT)
                .put(1, EXTSemaphore.GL_LAYOUT_DEPTH_STENCIL_READ_ONLY_EXT).put(2, EXTSemaphore.GL_LAYOUT_SHADER_READ_ONLY_EXT)
                .put(3, EXTSemaphore.GL_LAYOUT_GENERAL_EXT);
            zero = memAllocFloat(2).put(0, 0).put(1, 0);
            stamp = memAllocFloat(2);
            readback = memAllocFloat(2);
            performanceFrequency = Win32.performanceFrequency();
            for (Phase phase : schedule.order()) {
                statistics.put(phase, new PhaseStatistics());
            }
            for (int i = 0; i < RECORDS; i++) {
                records[i] = new FrameRecord();
            }
            run = LocalDateTime.now().format(RUN);
            csv = Files.newBufferedWriter(host.outputDirectory().resolve("frames-" + run + ".csv"), StandardCharsets.UTF_8);
            csv.write(FrameRecord.HEADER);
            csv.newLine();
            Gl.check("opening the handoff");
            host.info(String.format(Locale.ROOT, "Handoff opened (run %s): evaluation %s, %s semaphores, phases %s in turns "
                    + "of %d frames (first %d not measured), render scale %.4f, output %s, check every %d frames, world change: "
                    + "%s. GPU memory available %d MiB before the device, %d MiB with it.", run, evaluation,
                semaphores.timeline ? "timeline" : "binary", schedule.order(), schedule.segment(), schedule.settle(), scale,
                present ? "written into the frame" : "not written into the frame", checkEvery,
                recreateOnWorldChange ? "images made again" : "images kept", availableAtOpenKiB / 1024,
                availableWithDeviceKiB / 1024));
            return null;
        } catch (RuntimeException | IOException | OutOfMemoryError | LinkageError e) {
            // No error leaves open(): the game answers an OutOfMemoryError, which is also how LWJGL reports a full
            // thread stack, by dropping the world, and a LinkageError would end the client.
            String reason = e.toString();
            try {
                close();
            } catch (RuntimeException | OutOfMemoryError | LinkageError cleanup) {
                reason += " (and closing failed: " + cleanup + ")";
            }
            return reason;
        }
    }

    /** OpenGL's own identity, one entry per device UUID (EXT_external_objects, 4.2.1). */
    private static List<DeviceIdentity> glIdentity() {
        int count = GL11.glGetInteger(EXTMemoryObject.GL_NUM_DEVICE_UUIDS_EXT);
        ByteBuffer driver = BufferUtils.createByteBuffer(EXTMemoryObject.GL_UUID_SIZE_EXT);
        EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObject.GL_DRIVER_UUID_EXT, driver);
        ByteBuffer luid = BufferUtils.createByteBuffer(EXTMemoryObjectWin32.GL_LUID_SIZE_EXT);
        EXTMemoryObject.glGetUnsignedBytevEXT(EXTMemoryObjectWin32.GL_DEVICE_LUID_EXT, luid);
        int nodeMask = GL11.glGetInteger(EXTMemoryObjectWin32.GL_DEVICE_NODE_MASK_EXT);
        Gl.check("reading the context's device identity");
        List<DeviceIdentity> identities = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ByteBuffer device = BufferUtils.createByteBuffer(EXTMemoryObject.GL_UUID_SIZE_EXT);
            EXTMemoryObject.glGetUnsignedBytei_vEXT(EXTMemoryObject.GL_DEVICE_UUID_EXT, i, device);
            identities.add(new DeviceIdentity(bytes(device), bytes(driver), bytes(luid), nodeMask));
        }
        return identities;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    /** GPU memory the driver reports free, in KiB, or -1 where it reports none. */
    private static long availableKiB() {
        if (!GL.getCapabilities().GL_NVX_gpu_memory_info) {
            return -1;
        }
        return GL11.glGetInteger(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX);
    }

    @Override
    public void frame(Frame frame) {
        long start = System.nanoTime();
        long n = frame.number();
        frames++;
        readBack(n);
        FrameRecord record = records[(int) (n % RECORDS)];
        record.reset(n);
        record.wall = System.currentTimeMillis();
        record.phase = schedule.phase(n);
        record.measured = schedule.measured(n);
        if (lastFrame == n - 1 && lastStart >= 0 && !lastDisturbed) {
            record.cpuSincePrevious = start - lastStart;
        }
        lastStart = start;
        lastFrame = n;
        lastDisturbed = false;

        if (color == null || output.width != frame.width() || output.height != frame.height()) {
            createImages(frame.width(), frame.height(), frame.depthRenderbuffer(), null);
            record.measured = false;
            lastDisturbed = true;
        }
        if (n % schedule.segment() == 0) {
            // The first frame of a turn is never measured, so the calibration's reads cost nothing that counts.
            calibrate();
        }
        record.glOffset = glOffset;
        record.vkTicks0 = vkTicks0;
        record.vkNanos0 = vkNanos0;
        record.vkCalibrated = vkCalibrated;

        int slot = (int) (n % GL_RING);
        timestamp(slot, 0);
        Phase phase = record.phase;
        if (phase != Phase.OFF) {
            // Only a whole round trip carries this frame's stamp: the copies write it, the stand-in carries it back.
            // A checked frame also collects the errors the handoff's own calls raise, which no stamp would show
            // (a refused depth copy, say); errors queued before them are someone else's and are dropped.
            boolean check = phase.copies() && phase.evaluates() && (checkDue || n - lastCheck >= checkEvery);
            if (check) {
                Gl.clearErrors();
            }
            if (phase.copies()) {
                copyIn(frame, n);
            }
            timestamp(slot, 1);
            if (phase.roundTrip()) {
                roundTrip(record, phase.evaluates());
            }
            timestamp(slot, 2);
            if (check) {
                glErrors(n, "copies in and round trip");
                check(n);
                record.measured = false;
                lastDisturbed = true;
            }
            if (phase.copies() && present) {
                copyOut(frame);
            }
            timestamp(slot, 3);
            if (check) {
                glErrors(n, "copy out");
            }
        }
        record.cpuHandoff = System.nanoTime() - start;
        if (reportEvery > 0 && frames % reportEvery == 0) {
            report("after " + frames + " frames");
            lastDisturbed = true;
        }
    }

    @Override
    public boolean evaluates(Host host) {
        return host.option("evaluate", "standin").equals("dlss");
    }

    /**
     * The scaler's picture, evaluated by DLSS into the output's framebuffer in place of the scaler's temporal and
     * scale passes: its color, depth and motion copied into the shared images at the size it was drawn, the round
     * trip with this frame's offset, and DLSS's output copied into the game's framebuffer. No stamp is carried,
     * since the output's first pixel is the picture's; the picture is the check. Every evaluation is timed as a
     * whole handoff, under {@code FULL}.
     */
    @Override
    public boolean evaluate(Evaluation e) {
        if (ngx == null) {
            return false;
        }
        long start = System.nanoTime();
        long n = e.number();
        frames++;
        if (!forScaler) {
            forScaler = true;
            standIn.stamp(false);
            statistics.computeIfAbsent(Phase.FULL, phase -> new PhaseStatistics());
            event(String.format(Locale.ROOT, "Evaluating the scaler's picture: drawn %dx%d for %dx%d, depth %s, offsets "
                    + "taken as x %+.0f, y %+.0f", e.renderWidth(), e.renderHeight(), e.outputWidth(), e.outputHeight(),
                e.depthInverted() ? "reversed" : "not reversed", jitterSignX, jitterSignY));
        }
        readBack(n);
        FrameRecord record = records[(int) (n % RECORDS)];
        record.reset(n);
        record.wall = System.currentTimeMillis();
        record.phase = Phase.FULL;
        record.measured = true;
        if (lastFrame == n - 1 && lastStart >= 0 && !lastDisturbed) {
            record.cpuSincePrevious = start - lastStart;
        }
        lastStart = start;
        lastFrame = n;
        lastDisturbed = false;

        if (color == null || output.width != e.outputWidth() || output.height != e.outputHeight()
                || color.width != e.renderWidth() || color.height != e.renderHeight() || depthInvertedMade != e.depthInverted()) {
            depthInvertedMade = e.depthInverted();
            createImages(e.outputWidth(), e.outputHeight(), e.depthRenderbuffer(),
                new RenderSize(e.renderWidth(), e.renderHeight()));
            record.measured = false;
            lastDisturbed = true;
        }
        if (n % schedule.segment() == 0) {
            calibrate();
        }
        record.glOffset = glOffset;
        record.vkTicks0 = vkTicks0;
        record.vkNanos0 = vkNanos0;
        record.vkCalibrated = vkCalibrated;

        int slot = (int) (n % GL_RING);
        timestamp(slot, 0);
        copyInScaled(e);
        timestamp(slot, 1);
        standIn.inputs(e.jitterX() * jitterSignX, e.jitterY() * jitterSignY, e.reset());
        roundTrip(record, true);
        timestamp(slot, 2);
        copyOutScaled(e);
        timestamp(slot, 3);
        record.cpuHandoff = System.nanoTime() - start;
        if (reportEvery > 0 && frames % reportEvery == 0) {
            report("after " + frames + " frames");
            lastDisturbed = true;
        }
        return true;
    }

    /** OpenGL fills the inputs from the scaler's drawn picture, its depth and its motion, at the size it was drawn. */
    private void copyInScaled(Evaluation e) {
        // The motion pass wrote its target with image stores, which a copy reads only after a barrier.
        org.lwjgl.opengl.GL42.glMemoryBarrier(org.lwjgl.opengl.GL42.GL_ALL_BARRIER_BITS);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        int width = e.renderWidth();
        int height = e.renderHeight();
        GL45.glBlitNamedFramebuffer(e.framebuffer(), color.glFramebuffer, 0, 0, width, height, 0, 0, width, height,
            GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        if (depthFilled) {
            GL45.glBlitNamedFramebuffer(e.framebuffer(), depth.glFramebuffer, 0, 0, width, height, 0, 0, width, height,
                GL11.GL_DEPTH_BUFFER_BIT | (depth.stencil ? GL11.GL_STENCIL_BUFFER_BIT : 0), GL11.GL_NEAREST);
        }
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
        org.lwjgl.opengl.GL43.glCopyImageSubData(e.motionTexture(), GL11.GL_TEXTURE_2D, 0, 0, 0, 0, motion.glTexture,
            GL11.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
    }

    /** DLSS's output into the game's framebuffer, at the output's size. */
    private void copyOutScaled(Evaluation e) {
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL45.glBlitNamedFramebuffer(output.glFramebuffer, e.output(), 0, 0, e.outputWidth(), e.outputHeight(), 0, 0,
            e.outputWidth(), e.outputHeight(), GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    /** Logs the errors raised since the last look, on a checked frame. */
    private void glErrors(long n, String where) {
        StringBuilder errors = new StringBuilder();
        int error;
        for (int i = 0; i < 16 && (error = GL11.glGetError()) != GL11.GL_NO_ERROR; i++) {
            errors.append(errors.isEmpty() ? "0x" : ", 0x").append(Integer.toHexString(error));
        }
        if (!errors.isEmpty()) {
            glErrorFrames++;
            event("OpenGL raised " + errors + " in the handoff's " + where + " at frame " + n);
        }
    }

    private void timestamp(int slot, int index) {
        GL33.glQueryCounter(glQueries[slot][index], GL33.GL_TIMESTAMP);
        glIssued[slot][index] = true;
    }

    /** OpenGL fills the inputs from the game's frame; motion is cleared, since no motion vectors exist yet. */
    private void copyIn(Frame frame, long n) {
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL45.glBlitNamedFramebuffer(frame.framebuffer(), color.glFramebuffer, 0, 0, frame.width(), frame.height(), 0, 0,
            color.width, color.height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        if (depthFilled) {
            GL45.glBlitNamedFramebuffer(frame.framebuffer(), depth.glFramebuffer, 0, 0, frame.width(), frame.height(), 0,
                0, depth.width, depth.height, GL11.GL_DEPTH_BUFFER_BIT | (depth.stencil ? GL11.GL_STENCIL_BUFFER_BIT : 0),
                GL11.GL_NEAREST);
        }
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
        GL44.glClearTexImage(motion.glTexture, 0, GL30.GL_RG, GL11.GL_FLOAT, zero);
        stamp.put(0, n % STAMP_PERIOD).put(1, 1);
        GL44.glClearTexSubImage(motion.glTexture, 0, 0, 0, 0, 1, 1, 1, GL30.GL_RG, GL11.GL_FLOAT, stamp);
    }

    /** OpenGL signals, Vulkan evaluates (or only takes and gives back the images), OpenGL waits. */
    private void roundTrip(FrameRecord record, boolean evaluates) {
        int vkSlot = (int) (roundTrips++ % StandIn.RING);
        record.vkSlot = vkSlot;
        long t0 = System.nanoTime();
        record.cpuFence = standIn.reclaim(vkSlot);
        long t1 = System.nanoTime();
        long signal = ++timeline;
        long wait = ++timeline;
        if (semaphores.timeline) {
            EXTSemaphore.glSemaphoreParameterui64EXT(semaphores.glToVulkan, NVTimelineSemaphore.GL_TIMELINE_SEMAPHORE_VALUE_NV,
                signal);
        }
        EXTSemaphore.glSignalSemaphoreEXT(semaphores.glToVulkan, noBuffers, textures, signalLayouts);
        long t2 = System.nanoTime();
        standIn.submit(vkSlot, evaluates, signal, wait);
        long t3 = System.nanoTime();
        if (unproven > 0) {
            prove(vkSlot, signal);
        }
        if (semaphores.timeline) {
            EXTSemaphore.glSemaphoreParameterui64EXT(semaphores.glToOpenGl, NVTimelineSemaphore.GL_TIMELINE_SEMAPHORE_VALUE_NV,
                wait);
        }
        EXTSemaphore.glWaitSemaphoreEXT(semaphores.glToOpenGl, noBuffers, textures, waitLayouts);
        long t4 = System.nanoTime();
        record.cpuSubmit = t3 - t2;
        record.cpuGl = (t2 - t1) + (t4 - t3);
        record.cpuRoundTrip = t4 - t0;
    }

    /**
     * The first round trips are proved before OpenGL waits on one: Vulkan's batch must finish, which it can only
     * once OpenGL's signal has reached it. A signal that never arrives would leave Vulkan's queue waiting and then
     * OpenGL's, the game's frame with it; instead the batch is let run out from the client and the handoff stops.
     * These frames are the first of a turn, which are never measured.
     */
    private void prove(int vkSlot, long signal) {
        long start = System.nanoTime();
        boolean arrived = standIn.finished(vkSlot, PROOF_TIMEOUT);
        if (!arrived) {
            if (semaphores.timeline) {
                standIn.signalFromClient(signal);
                standIn.finished(vkSlot, PROOF_TIMEOUT);
            }
            throw new IllegalStateException("OpenGL's signal did not reach Vulkan within " + PROOF_TIMEOUT / 1_000_000
                + " ms on round trip " + roundTrips + "; OpenGL is not made to wait, and the handoff stops.");
        }
        unproven--;
        event(String.format(Locale.ROOT, "Round trip %d proved: Vulkan's batch finished %.2f ms after submission",
            roundTrips, (System.nanoTime() - start) / 1e6));
    }

    private void copyOut(Frame frame) {
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL45.glBlitNamedFramebuffer(output.glFramebuffer, frame.framebuffer(), 0, 0, output.width, output.height, 0, 0,
            frame.width(), frame.height(), GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    /**
     * Reads the output's first texel, where the stand-in copied this frame's stamp, and compares it: proof that
     * what OpenGL got back was evaluated from this frame's inputs, through handles still alive. Waits for the GPU.
     */
    private void check(long n) {
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        long start = System.nanoTime();
        // The stamp is copied on the GPU into a texel OpenGL owns, and only that is read back: an image the GPU reads
        // every frame is never read by the client, so the driver has no reason to move it out of video memory.
        org.lwjgl.opengl.GL43.glCopyImageSubData(output.glTexture, GL11.GL_TEXTURE_2D, 0, 0, 0, 0, stampTexel,
            GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 1, 1, 1);
        GL45.glGetTextureSubImage(stampTexel, 0, 0, 0, 0, 1, 1, 1, GL30.GL_RG, GL11.GL_FLOAT, readback);
        long waited = System.nanoTime() - start;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
        GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
        GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
        float expected = n % STAMP_PERIOD;
        boolean passed = Math.abs(readback.get(0) - expected) < 0.5f && Math.abs(readback.get(1) - 1) < 0.01f;
        if (passed) {
            checksPassed++;
        } else {
            checksFailed++;
        }
        if (!passed || checkDue) {
            event(String.format(Locale.ROOT, "Round trip check at frame %d %s: stamp %.1f, read %.1f/%.2f (%.2f ms waiting)",
                n, passed ? "passed" : "FAILED", expected, readback.get(0), readback.get(1), waited / 1e6));
        }
        checkDue = false;
        lastCheck = n;
    }

    /**
     * Makes the four images for an output, after releasing any old ones.
     *
     * @param render the render size the scaler drew at, or null for the measurement's own fraction of the output
     */
    private void createImages(int outputWidth, int outputHeight, int depthRenderbuffer, RenderSize render) {
        long start = System.nanoTime();
        long availableBefore = availableKiB();
        boolean again = color != null;
        if (again) {
            releaseImages();
        }
        int depthFormat = GL45.glGetNamedRenderbufferParameteri(depthRenderbuffer, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
        int[] depthFormats = depthFormats(depthFormat);
        if (depthFormat == GL11.GL_DEPTH_COMPONENT) {
            // A texture's storage takes a sized format; the unsized one is taken as 24 bits on both sides.
            depthFormat = 0x81A6;
        }
        // Every size is checked before anything is allocated, so a wrong one is refused with its numbers.
        SharedImageSizes sizes = render == null ? SharedImageSizes.of(outputWidth, outputHeight, scale, depthFormats[2])
            : SharedImageSizes.of(outputWidth, outputHeight, render, depthFormats[2]);
        String refusal = sizes.refusal();
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        render = sizes.render();
        int quality = dlssQuality;
        int[] asked = null;
        if (ngx != null) {
            if (forScaler && qualitySetting.isEmpty()) {
                quality = Ngx.qualityFor((double) render.width() / outputWidth);
            }
            // The render sizes DLSS takes for this output and mode: optimal, largest, smallest.
            asked = ngx.optimal(outputWidth, outputHeight, quality);
            if (forScaler && (render.width() < asked[4] || render.height() < asked[5] || render.width() > asked[2]
                    || render.height() > asked[3])) {
                throw new IllegalStateException(String.format(Locale.ROOT, "DLSS in quality mode %s takes a render size "
                        + "from %dx%d to %dx%d for an output of %dx%d, and the scaler draws %s.", Ngx.qualityName(quality),
                    asked[4], asked[5], asked[2], asked[3], outputWidth, outputHeight, render));
            }
            if (forScaler) {
                standIn.feature(quality, Ngx.MOTION_LOW_RESOLUTION | (depthInvertedMade ? Ngx.DEPTH_INVERTED : 0));
            }
        }
        try {
            color = new SharedImage(vulkan, SharedImage.Role.COLOR, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, GL30.GL_RGBA16F,
                render.width(), render.height(), SharedImageSizes.COLOR_TEXEL, false, false);
            try {
                depth = new SharedImage(vulkan, SharedImage.Role.DEPTH, depthFormats[0], depthFormat, render.width(),
                    render.height(), depthFormats[2], true, depthFormats[1] != 0);
                depthFilled = true;
            } catch (IllegalStateException refused) {
                // A finding, not the end of the measurement: the round trip still carries a depth image, unfilled.
                event("The game's depth format 0x" + Integer.toHexString(depthFormat) + " (Vulkan " + depthFormats[0]
                    + ") could not be shared: " + refused.getMessage() + " A 32-bit float depth image is shared instead, "
                    + "which OpenGL cannot copy the game's depth into.");
                depth = new SharedImage(vulkan, SharedImage.Role.DEPTH, VK10.VK_FORMAT_D32_SFLOAT, GL30.GL_DEPTH_COMPONENT32F,
                    render.width(), render.height(), 4, true, false);
                depthFilled = false;
            }
            motion = new SharedImage(vulkan, SharedImage.Role.MOTION, VK10.VK_FORMAT_R16G16_SFLOAT, GL30.GL_RG16F,
                render.width(), render.height(), SharedImageSizes.MOTION_TEXEL, false, false);
            output = new SharedImage(vulkan, SharedImage.Role.OUTPUT, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, GL30.GL_RGBA16F,
                outputWidth, outputHeight, SharedImageSizes.OUTPUT_TEXEL, false, false);
        } catch (RuntimeException e) {
            releaseImages();
            throw e;
        }
        textures.put(0, color.glTexture).put(1, depth.glTexture).put(2, motion.glTexture).put(3, output.glTexture);
        standIn.bind(color, depth, motion, output);
        if (asked != null) {
            event(String.format(Locale.ROOT, "DLSS feature made for render %s to output %dx%d in quality mode %s; its "
                    + "optimal settings ask for render %dx%d and take %dx%d to %dx%d", render, outputWidth, outputHeight,
                Ngx.qualityName(quality), asked[0], asked[1], asked[4], asked[5], asked[2], asked[3]));
        }
        Gl.check("making the shared images");
        long elapsed = System.nanoTime() - start;
        long availableAfter = availableKiB();
        long total = color.size() + depth.size() + motion.size() + output.size();
        imageReport = String.format(Locale.ROOT, "render %s, output %dx%d; %s; %s; %s; %s; %.2f MiB in all (texels %.2f MiB)",
            render, outputWidth, outputHeight, color.describe(), depth.describe(), motion.describe(), output.describe(),
            total / 1048576.0, sizes.total() / 1048576.0);
        recreations++;
        checkDue = true;
        event(String.format(Locale.ROOT, "Images %s in %.2f ms: %s. GPU memory available %d to %d MiB; Vulkan reports %s of "
                + "device-local memory in use by the process.", again ? "made again" : "made", elapsed / 1e6, imageReport,
            availableBefore / 1024, availableAfter / 1024, mib(vulkan.deviceLocalUsage())));
    }

    /** The Vulkan format matching the game's depth renderbuffer, whether it has stencil, and its texel's bytes. */
    private static int[] depthFormats(int glFormat) {
        return switch (glFormat) {
            case GL30.GL_DEPTH24_STENCIL8 -> new int[]{VK10.VK_FORMAT_D24_UNORM_S8_UINT, 1, 4};
            case GL30.GL_DEPTH32F_STENCIL8 -> new int[]{VK10.VK_FORMAT_D32_SFLOAT_S8_UINT, 1, 8};
            case GL11.GL_DEPTH_COMPONENT /* unsized: taken as 24 bits */, 0x81A6 /* GL_DEPTH_COMPONENT24 */ ->
                new int[]{VK10.VK_FORMAT_X8_D24_UNORM_PACK32, 0, 4};
            case GL30.GL_DEPTH_COMPONENT32F -> new int[]{VK10.VK_FORMAT_D32_SFLOAT, 0, 4};
            case 0x81A5 /* GL_DEPTH_COMPONENT16 */ -> new int[]{VK10.VK_FORMAT_D16_UNORM, 0, 2};
            default -> throw new IllegalStateException("The game's depth buffer has format 0x" + Integer.toHexString(glFormat)
                + ", which the handoff does not map to Vulkan.");
        };
    }

    /** Waits for both APIs, then releases the images. */
    private void releaseImages() {
        GL11.glFinish();
        VK10.vkQueueWaitIdle(vulkan.queue);
        for (SharedImage image : new SharedImage[]{color, depth, motion, output}) {
            if (image != null) {
                image.close();
            }
        }
        color = depth = motion = output = null;
    }

    /** Puts OpenGL's and Vulkan's GPU clocks on the client's {@link System#nanoTime}. */
    private void calibrate() {
        long before = System.nanoTime();
        long gl = GL32.glGetInteger64(GL33.GL_TIMESTAMP);
        long after = System.nanoTime();
        glOffset = gl - (before + after) / 2;
        long[] pair = vulkan.calibrate();
        if (pair != null) {
            long counter = Win32.performanceCounter();
            long nanos = System.nanoTime();
            // The device's tick and the performance counter were read together; nanoTime is taken against the counter
            // a moment later, and only the short span between is converted, so nothing large is rounded.
            vkTicks0 = pair[0];
            vkNanos0 = nanos - Math.round((counter - pair[1]) * 1e9 / performanceFrequency);
            vkCalibrated = true;
            worstDeviation = Math.max(worstDeviation, pair[2]);
        }
    }

    /** Reads what the GPU wrote for the frame issued {@link #GL_RING} frames ago, if it is there. */
    private void readBack(long n) {
        long old = n - GL_RING;
        if (old < 0) {
            return;
        }
        FrameRecord record = records[(int) (old % RECORDS)];
        int slot = (int) (old % GL_RING);
        if (record.frame != old) {
            clearIssued(slot);
            return;
        }
        boolean complete = true;
        for (int i = 0; i < 4; i++) {
            if (!glIssued[slot][i]) {
                continue;
            }
            if (GL15.glGetQueryObjecti(glQueries[slot][i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                complete = false;
                break;
            }
            record.gl[i] = GL33.glGetQueryObjectui64(glQueries[slot][i], GL15.GL_QUERY_RESULT);
        }
        boolean[] issued = glIssued[slot].clone();
        clearIssued(slot);
        if (!complete) {
            droppedGl++;
            previousG0Frame = -1;
            return;
        }
        long[] vk = null;
        if (record.vkSlot >= 0) {
            long[] ticks = new long[4];
            if (standIn.timestamps(record.vkSlot, ticks)) {
                vk = ticks;
            } else {
                droppedVk++;
            }
        }
        finish(record, issued, vk);
    }

    private void clearIssued(int slot) {
        java.util.Arrays.fill(glIssued[slot], false);
    }

    /** Turns a frame's timestamps into measurements, and counts them where the frame is measured. */
    private void finish(FrameRecord record, boolean[] issued, long[] vk) {
        // A frame's GPU interval counts only after a measured frame of the same part, so a check's stall is not in it.
        if (issued[0] && previousG0Frame == record.frame - 1 && previousG0Phase == record.phase && previousG0Measured) {
            record.gpuSincePrevious = record.gl[0] - previousG0;
        }
        previousG0 = issued[0] ? record.gl[0] : -1;
        previousG0Frame = issued[0] ? record.frame : -1;
        previousG0Phase = record.phase;
        previousG0Measured = record.measured;
        if (issued[3]) {
            record.copyIn = record.gl[1] - record.gl[0];
            record.roundTrip = record.gl[2] - record.gl[1];
            record.copyOut = record.gl[3] - record.gl[2];
            record.total = record.gl[3] - record.gl[0];
        }
        if (vk != null) {
            record.vkWork = Math.round((vk[1] - vk[0]) * vulkan.timestampPeriod);
            if (vk[2] >= 0 && vk[3] >= 0) {
                record.ngxWork = Math.round((vk[3] - vk[2]) * vulkan.timestampPeriod);
            }
            if (record.vkCalibrated) {
                long vkStart = record.vkNanos0 + Math.round((vk[0] - record.vkTicks0) * vulkan.timestampPeriod);
                long vkEnd = record.vkNanos0 + Math.round((vk[1] - record.vkTicks0) * vulkan.timestampPeriod);
                long signalled = record.gl[1] - record.glOffset;
                long resumed = record.gl[2] - record.glOffset;
                record.inbound = vkStart - signalled;
                record.outbound = resumed - vkEnd;
                record.hasClocks = true;
            }
        }
        if (record.measured) {
            statistics.get(record.phase).add(record);
            try {
                csv.write(record.csv());
                csv.newLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void worldChanged() {
        worldChanges++;
        checkDue = true;
        if (standIn != null) {
            // DLSS's history belongs to the world it was built in.
            standIn.resetHistory();
        }
        if (recreateOnWorldChange && color != null) {
            long start = System.nanoTime();
            releaseImages();
            event(String.format(Locale.ROOT, "World changed (%d): images released in %.2f ms, made again on the next frame",
                worldChanges, (System.nanoTime() - start) / 1e6));
        } else {
            event("World changed (" + worldChanges + "): images kept; the next round trip is checked");
        }
        lastDisturbed = true;
        report("at world change " + worldChanges);
    }

    private void event(String message) {
        events.add(message);
        host.info("Handoff: " + message);
    }

    /** Logs the statistics so far and writes them to the run's summary file. */
    private void report(String when) {
        List<String> lines = new ArrayList<>();
        lines.add("Handoff measurement, run " + run + ", " + when + ": " + frames + " frames");
        lines.add("  device: " + vulkan.chosen.describe());
        lines.add("  evaluation: " + evaluation);
        lines.add(String.format(Locale.ROOT, "  semaphores %s, queue family %d, render scale %.4f, output %s",
            semaphores.timeline ? "timeline" : "binary", vulkan.queueFamily, scale, present ? "presented" : "not presented"));
        lines.add("  images: " + imageReport);
        lines.add(String.format(Locale.ROOT, "  GPU memory available: %d MiB at open, %d MiB with the device, %d MiB now; "
                + "Vulkan device-local use %s", availableAtOpenKiB / 1024, availableWithDeviceKiB / 1024, availableKiB() / 1024,
            mib(vulkan.deviceLocalUsage())));
        lines.add(String.format(Locale.ROOT, "  checks passed %d, failed %d; checked frames with OpenGL errors %d; images "
                + "made %d times; world changes %d; OpenGL results dropped %d, Vulkan %d; validation messages %d; worst "
                + "calibration deviation %.1f us", checksPassed, checksFailed, glErrorFrames, recreations, worldChanges,
            droppedGl, droppedVk, vulkan.validationMessages.get(), worstDeviation / 1e3));
        for (Map.Entry<Phase, PhaseStatistics> entry : statistics.entrySet()) {
            entry.getValue().report(entry.getKey(), lines);
        }
        lines.add("  events:");
        events.forEach(event -> lines.add("    " + event));
        host.info(String.join(System.lineSeparator(), lines));
        try {
            csv.flush();
            Files.write(host.outputDirectory().resolve("summary-" + run + ".txt"), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            host.warn("The handoff's summary could not be written: " + e);
        }
    }

    private static String mib(long bytes) {
        return bytes < 0 ? "not reported" : String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }

    @Override
    public void close() {
        try {
            if (vulkan != null && run != null && csv != null) {
                report("at close");
            }
            if (color != null) {
                releaseImages();
            }
            if (standIn != null) {
                standIn.close();
            }
            if (ngx != null) {
                // After the queue is idle and before the device goes: NGX releases its features on the device.
                try {
                    ngx.close();
                } catch (RuntimeException e) {
                    host.warn("NGX did not shut down cleanly: " + e.getMessage());
                }
            }
            if (semaphores != null) {
                semaphores.close();
            }
            if (vulkan != null) {
                vulkan.close();
            }
            for (int[] row : glQueries) {
                if (row[0] != 0) {
                    GL15.glDeleteQueries(row);
                }
            }
            if (stampTexel != 0) {
                GL11.glDeleteTextures(stampTexel);
                stampTexel = 0;
            }
            for (java.nio.Buffer buffer : new java.nio.Buffer[]{noBuffers, textures, signalLayouts, waitLayouts, zero, stamp,
                readback}) {
                if (buffer != null) {
                    memFree(buffer);
                }
            }
            if (csv != null) {
                csv.close();
            }
        } catch (IOException e) {
            host.warn("The handoff's frame file could not be closed: " + e);
        } finally {
            standIn = null;
            ngx = null;
            semaphores = null;
            vulkan = null;
            noBuffers = textures = signalLayouts = waitLayouts = null;
            zero = stamp = readback = null;
            csv = null;
        }
    }

    /** What one frame recorded, until its timestamps are read. Times in nanoseconds; -1 where not measured. */
    private static final class FrameRecord {
        static final String HEADER = "frame,phase,cpu_since_previous,gpu_since_previous,copy_in,round_trip,copy_out,total,"
            + "vk_work,inbound,outbound,cpu_handoff,cpu_round_trip,cpu_submit,cpu_gl,cpu_fence,wall_ms,ngx_work";

        long frame = -1;
        /** The wall clock at the frame's start, to match it with a sampler of the GPU's clocks and power state. */
        long wall;
        Phase phase;
        boolean measured;
        int vkSlot;
        final long[] gl = new long[4];
        long glOffset;
        long vkTicks0;
        long vkNanos0;
        boolean vkCalibrated;
        boolean hasClocks;
        long cpuSincePrevious;
        long gpuSincePrevious;
        long copyIn;
        long roundTrip;
        long copyOut;
        long total;
        long vkWork;
        /** NGX's own commands within the Vulkan batch, where DLSS evaluated. */
        long ngxWork;
        long inbound;
        long outbound;
        long cpuHandoff;
        long cpuRoundTrip;
        long cpuSubmit;
        long cpuGl;
        long cpuFence;

        void reset(long frame) {
            this.frame = frame;
            vkSlot = -1;
            hasClocks = false;
            cpuSincePrevious = gpuSincePrevious = copyIn = roundTrip = copyOut = total = vkWork = ngxWork = inbound = outbound
                = -1;
            cpuHandoff = cpuRoundTrip = cpuSubmit = cpuGl = cpuFence = -1;
        }

        String csv() {
            return frame + "," + phase + "," + cell(cpuSincePrevious) + "," + cell(gpuSincePrevious) + "," + cell(copyIn) + ","
                + cell(roundTrip) + "," + cell(copyOut) + "," + cell(total) + "," + cell(vkWork) + ","
                + (hasClocks ? inbound : "") + "," + (hasClocks ? outbound : "") + "," + cell(cpuHandoff) + ","
                + cell(cpuRoundTrip) + "," + cell(cpuSubmit) + "," + cell(cpuGl) + "," + cell(cpuFence) + "," + wall + ","
                + cell(ngxWork);
        }

        private static String cell(long value) {
            return value < 0 ? "" : Long.toString(value);
        }
    }

    /** One part's measurements over the run. */
    private static final class PhaseStatistics {
        final Samples cpuSincePrevious = new Samples();
        final Samples gpuSincePrevious = new Samples();
        final Samples copyIn = new Samples();
        final Samples roundTrip = new Samples();
        final Samples copyOut = new Samples();
        final Samples total = new Samples();
        final Samples vkWork = new Samples();
        final Samples ngxWork = new Samples();
        final Samples inbound = new Samples();
        final Samples outbound = new Samples();
        final Samples cpuHandoff = new Samples();
        final Samples cpuRoundTrip = new Samples();
        final Samples cpuSubmit = new Samples();
        final Samples cpuFence = new Samples();

        void add(FrameRecord record) {
            addIf(cpuSincePrevious, record.cpuSincePrevious);
            addIf(gpuSincePrevious, record.gpuSincePrevious);
            if (record.phase != Phase.OFF) {
                addIf(copyIn, record.copyIn);
                addIf(roundTrip, record.roundTrip);
                addIf(copyOut, record.copyOut);
                addIf(total, record.total);
            }
            addIf(vkWork, record.vkWork);
            addIf(ngxWork, record.ngxWork);
            if (record.hasClocks) {
                inbound.add(record.inbound);
                outbound.add(record.outbound);
            }
            addIf(cpuHandoff, record.cpuHandoff);
            addIf(cpuRoundTrip, record.cpuRoundTrip);
            addIf(cpuSubmit, record.cpuSubmit);
            addIf(cpuFence, record.cpuFence);
        }

        private static void addIf(Samples samples, long value) {
            if (value >= 0) {
                samples.add(value);
            }
        }

        void report(Phase phase, List<String> lines) {
            lines.add("  " + phase + ":");
            line(lines, "client frame interval", cpuSincePrevious);
            line(lines, "GPU frame interval", gpuSincePrevious);
            line(lines, "GPU copies in", copyIn);
            line(lines, "GPU round trip (signal to resume)", roundTrip);
            line(lines, "GPU copy out", copyOut);
            line(lines, "GPU handoff in all", total);
            line(lines, "Vulkan batch", vkWork);
            line(lines, "NGX evaluation (within the batch)", ngxWork);
            line(lines, "signal to Vulkan start", inbound);
            line(lines, "Vulkan end to resume", outbound);
            line(lines, "client time in the handoff", cpuHandoff);
            line(lines, "client time in the round trip", cpuRoundTrip);
            line(lines, "client time in vkQueueSubmit", cpuSubmit);
            line(lines, "client time waiting on fences", cpuFence);
        }

        private static void line(List<String> lines, String name, Samples samples) {
            if (samples.count() > 0) {
                lines.add(String.format(Locale.ROOT, "    %-34s %s", name, samples.summary().millis()));
            }
        }
    }
}
