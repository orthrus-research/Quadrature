package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class MaterialTableTest {

    @Test
    void anImagesLevelsAreBoxMeansDownToOneTexelWhoseColorIsTheImagesMean() {
        assertEquals(5, MaterialTable.IMAGE_LEVELS);
        // Two by two of known texels: the mean, each channel rounded to nearest.
        int[] four = {0xFF000000, 0xFF0000FF, 0xFF00FF00, 0xFFFF0003};
        assertEquals(0xFF404041, MaterialTable.halve(four, 2)[0]);
        // A 16 texel image halved four times keeps its plain mean within the rounding of each step.
        int[] image = new int[MaterialTable.IMAGE_SIDE * MaterialTable.IMAGE_SIDE];
        long red = 0;
        for (int i = 0; i < image.length; i++) {
            int r = (i * 37) & 0xFF;
            image[i] = 0xFF000000 | r << 16 | ((i * 11) & 0xFF) << 8 | (i & 0xFF);
            red += r;
        }
        int[] level = image;
        for (int side = MaterialTable.IMAGE_SIDE; side > 1; side /= 2) {
            level = MaterialTable.halve(level, side);
            assertEquals(side / 2 * (side / 2), level.length);
        }
        long mean = Math.round(red / (double) image.length);
        org.junit.jupiter.api.Assertions.assertTrue(Math.abs((level[0] >> 16 & 0xFF) - mean) <= 2,
            "the one texel's red " + (level[0] >> 16 & 0xFF) + " against the mean " + mean);
        assertEquals(0xFF, level[0] >>> 24);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> MaterialTable.halve(new int[9], 3));
    }

    @Test
    void aMaterialHasThreeWordsAndItsColorsAreWhatTheyWereWithTwo() {
        assertEquals(3, MaterialTable.ENTRY_WORDS);
        ByteBuffer entries = ByteBuffer.allocate(4 * MaterialTable.ENTRY_WORDS * 4).order(ByteOrder.nativeOrder());
        int grassTop = MaterialTable.entry(0x8DB360, MaterialTable.GRASS);
        int grassSide = MaterialTable.entry(0x866043, MaterialTable.PLAIN);
        int water = MaterialTable.entry(0x2F44F4, MaterialTable.WATER_TINT, 45);
        MaterialTable.put(entries, 1, grassTop, grassSide, 2);
        MaterialTable.put(entries, 3, water, water, 9 | 3 << 12);
        // The top and side words are the entries as they were packed before there was a third.
        assertEquals(grassTop, entries.getInt(12));
        assertEquals(grassSide, entries.getInt(16));
        assertEquals(0x008DB360 | MaterialTable.GRASS << 24 | MaterialTable.FULL_COVER << 26, entries.getInt(12));
        assertEquals(2, entries.getInt(20));
        assertEquals(water, entries.getInt(36));
        assertEquals(water, entries.getInt(40));
        assertEquals(45, MaterialTable.cover(entries.getInt(36)));
        assertEquals(9 | 3 << 12, entries.getInt(44));
        // Materials not written are untouched, and nothing is written past a material's own words.
        for (int at : new int[] {0, 4, 8, 24, 28, 32}) {
            assertEquals(0, entries.getInt(at), "word at " + at);
        }
        // A state's number is 16 bits.
        MaterialTable.put(entries, 0, 0, 0, 0x12345);
        assertEquals(0x2345, entries.getInt(8));
        assertEquals(0, entries.position(), "Written by absolute position");
    }
}
