package cc.orthrus.quadrature.core.tier;

import cc.orthrus.quadrature.core.platform.CapabilityReport;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * What the startup benchmark measures and how often (D22), and the key its result is stored under.
 * {@link #VERSION} counts any change to what is measured, how it is scored, or the calibration it is
 * scored against: a stored result of another version is measured again.
 */
public final class TierBenchmark {

    /**
     * The benchmark's version; part of the key a result is stored under. 1, the first calibration; 2, the trace probe
     * on the walk that reads no coarser bits, against a reference scaled to it; 3, nothing timed before the light
     * scene's copy has caught up, and a run whose trace stands far from the other probes chooses nothing; 4, a tier
     * renders below the output only where that costs less than drawing natively; 5, distant terrain counted; 6, its
     * faces at each reach measured at two places.
     */
    public static final int VERSION = 6;

    /** The bandwidth kernel's small working set: past the last-level cache of Turing and Ampere, inside Ada's. */
    public static final long SMALL_BYTES = 8L << 20;
    /** The large working set: past every supported card's cache. */
    public static final long LARGE_BYTES = 256L << 20;
    /** How much the kernel reads between one pair of timer queries, whatever the working set. */
    public static final long BYTES_PER_QUERY = 512L << 20;

    /** Repetitions of each probe at each setting that are timed but not kept, before those that are. */
    public static final int WARM_UP = 2;
    /** Repetitions kept for each probe at each setting. */
    public static final int REPETITIONS = 7;
    /** Repetitions kept of the light view's box drawn whole, which takes longest of the probes. */
    public static final int LIGHT_REPETITIONS = 5;

    /**
     * The fewest primitives the opaque terrain probe must draw for a run to measure the card: the calibration's
     * smallest place drew 270,000. A view whose cubes have not arrived draws a few thousand, and its time is the
     * probe's fixed part alone, which reads the card three times as fast as it is.
     */
    public static final long LEAST_PRIMITIVES = 200_000;

    /**
     * The lowest render scale each tier may use, lowest tier first (ARCHITECTURE 12.1); the probes run at each. A tier
     * draws natively where that costs no more ({@link TierSelection#priced}).
     */
    public static final double[] RENDER_SCALES = {0.67, 0.77, 1.0, 1.0};

    private TierBenchmark() {
    }

    /** The render scale of a tier. */
    public static double renderScale(Tier tier) {
        return RENDER_SCALES[tier.ordinal()];
    }

    /**
     * The GPU as a digest: what the context names it, and the device's identifier where interop reports
     * one. Nothing of the identifier itself is kept.
     *
     * @param deviceUuid the device's identifier, or null where it is not reported
     */
    public static String gpuDigest(String vendor, String renderer, byte[] deviceUuid) {
        return digest(vendor + "\n" + renderer + "\n" + hex(deviceUuid));
    }

    /**
     * The driver as a digest: the context's version string, which names the driver's version, and the
     * driver's identifier where interop reports one.
     */
    public static String driverDigest(String versionString, byte[] driverUuid) {
        return digest(versionString + "\n" + hex(driverUuid));
    }

    /** The key a result is stored under: the GPU, the driver and the benchmark's version. */
    public static String key(String gpuDigest, String driverDigest) {
        return digest(gpuDigest + "\n" + driverDigest + "\n" + VERSION);
    }

    private static String hex(byte[] bytes) {
        return bytes == null ? "-" : HexFormat.of().formatHex(bytes);
    }

    /** The first 8 hexadecimal digits of the text's SHA-256, as device identifiers are kept elsewhere. */
    static String digest(String text) {
        return CapabilityReport.fingerprint(text.getBytes(StandardCharsets.UTF_8)).substring("sha256 ".length());
    }
}
