package cc.orthrus.quadrature.core.terrain;

/**
 * Every rectangle of the atlas a quad has shown, numbered in the order first met: least u, least v,
 * greatest u, greatest v, as the game's own floats. A face record names its rectangle by number, and
 * the numbers are shared by every thread that meshes, so a number once given never changes.
 *
 * <p>Each meshing thread asks through a {@link Cache} of its own, which takes the lock only for a
 * rectangle it has not met; after the first cubes almost every quad is answered without it. The
 * client thread reads the values for the driver with {@link #copy}.
 */
public final class AtlasRectangles {

    private final Object lock = new Object();
    /** Four floats a rectangle. Replaced by a larger copy as it fills, so a reader's array keeps every number it was given. */
    private volatile float[] values = new float[4 * 256];
    private volatile int count;
    private volatile long version;
    /** An open hash over the rectangles under the lock: a rectangle's number and one, or 0 for an empty place. */
    private int[] places = new int[1024];

    /** The number a rectangle goes by, given the first time any thread meets it. Any thread. */
    public int number(float minU, float minV, float maxU, float maxV) {
        synchronized (lock) {
            float[] held = values;
            int mask = places.length - 1;
            int place = hash(minU, minV, maxU, maxV) & mask;
            while (places[place] != 0) {
                int number = places[place] - 1;
                if (same(held, number, minU, minV, maxU, maxV)) {
                    return number;
                }
                place = place + 1 & mask;
            }
            int number = count;
            if (number * 4 + 4 > held.length) {
                held = java.util.Arrays.copyOf(held, held.length * 2);
            }
            held[number * 4] = minU;
            held[number * 4 + 1] = minV;
            held[number * 4 + 2] = maxU;
            held[number * 4 + 3] = maxV;
            // The values are in place before the count says they exist.
            values = held;
            count = number + 1;
            version++;
            places[place] = number + 1;
            if (count * 2 > places.length) {
                rehash(held);
            }
            return number;
        }
    }

    private void rehash(float[] held) {
        places = new int[places.length * 2];
        int mask = places.length - 1;
        for (int i = 0; i < count; i++) {
            int place = hash(held[i * 4], held[i * 4 + 1], held[i * 4 + 2], held[i * 4 + 3]) & mask;
            while (places[place] != 0) {
                place = place + 1 & mask;
            }
            places[place] = i + 1;
        }
    }

    /**
     * The values, four floats a rectangle. Every number a caller was given before this call is in the
     * array; entries past {@link #count} may be unset. Any thread.
     */
    public float[] values() {
        return values;
    }

    /** How many rectangles have numbers. */
    public int count() {
        return count;
    }

    /** Moves on whenever a rectangle is first met. */
    public long version() {
        return version;
    }

    /** The values and the count at one moment, for handing to the driver. */
    public Copy copy() {
        synchronized (lock) {
            return new Copy(java.util.Arrays.copyOf(values, count * 4), count, version);
        }
    }

    /** What {@link #copy} returns. */
    public static final class Copy {
        public final float[] values;
        public final int count;
        public final long version;

        Copy(float[] values, int count, long version) {
            this.values = values;
            this.count = count;
            this.version = version;
        }
    }

    /** A cache for one thread at a time. */
    public Cache cache() {
        return new Cache();
    }

    /** One thread's numbers for the rectangles it has met; it asks the shared table only for a new one. */
    public final class Cache {
        private int[] places = new int[1024];
        private int held;

        private Cache() {
        }

        public int number(float minU, float minV, float maxU, float maxV) {
            float[] shared = values;
            int mask = places.length - 1;
            int place = hash(minU, minV, maxU, maxV) & mask;
            while (places[place] != 0) {
                int number = places[place] - 1;
                if (same(shared, number, minU, minV, maxU, maxV)) {
                    return number;
                }
                place = place + 1 & mask;
            }
            int number = AtlasRectangles.this.number(minU, minV, maxU, maxV);
            places[place] = number + 1;
            if (++held * 2 > places.length) {
                grow();
            }
            return number;
        }

        private void grow() {
            int[] old = places;
            float[] shared = values;
            places = new int[old.length * 2];
            int mask = places.length - 1;
            for (int entry : old) {
                if (entry == 0) {
                    continue;
                }
                int n = entry - 1;
                int place = hash(shared[n * 4], shared[n * 4 + 1], shared[n * 4 + 2], shared[n * 4 + 3]) & mask;
                while (places[place] != 0) {
                    place = place + 1 & mask;
                }
                places[place] = entry;
            }
        }
    }

    private static boolean same(float[] values, int number, float minU, float minV, float maxU, float maxV) {
        int at = number * 4;
        return values[at] == minU && values[at + 1] == minV && values[at + 2] == maxU && values[at + 3] == maxV;
    }

    private static int hash(float minU, float minV, float maxU, float maxV) {
        int hash = Float.floatToRawIntBits(minU) * 0x9E3779B1 ^ Float.floatToRawIntBits(minV) * 0x85EBCA77
            ^ Float.floatToRawIntBits(maxU) * 0xC2B2AE3D ^ Float.floatToRawIntBits(maxV) * 0x27D4EB2F;
        return hash ^ hash >>> 15;
    }
}
