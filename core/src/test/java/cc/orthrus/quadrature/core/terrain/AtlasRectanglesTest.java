package cc.orthrus.quadrature.core.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

class AtlasRectanglesTest {

    @Test
    void aRectangleKeepsTheNumberItWasFirstGiven() {
        AtlasRectangles rectangles = new AtlasRectangles();
        int a = rectangles.number(0f, 0f, 0.5f, 0.5f);
        int b = rectangles.number(0.5f, 0f, 1f, 0.5f);
        assertEquals(0, a);
        assertEquals(1, b);
        assertEquals(a, rectangles.number(0f, 0f, 0.5f, 0.5f));
        assertEquals(2, rectangles.count());
        float[] values = rectangles.values();
        assertEquals(0.5f, values[b * 4]);
        assertEquals(1f, values[b * 4 + 2]);
    }

    @Test
    void cachesOfTwoThreadsShareOneNumbering() {
        AtlasRectangles rectangles = new AtlasRectangles();
        AtlasRectangles.Cache first = rectangles.cache();
        AtlasRectangles.Cache second = rectangles.cache();
        int a = first.number(0.1f, 0.2f, 0.3f, 0.4f);
        int b = second.number(0.5f, 0.6f, 0.7f, 0.8f);
        assertNotEquals(a, b);
        assertEquals(a, second.number(0.1f, 0.2f, 0.3f, 0.4f));
        assertEquals(b, first.number(0.5f, 0.6f, 0.7f, 0.8f));
        assertEquals(2, rectangles.count());
    }

    @Test
    void numbersSurviveTheTableGrowing() {
        AtlasRectangles rectangles = new AtlasRectangles();
        AtlasRectangles.Cache cache = rectangles.cache();
        for (int i = 0; i < 5000; i++) {
            assertEquals(i, cache.number(i, i + 0.25f, i + 0.5f, i + 0.75f));
        }
        for (int i = 0; i < 5000; i++) {
            assertEquals(i, cache.number(i, i + 0.25f, i + 0.5f, i + 0.75f));
            assertEquals(i, rectangles.number(i, i + 0.25f, i + 0.5f, i + 0.75f));
        }
        AtlasRectangles.Copy copy = rectangles.copy();
        assertEquals(5000, copy.count);
        assertEquals(20000, copy.values.length);
        assertEquals(4999.75f, copy.values[4999 * 4 + 3]);
    }

    @Test
    void theVersionMovesOnlyForANewRectangle() {
        AtlasRectangles rectangles = new AtlasRectangles();
        long before = rectangles.version();
        rectangles.number(0f, 0f, 1f, 1f);
        long after = rectangles.version();
        rectangles.number(0f, 0f, 1f, 1f);
        assertNotEquals(before, after);
        assertEquals(after, rectangles.version());
    }

    @Test
    void threadsMeetingTheSameRectanglesAgreeOnTheirNumbers() throws Exception {
        AtlasRectangles rectangles = new AtlasRectangles();
        int threads = 4;
        int each = 3000;
        List<Map<Integer, Integer>> seen = new ArrayList<>();
        List<Thread> started = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            Map<Integer, Integer> numbers = new HashMap<>();
            seen.add(numbers);
            int offset = t * 7;
            Thread thread = new Thread(() -> {
                AtlasRectangles.Cache cache = rectangles.cache();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    int r = (i * 31 + offset) % each;
                    numbers.put(r, cache.number(r, r + 1, r + 2, r + 3));
                }
            });
            started.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : started) {
            thread.join();
        }
        assertEquals(each, rectangles.count());
        float[] values = rectangles.values();
        for (Map<Integer, Integer> numbers : seen) {
            assertEquals(seen.get(0), numbers);
            for (Map.Entry<Integer, Integer> entry : numbers.entrySet()) {
                int n = entry.getValue();
                assertArrayEquals(new float[] {entry.getKey(), entry.getKey() + 1, entry.getKey() + 2, entry.getKey() + 3},
                    java.util.Arrays.copyOfRange(values, n * 4, n * 4 + 4));
            }
        }
    }
}
