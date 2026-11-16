package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class MaterialTableTest {

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
