package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicIntegerArray;

class TileDrawsTest {

    @Test
    void commandsOfALayerAndPageLieTogetherAndNameFacesAsVertices() {
        TileDraws draws = new TileDraws();
        draws.begin(3);
        int far = draws.record(25_000.25, -1700.5, -12_000.75, 6, 4096, 1f);
        int near = draws.record(-64.0, -1664.0, 128.0, 0, 77, 0.5f);
        draws.add(TileDraws.OPAQUE, 2, 100, 10, far);
        draws.add(TileDraws.WATER, 0, 7, 1, near);
        draws.add(TileDraws.OPAQUE, 0, 5, 2, near);
        draws.add(TileDraws.OPAQUE, 2, 300, 30, near);
        draws.add(TileDraws.CUTOUT, 1, 9, 0, near);
        draws.finish();
        assertEquals(4, draws.commandCount());
        assertEquals(43, draws.faces());
        assertEquals(2, draws.runCount(TileDraws.OPAQUE, 2));
        assertEquals(1, draws.runCount(TileDraws.OPAQUE, 0));
        assertEquals(0, draws.runCount(TileDraws.OPAQUE, 1));
        assertEquals(0, draws.runCount(TileDraws.CUTOUT, 1));
        assertEquals(1, draws.runCount(TileDraws.WATER, 0));
        ByteBuffer commands = draws.commands();
        assertEquals(ByteOrder.nativeOrder(), commands.order());
        assertEquals(4 * TileDraws.COMMAND_BYTES, commands.limit());
        int at = (int) draws.runOffset(TileDraws.OPAQUE, 2);
        // Vertices, one instance, the first vertex, the tile's record; in the order they were added.
        assertEquals(60, commands.getInt(at));
        assertEquals(1, commands.getInt(at + 4));
        assertEquals(600, commands.getInt(at + 8));
        assertEquals(far, commands.getInt(at + 12));
        assertEquals(180, commands.getInt(at + 16));
        assertEquals(1800, commands.getInt(at + 24));
        assertEquals(near, commands.getInt(at + 28));
        at = (int) draws.runOffset(TileDraws.WATER, 0);
        assertEquals(6, commands.getInt(at));
        assertEquals(42, commands.getInt(at + 8));

        ByteBuffer records = draws.records();
        assertEquals(2 * TileDraws.RECORD_BYTES, records.limit());
        assertEquals(25_000.25f, records.getFloat(0));
        assertEquals(-1700.5f, records.getFloat(4));
        assertEquals(-12_000.75f, records.getFloat(8));
        assertEquals(64f, records.getFloat(12));
        assertEquals(4096, records.getInt(16));
        assertEquals(6, records.getInt(20));
        assertEquals(1f, records.getFloat(24));
        assertEquals(1f, records.getFloat(TileDraws.RECORD_BYTES + 12));
        assertEquals(0.5f, records.getFloat(TileDraws.RECORD_BYTES + 24));

        // A list is reused, and grows past its first size.
        draws.begin(1);
        for (int i = 0; i < 1000; i++) {
            draws.add(TileDraws.CUTOUT, 0, i, 1, draws.record(i, 0, 0, 0, 0, 1f));
        }
        draws.finish();
        assertEquals(1000, draws.runCount(TileDraws.CUTOUT, 0));
        assertEquals(999f, draws.records().getFloat(999 * TileDraws.RECORD_BYTES));
        assertEquals(999 * 6, draws.commands().getInt(999 * TileDraws.COMMAND_BYTES + 8));
        assertEquals(TileDraws.OPAQUE, TileDraws.drawLayer(TileMesh.SKIRT));
        assertEquals(TileDraws.WATER, TileDraws.drawLayer(TileMesh.WATER));
    }

    @Test
    void anOriginFarFromZeroKeepsItsPlaceToTheCamera() {
        // The camera is 20 km out; the tile's place is worked out in doubles before it is a float.
        double cameraX = 20_000.37;
        long tile = TileKey.of(2, 78, -3);
        TileDraws draws = new TileDraws();
        draws.begin(1);
        draws.record(TileKey.originX(tile) - cameraX, 0, 0, 2, 0, 1f);
        draws.finish();
        assertEquals((float) (19_968 - 20_000.37), draws.records().getFloat(0));
    }

    @Test
    void groupsThatTurnAwayFromTheEyeAreLeftOut() {
        long tile = TileKey.of(1, 2, -1);
        // Blocks 256 to 384 along x, -128 to 0 along z; faces from level 10 to 90.
        int all = TileCulling.facing(tile, 10, 90, 300, 50, -60);
        assertEquals(63, all);
        int above = TileCulling.facing(tile, 10, 90, 300, 200, -60);
        assertTrue((above & 1 << FaceRecord.UP) != 0);
        assertFalse((above & 1 << FaceRecord.DOWN) != 0);
        int west = TileCulling.facing(tile, 10, 90, 100, 5, -60);
        assertTrue((west & 1 << FaceRecord.WEST) != 0);
        assertFalse((west & 1 << FaceRecord.EAST) != 0);
        assertFalse((west & 1 << FaceRecord.UP) != 0);
        assertTrue((west & 1 << FaceRecord.NORTH) != 0 && (west & 1 << FaceRecord.SOUTH) != 0);
        int southEast = TileCulling.facing(tile, 10, 90, 500, 50, 40);
        assertEquals(1 << FaceRecord.UP | 1 << FaceRecord.DOWN | 1 << FaceRecord.SOUTH | 1 << FaceRecord.EAST,
            southEast);
    }

    @Test
    void aTileIsLeftToNearTerrainOnlyWhenAllOfItLiesInTheBox() {
        long tile = TileKey.of(0, 1, 1);
        // Blocks 64 to 128 on both axes.
        assertTrue(TileCulling.inside(tile, 10, 90, 0, 0, 0, 192, 128, 192));
        assertTrue(TileCulling.inside(tile, 10, 90, 64, 10, 64, 128, 90, 128));
        assertFalse(TileCulling.inside(tile, 10, 90, 65, 0, 0, 192, 128, 192));
        assertFalse(TileCulling.inside(tile, 10, 90, 0, 0, 0, 127, 128, 192));
        // A face below the loaded cubes, or above them, keeps the tile.
        assertFalse(TileCulling.inside(tile, -40, 90, 0, 0, 0, 192, 128, 192));
        assertFalse(TileCulling.inside(tile, 10, 140, 0, 0, 0, 192, 128, 192));
    }

    @Test
    void materialsGetOneIndexEachFromAnyThread() throws InterruptedException {
        MaterialTable<String> table = new MaterialTable<>();
        assertEquals(1, table.size());
        int stone = table.index("stone");
        assertEquals(1, stone);
        assertEquals(stone, table.index("stone"));
        assertEquals("stone", table.key(stone));
        int threads = 8;
        AtomicIntegerArray seen = new AtomicIntegerArray(threads * 200);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int thread = t;
            new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    // Every thread asks for the same 200 materials, each in its own order.
                    int material = (i * 7 + thread * 13) % 200;
                    seen.set(thread * 200 + material, table.index("block " + material));
                }
                done.countDown();
            }).start();
        }
        done.await();
        assertEquals(202, table.size());
        for (int material = 0; material < 200; material++) {
            int index = seen.get(material);
            assertEquals("block " + material, table.key(index));
            for (int t = 1; t < threads; t++) {
                assertEquals(index, seen.get(t * 200 + material));
            }
        }
        // A full table gives 0, which is no material.
        MaterialTable<String> small = new MaterialTable<>(3);
        assertEquals(1, small.index("a"));
        assertEquals(2, small.index("b"));
        assertEquals(0, small.index("c"));
        assertEquals(1, small.index("a"));
    }

    @Test
    void aColorIsTheMeanOfWhatCanBeSeenOfAnImage() {
        // Two opaque pixels and two clear ones: the clear ones do not count.
        assertEquals(0x804020, MaterialTable.average(new int[] {0xFFFF8040, 0xFF010000, 0x00123456, 0x00FFFFFF}));
        // A half-clear pixel counts half.
        assertEquals(0x55AA00, MaterialTable.average(new int[] {0xFF00FF00, 0x80FF0000}) & 0xFFFF00);
        assertEquals(0, MaterialTable.average(new int[] {0x00FFFFFF}));
        // An entry carries the color, the tint and how much the image covers.
        int water = MaterialTable.entry(0x2F44F4, MaterialTable.WATER_TINT, 45);
        assertEquals(0x2F44F4, water & 0xFFFFFF);
        assertEquals(MaterialTable.WATER_TINT, MaterialTable.tint(water));
        assertEquals(45, MaterialTable.cover(water));
        int stone = MaterialTable.entry(0x808080, MaterialTable.PLAIN);
        assertEquals(MaterialTable.FULL_COVER, MaterialTable.cover(stone));
        assertEquals(MaterialTable.PLAIN, MaterialTable.tint(stone));
        assertEquals(MaterialTable.FULL_COVER, MaterialTable.cover(MaterialTable.entry(0, MaterialTable.GRASS, 900)));
        // Cover is the mean alpha: all opaque, all clear, half the pixels clear, a translucent image.
        assertEquals(63, MaterialTable.cover(new int[] {0xFF000000, 0xFFFFFFFF}));
        assertEquals(0, MaterialTable.cover(new int[] {0x00FFFFFF}));
        assertEquals(32, MaterialTable.cover(new int[] {0xFF00FF00, 0x00000000}));
        assertEquals(44, MaterialTable.cover(new int[] {0xB4102030, 0xB4102030}));
        // From afar an image shows the mean light of its pixels, clear ones counted as black.
        assertEquals(0x546240, MaterialTable.seenFromAfar(new int[] {0xFF546240, 0xFF546240}));
        // Half the image clear: half the light, which is 0.5 ^ (1 / 2.2) = 0.73 of the stored value.
        assertEquals(0xBABABA, MaterialTable.seenFromAfar(new int[] {0xFFFFFFFF, 0x00FFFFFF}));
        // A third leaf, like an acacia's: about two thirds as bright.
        int acacia = MaterialTable.seenFromAfar(new int[] {0xFF546240, 0x00000000, 0x00000000});
        assertEquals(0x33, acacia >> 16 & 0xFF);
        assertEquals(0x3B, acacia >> 8 & 0xFF);
        assertEquals(0, MaterialTable.seenFromAfar(new int[] {0x00FFFFFF}));
        assertEquals(0, MaterialTable.seenFromAfar(new int[0]));
        assertEquals(0x7F3F00, MaterialTable.multiply(0xFF7F00, 0x7F7FFF));
        assertEquals(0x02ABCDEF | MaterialTable.FULL_COVER << 26, MaterialTable.entry(0xFFABCDEF, MaterialTable.FOLIAGE));
    }

    @Test
    void aBuiltTileCarriesItsFacesItsTintsAndItsGroups() {
        RunTile tile = Tiles.fill(Tiles.terrain(2));
        TileMesh mesh = new TileMesh();
        new RunMesher().mesh(tile, mesh);
        BuiltTile built = new BuiltTile();
        long key = TileKey.of(3, -5, 9);
        built.set(key, 42, mesh, 1000, 2000);
        assertEquals(key, built.key());
        assertEquals(42, built.generation());
        assertEquals(mesh.faces(), built.faces());
        assertEquals(mesh.bytes() + 4096, built.bytes());
        ByteBuffer data = built.data();
        assertEquals(built.bytes(), data.remaining());
        int checked = 0;
        for (int group = 0; group < TileMesh.GROUPS; group++) {
            assertEquals(mesh.offset(group), built.groupOffset(group));
            assertEquals(mesh.count(group), built.groupCount(group));
            for (int i = 0; i < mesh.count(group); i++) {
                assertEquals(mesh.record(group, i), data.getLong((built.groupOffset(group) + i) * 8));
                checked++;
            }
        }
        assertEquals(mesh.faces(), checked);
        // A column's tint is one byte; the draw reads it from 32-bit words, low byte first.
        int words = mesh.bytes();
        for (int column : new int[] {0, 1, 5, 64 * 17 + 3, 4095}) {
            int x = column % 64;
            int z = column / 64;
            int word = data.order(ByteOrder.LITTLE_ENDIAN).getInt(words + (column >> 2) * 4);
            assertEquals(mesh.tint(x, z) & 0xFF, word >>> 8 * (column & 3) & 0xFF);
        }
        assertEquals(mesh.minimumY(), built.minimumY());
        assertEquals(mesh.maximumY(), built.maximumY());
        assertEquals(1000, built.sampleNanos());
        assertEquals(2000, built.meshNanos());
    }
}
