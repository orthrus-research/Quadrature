package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.terrain.SurfaceBand;

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSource;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The surface heights a cube source gives for a chunk column, where it gives any. The methods are
 * looked for once, by name, so that a cube source without them still serves: then no column has a band
 * from here and coverage is found by scanning.
 *
 * <p>Asking can be dear: where loaded cubes cannot answer for a block column the source works the
 * answer out from the generator, tens of milliseconds for a chunk column the first time. So a column is
 * asked about only when the caller says its ground lies among cubes that are held, a bounded number a
 * pass, and the answer is kept until the source says the column changed.
 */
final class SurfaceHeights {

    private static final int UNKNOWN = Integer.MIN_VALUE;

    private final ICubeSource source;
    private final MethodHandle heights;
    private final MethodHandle version;
    private final MethodHandle bounded;
    private final MethodHandle content;
    /** What the cube source's content call answers for a cube that holds only air. */
    private static final int CONTENT_EMPTY = 1;
    private final int[] ground = new int[256];
    private final int[] liquid = new int[256];
    private final boolean[] loaded = new boolean[256];
    /** For each column asked about, its band, and the source's version of it then. */
    private final Long2LongOpenHashMap bands = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap versions = new Long2LongOpenHashMap();
    /** Columns a cube changed in or beside the band kept for them: their answers are checked before they are used again. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet stale = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private long versionsAsked;
    /** The columns asked about since the coverage map last took their bands. */
    private final it.unimi.dsi.fastutil.longs.LongArrayList answered = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private long asked;
    private long nanos;
    private long mostNanos;

    SurfaceHeights(ICubeSource source) {
        this.source = source;
        MethodHandle found = null;
        MethodHandle foundVersion = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            found = lookup.findVirtual(ICubeSource.class, "surfaceHeights",
                MethodType.methodType(int.class, int.class, int.class, int[].class, int[].class, boolean[].class));
            foundVersion = lookup.findVirtual(ICubeSource.class, "surfaceVersion",
                MethodType.methodType(long.class, int.class, int.class));
        } catch (ReflectiveOperationException absent) {
            found = null;
        }
        MethodHandle foundBounded = null;
        MethodHandle foundContent = null;
        try {
            // A later form takes a sixth argument that forbids working a height out from the generator.
            foundBounded = MethodHandles.publicLookup().findVirtual(ICubeSource.class, "surfaceHeights",
                MethodType.methodType(int.class, int.class, int.class, int[].class, int[].class, boolean[].class,
                    boolean.class));
        } catch (ReflectiveOperationException absent) {
            foundBounded = null;
        }
        try {
            foundContent = MethodHandles.publicLookup().findVirtual(ICubeSource.class, "cubeContent",
                MethodType.methodType(int.class, int.class, int.class, int.class));
        } catch (ReflectiveOperationException absent) {
            foundContent = null;
        }
        heights = found;
        version = found == null ? null : foundVersion;
        bounded = found == null ? null : foundBounded;
        content = foundContent;
        bands.defaultReturnValue(SurfaceBand.NONE);
        versions.defaultReturnValue(Long.MIN_VALUE);
        Quadrature.LOGGER.info("The cube source {}, and {}.", heights == null
                ? "gives no surface heights, so coverage is found by scanning" : bounded != null
                ? "gives surface heights without working any out, so coverage uses them for every column held"
                : "gives surface heights, so coverage uses them where ground is among the cubes held",
            content == null ? "does not say whether a cube is empty, so a snapshot is asked"
                : "says whether a cube is empty");
    }

    /** Whether the cube source gives heights at all. */
    boolean present() {
        return heights != null;
    }

    private static long column(int columnX, int columnZ) {
        return (long) columnX << 32 | columnZ & 0xFFFFFFFFL;
    }

    /**
     * The band last learned for a column, or {@link SurfaceBand#NONE}. An answer the source has since
     * changed is dropped.
     */
    long band(int columnX, int columnZ) {
        return bands.get(column(columnX, columnZ));
    }

    /**
     * Says that a cube of a column changed: arrived, left, was meshed anew. Where the cube lies in or
     * beside the band kept for the column, or no band is kept, the answer may no longer hold and the
     * column is asked about again when next it comes up; a change far from the band leaves the answer
     * as it is.
     */
    void touched(int columnX, int cubeY, int columnZ) {
        long key = column(columnX, columnZ);
        if (!versions.containsKey(key)) {
            return;
        }
        long band = bands.get(key);
        if (band == SurfaceBand.NONE || cubeY >= SurfaceBand.lowest(band) - 1 && cubeY <= SurfaceBand.highest(band) + 1) {
            stale.add(key);
        }
    }

    /** Asks the source about a column and keeps the answer. Call only for a column whose ground is among cubes held. */
    void ask(int columnX, int columnZ) {
        long start = System.nanoTime();
        try {
            int fromLoaded = bounded != null ? (int) bounded.invokeExact(source, columnX, columnZ, ground, liquid, loaded, false)
                : (int) heights.invokeExact(source, columnX, columnZ, ground, liquid, loaded);
            long key = column(columnX, columnZ);
            long band = fromLoaded == 0 ? SurfaceBand.NONE : SurfaceBand.of(ground, liquid, loaded, UNKNOWN);
            versions.put(key, (long) version.invokeExact(source, columnX, columnZ));
            stale.remove(key);
            answered.add(key);
            if (band == SurfaceBand.NONE) {
                bands.remove(key);
            } else {
                bands.put(key, band);
            }
        } catch (Throwable e) {
            Quadrature.LOGGER.warn("Surface heights could not be read: {}", e.toString());
        }
        long took = System.nanoTime() - start;
        asked++;
        nanos += took;
        mostNanos = Math.max(mostNanos, took);
    }

    /** The columns asked about since they were last taken, as {@code columnX << 32 | columnZ}: the taker clears it. */
    it.unimi.dsi.fastutil.longs.LongArrayList answered() {
        return answered;
    }

    /** Whether the cube source can be told never to work a height out from the generator. */
    boolean neverPreviews() {
        return bounded != null;
    }

    /** Whether the cube source says in one look-up whether a cube holds anything. */
    boolean tellsContent() {
        return content != null;
    }

    /** Whether a cube holds only air, by the cube source's own account. Only where {@link #tellsContent}. */
    boolean empty(int cubeX, int cubeY, int cubeZ) {
        try {
            return (int) content.invokeExact(source, cubeX, cubeY, cubeZ) == CONTENT_EMPTY;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Whether a column has been asked about and the source's version of it has not moved since. */
    boolean known(int columnX, int columnZ) {
        long key = column(columnX, columnZ);
        if (!versions.containsKey(key)) {
            return false;
        }
        if (!stale.contains(key)) {
            return true;
        }
        // A cube by its band changed. The answer still holds if the source's version of the column has not moved.
        stale.remove(key);
        try {
            versionsAsked++;
            return (long) version.invokeExact(source, columnX, columnZ) == versions.get(key);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Forgets every answer, as when the world is left. */
    void clear() {
        bands.clear();
        versions.clear();
        stale.clear();
        answered.clear();
    }

    String summary() {
        return String.format("surface heights asked %d times, %.3f ms each, %.3f ms most, %d columns kept, a column's version "
                + "asked %d times", asked, asked == 0 ? 0.0 : nanos / 1e6 / asked, mostNanos / 1e6, bands.size(), versionsAsked);
    }
}
