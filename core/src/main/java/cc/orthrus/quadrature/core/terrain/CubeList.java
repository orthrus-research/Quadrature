package cc.orthrus.quadrature.core.terrain;

import java.util.Arrays;

/**
 * Cubes to draw for a view, as {@link CubeKey}s, in the order they are to be drawn: what a search or
 * a sweep found ({@link Visibility}), or a part of that chosen for a few tiles of the light's depth.
 */
public interface CubeList {

    int count();

    long key(int index);

    /** A list filled by hand, reused frame after frame. */
    final class Keys implements CubeList {

        private long[] keys = new long[256];
        private int count;

        public void clear() {
            count = 0;
        }

        public void add(long key) {
            if (count == keys.length) {
                keys = Arrays.copyOf(keys, count * 2);
            }
            keys[count++] = key;
        }

        @Override
        public int count() {
            return count;
        }

        @Override
        public long key(int index) {
            return keys[index];
        }
    }
}
