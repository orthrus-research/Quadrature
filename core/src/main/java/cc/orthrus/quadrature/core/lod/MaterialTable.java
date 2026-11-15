package cc.orthrus.quadrature.core.lod;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gives each kind of block met in distant terrain a material index, the number a face record
 * carries, and keeps a color for the top and for the sides of each. Index 0 is no material.
 *
 * <p>Indices are handed out on the threads that sample tiles, as they meet blocks; colors are
 * filled in later by whoever can read the blocks' images, which asks {@link #size} and
 * {@link #key} for those it has not seen. Safe for use from many threads.
 *
 * @param <K> what a material is the material of
 */
public final class MaterialTable<K> {

    /** How a material's color is tinted by its column's biome. */
    public static final int PLAIN = 0, GRASS = 1, FOLIAGE = 2, WATER_TINT = 3;

    private final ConcurrentHashMap<K, Integer> indices = new ConcurrentHashMap<>();
    private final ArrayList<K> keys = new ArrayList<>();
    private final int limit;

    public MaterialTable() {
        this(FaceRecord.MATERIALS);
    }

    /** A table of at most {@code limit} indices, 0 included. */
    public MaterialTable(int limit) {
        this.limit = limit;
        keys.add(null);
    }

    /**
     * The material's index, a new one if it has none. Once the table is full every further material
     * gets 0, which draws as nothing known.
     */
    public int index(K material) {
        Integer held = indices.get(material);
        if (held != null) {
            return held;
        }
        synchronized (keys) {
            held = indices.get(material);
            if (held != null) {
                return held;
            }
            if (keys.size() >= limit) {
                return 0;
            }
            int index = keys.size();
            keys.add(material);
            indices.put(material, index);
            return index;
        }
    }

    /** Indices handed out so far, 0 included: every index below this has a key. */
    public int size() {
        synchronized (keys) {
            return keys.size();
        }
    }

    /** The material with an index, or null for 0. */
    public K key(int index) {
        synchronized (keys) {
            return keys.get(index);
        }
    }

    /**
     * The mean color of an image, each pixel weighted by its alpha, so that the clear parts of a
     * leaf or a flower do not darken it: {@code 0xRRGGBB}. Pixels are {@code 0xAARRGGBB}.
     */
    public static int average(int[] pixels) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long weight = 0;
        for (int pixel : pixels) {
            int alpha = pixel >>> 24;
            red += (long) (pixel >> 16 & 0xFF) * alpha;
            green += (long) (pixel >> 8 & 0xFF) * alpha;
            blue += (long) (pixel & 0xFF) * alpha;
            weight += alpha;
        }
        if (weight == 0) {
            return 0;
        }
        return (int) ((red + weight / 2) / weight) << 16 | (int) ((green + weight / 2) / weight) << 8
            | (int) ((blue + weight / 2) / weight);
    }

    /** A color multiplied by another, channel by channel. */
    public static int multiply(int color, int by) {
        int red = (color >> 16 & 0xFF) * (by >> 16 & 0xFF) / 255;
        int green = (color >> 8 & 0xFF) * (by >> 8 & 0xFF) / 255;
        int blue = (color & 0xFF) * (by & 0xFF) / 255;
        return red << 16 | green << 8 | blue;
    }

    /** A color and how it is tinted, as the 32 bits the draw reads. */
    public static int entry(int color, int tint) {
        return color & 0xFFFFFF | tint << 24;
    }
}
