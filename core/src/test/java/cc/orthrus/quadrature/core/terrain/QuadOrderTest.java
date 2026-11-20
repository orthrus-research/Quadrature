package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

import org.junit.jupiter.api.Test;

class QuadOrderTest {

    @Test
    void farthestComesFirst() {
        float[] centers = {1, 0, 0, 3, 0, 0, 2, 0, 0};
        int[] order = new int[3];
        new QuadOrder().sort(centers, 3, 0, 0, 0, order);
        assertArrayEquals(new int[] {1, 2, 0}, order);
    }

    @Test
    void equalDistancesKeepTheMeshedOrder() {
        // Four quads one block from the eye on different axes, and one farther.
        float[] centers = {1, 0, 0, 0, 1, 0, 0, 0, 5, 0, 0, -1, -1, 0, 0};
        int[] order = new int[5];
        new QuadOrder().sort(centers, 5, 0, 0, 0, order);
        assertArrayEquals(new int[] {2, 0, 1, 3, 4}, order);
    }

    @Test
    void matchesAStableSortOfTheSameDistances() {
        // The reference is the library's stable sort of boxed numbers by the same comparison, farthest first.
        Random random = new Random(1_205L);
        QuadOrder sorter = new QuadOrder();
        for (int round = 0; round < 200; round++) {
            int quads = random.nextInt(600);
            float[] centers = new float[quads * 3];
            for (int i = 0; i < centers.length; i++) {
                // Half-block steps, so many quads lie at equal distances, as on a water surface.
                centers[i] = random.nextInt(33) * 0.5f;
            }
            float eyeX = random.nextInt(33) * 0.5f;
            float eyeY = random.nextInt(33) * 0.5f;
            float eyeZ = random.nextInt(33) * 0.5f;
            float[] distance = new float[quads];
            Integer[] expected = new Integer[quads];
            for (int q = 0; q < quads; q++) {
                float dx = centers[q * 3] - eyeX;
                float dy = centers[q * 3 + 1] - eyeY;
                float dz = centers[q * 3 + 2] - eyeZ;
                distance[q] = dx * dx + dy * dy + dz * dz;
                expected[q] = q;
            }
            Arrays.sort(expected, (Comparator<Integer>) (a, b) -> Float.compare(distance[b], distance[a]));
            int[] order = new int[quads];
            sorter.sort(centers, quads, eyeX, eyeY, eyeZ, order);
            assertArrayEquals(Arrays.stream(expected).mapToInt(Integer::intValue).toArray(), order, "round " + round);
        }
    }
}
