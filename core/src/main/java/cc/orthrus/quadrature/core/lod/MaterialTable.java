package cc.orthrus.quadrature.core.lod;

import java.nio.ByteBuffer;
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

    /** The side of a material's image as distant terrain keeps it, in texels. */
    public static final int IMAGE_SIDE = 16;

    /**
     * A material's image as distant terrain draws it near the join: {@link #IMAGE_SIDE} texels a
     * side, {@code 0xAARRGGBB}, taken from the nearest texels of the source. Each color is
     * multiplied by {@code by} ({@code 0xFFFFFF} for none), which is where a block's own fixed
     * color goes; a biome's tint is applied when it is drawn.
     *
     * <p>Every texel is made opaque, since distant faces are solid. A clear texel takes the one
     * color that makes the image's plain mean equal {@link #seenFromAfar} of it: the image's
     * smaller copies are plain means, so from afar the image then shows the color the flat color
     * is, and the game's own far leaves have. Filled with black instead, an image a third leaf
     * came out half as bright as its flat color, and crowns near the join were too dark.
     */
    public static int[] image(int[] pixels, int width, int height, int by) {
        int[] image = new int[IMAGE_SIDE * IMAGE_SIDE];
        if (width <= 0 || height <= 0 || pixels.length < width * height) {
            java.util.Arrays.fill(image, 0xFF808080);
            return image;
        }
        long red = 0;
        long green = 0;
        long blue = 0;
        int clear = 0;
        for (int y = 0; y < IMAGE_SIDE; y++) {
            for (int x = 0; x < IMAGE_SIDE; x++) {
                int pixel = pixels[(y * height / IMAGE_SIDE) * width + x * width / IMAGE_SIDE];
                if (pixel >>> 24 == 0) {
                    image[y * IMAGE_SIDE + x] = 0;
                    clear++;
                    continue;
                }
                int color = multiply(pixel, by);
                image[y * IMAGE_SIDE + x] = 0xFF000000 | color;
                red += color >> 16 & 0xFF;
                green += color >> 8 & 0xFF;
                blue += color & 0xFF;
            }
        }
        if (clear == 0) {
            return image;
        }
        int far = seenFromAfar(image);
        int fill = 0xFF000000 | filling(far >> 16 & 0xFF, red, clear) << 16 | filling(far >> 8 & 0xFF, green, clear) << 8
            | filling(far & 0xFF, blue, clear);
        for (int i = 0; i < image.length; i++) {
            if (image[i] == 0) {
                image[i] = fill;
            }
        }
        return image;
    }

    /** How many levels a material's image has: {@link #IMAGE_SIDE} texels a side down to one. */
    public static final int IMAGE_LEVELS = Integer.numberOfTrailingZeros(IMAGE_SIDE) + 1;

    /**
     * The next smaller copy of a square {@code 0xAARRGGBB} image {@code side} texels a side: each
     * texel the mean of the two by two below it, each channel rounded to nearest, as a box filter
     * makes a mip level. The image repeats a block apart, and a power-of-two side halves onto
     * whole texels, so no texel reads across an edge. Written from the CPU when a material's image
     * is uploaded, every level in turn, so that no level is generated over the whole array.
     */
    public static int[] halve(int[] image, int side) {
        if (side < 2 || (side & (side - 1)) != 0 || image.length < side * side) {
            throw new IllegalArgumentException("a square power-of-two image of side 2 or more, not " + side);
        }
        int half = side / 2;
        int[] out = new int[half * half];
        for (int y = 0; y < half; y++) {
            for (int x = 0; x < half; x++) {
                int a = image[(2 * y) * side + 2 * x];
                int b = image[(2 * y) * side + 2 * x + 1];
                int c = image[(2 * y + 1) * side + 2 * x];
                int d = image[(2 * y + 1) * side + 2 * x + 1];
                int texel = 0;
                for (int shift = 0; shift < 32; shift += 8) {
                    int sum = (a >>> shift & 0xFF) + (b >>> shift & 0xFF) + (c >>> shift & 0xFF) + (d >>> shift & 0xFF);
                    texel |= ((sum + 2) >> 2) << shift;
                }
                out[y * half + x] = texel;
            }
        }
        return out;
    }

    /** What the clear texels must hold for the whole image's mean to be {@code mean}. */
    private static int filling(int mean, long opaqueSum, int clear) {
        long value = Math.round((mean * (double) (IMAGE_SIDE * IMAGE_SIDE) - opaqueSum) / clear);
        return (int) Math.max(0, Math.min(255, value));
    }

    /** The most an entry's cover can be: every pixel of the image fully opaque. */
    public static final int FULL_COVER = 63;

    /**
     * How much of an image covers what lies behind it, 0 to {@link #FULL_COVER}: the mean of its
     * pixels' alpha. For water it is how strongly the surface hides its bed; for leaves, the share
     * of the face that is leaf.
     */
    public static int cover(int[] pixels) {
        if (pixels.length == 0) {
            return FULL_COVER;
        }
        long alpha = 0;
        for (int pixel : pixels) {
            alpha += pixel >>> 24;
        }
        return (int) ((alpha * FULL_COVER + 255L * pixels.length / 2) / (255L * pixels.length));
    }

    /**
     * The color an image with clear parts shows from afar, as the game draws it: {@code 0xRRGGBB}.
     * The game's smaller copies of an image average light, not stored values, and count a clear
     * pixel as black; its alpha test then passes what is left. So a far leaf face is solid, and
     * its color is the mean, over every pixel, of the light of the pixels that are not clear. A
     * leaf image a third leaf comes out about two thirds as bright as the leaf itself.
     */
    public static int seenFromAfar(int[] pixels) {
        if (pixels.length == 0) {
            return 0;
        }
        double red = 0;
        double green = 0;
        double blue = 0;
        for (int pixel : pixels) {
            if (pixel >>> 24 == 0) {
                continue;
            }
            red += Math.pow((pixel >> 16 & 0xFF) / 255.0, GAMMA);
            green += Math.pow((pixel >> 8 & 0xFF) / 255.0, GAMMA);
            blue += Math.pow((pixel & 0xFF) / 255.0, GAMMA);
        }
        return stored(red / pixels.length) << 16 | stored(green / pixels.length) << 8 | stored(blue / pixels.length);
    }

    /** The exponent the game takes between a stored value and light when it makes smaller copies. */
    private static final double GAMMA = 2.2;

    private static int stored(double light) {
        return (int) Math.round(Math.pow(light, 1 / GAMMA) * 255);
    }

    /** An opaque color and how it is tinted, as the 32 bits the draw reads. */
    public static int entry(int color, int tint) {
        return entry(color, tint, FULL_COVER);
    }

    /**
     * A color, how it is tinted and how much it covers, as the 32 bits the draw reads: the color in
     * the low 24, the tint in the next 2, the cover in the top 6.
     */
    public static int entry(int color, int tint, int cover) {
        return color & 0xFFFFFF | (tint & 3) << 24 | Math.max(0, Math.min(FULL_COVER, cover)) << 26;
    }

    /**
     * Words a material has in the table the draw reads: {@link #TOP_WORD} and {@link #SIDE_WORD},
     * each an {@link #entry}, and {@link #STATE_WORD}, the number of the block state it stands for.
     */
    public static final int ENTRY_WORDS = 3;
    public static final int TOP_WORD = 0, SIDE_WORD = 1, STATE_WORD = 2;
    /** State numbers there are: a state's number is 16 bits, as near terrain numbers states. */
    public static final int STATE_NUMBERS = 1 << 16;

    /**
     * Writes a material's words at its place in the table, by absolute position. The state number
     * is what a table shared with near terrain is indexed by; nothing of how the material is
     * colored depends on it.
     */
    public static void put(ByteBuffer entries, int index, int top, int side, int state) {
        int at = index * ENTRY_WORDS * 4;
        entries.putInt(at + TOP_WORD * 4, top);
        entries.putInt(at + SIDE_WORD * 4, side);
        entries.putInt(at + STATE_WORD * 4, state & STATE_NUMBERS - 1);
    }

    /** An entry's tint. */
    public static int tint(int entry) {
        return entry >>> 24 & 3;
    }

    /** An entry's cover, 0 to {@link #FULL_COVER}. */
    public static int cover(int entry) {
        return entry >>> 26;
    }
}
