package cc.orthrus.quadrature.core.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.Random;

class RunTileTest {

    @Test
    void runsOfOneMaterialJoinAndAColumnMustReachTheCeiling() {
        RunTile tile = new RunTile(0, 16);
        tile.reset(3, 640, -1280);
        assertEquals(3, tile.level());
        assertEquals(640, tile.originX());
        assertEquals(-1280, tile.originZ());
        tile.beginColumn(0);
        tile.addRun(0, 4, 1, RunTile.OPAQUE);
        tile.addRun(4, 6, 1, RunTile.OPAQUE);
        tile.addRun(6, 9, 2, RunTile.OPAQUE);
        // A run must start where the last ended, and the next column waits for this one to end.
        assertThrows(IllegalStateException.class, () -> tile.addRun(10, 16, 0, RunTile.AIR));
        assertThrows(IllegalStateException.class, () -> tile.beginColumn(0));
        assertThrows(IllegalStateException.class, tile::finish);
        assertThrows(IllegalArgumentException.class, () -> tile.addRun(9, 16, 1 << 13, RunTile.AIR));
        tile.addRun(9, 16, 0, RunTile.AIR);
        for (int c = 1; c < RunTile.COLUMNS; c++) {
            tile.beginColumn(c);
            tile.addRun(0, 16, 0, RunTile.AIR);
        }
        assertThrows(IllegalStateException.class, () -> tile.beginColumn(0));
        assertThrows(IllegalStateException.class, tile::runs);
        tile.finish();
        assertEquals(3, tile.runs(0));
        assertEquals(3 + RunTile.COLUMNS - 1, tile.runs());
        assertThrows(IllegalArgumentException.class, () -> new RunTile(0, 4097));
        assertThrows(IllegalArgumentException.class, () -> new RunTile(5, 5));
    }

    @Test
    void buriedStrataBecomeOneRunAndWhatASpaceTouchesIsKept() {
        // Five strata under ground at 20; one column is a pit down to 8.
        RunTile tile = new RunTile(0, 32);
        tile.reset(0, 0, 0);
        for (int z = -1; z <= RunTile.CELLS; z++) {
            for (int x = -1; x <= RunTile.CELLS; x++) {
                int ground = x == 30 && z == 30 ? 8 : 20;
                tile.beginColumn(0);
                for (int y = 0; y < ground; y += 4) {
                    tile.addRun(y, Math.min(y + 4, ground), 1 + y / 4, RunTile.OPAQUE);
                }
                tile.addRun(ground, 32, 0, RunTile.AIR);
            }
        }
        tile.finish();
        assertEquals(6, tile.runs(RunTile.column(5, 5)));
        tile.collapseBuried();
        // Away from the pit only the surface stratum can be seen: one run under it, and air.
        assertEquals(2, tile.runs(RunTile.column(5, 5)));
        // Beside the pit the strata from 8 up stay apart; those below join.
        assertEquals(1 + 3 + 1, tile.runs(RunTile.column(29, 30)));
        // The pit's own column: its two strata join, since the space starts above them.
        assertEquals(2, tile.runs(RunTile.column(30, 30)));
    }

    @Test
    void aRecordGivesBackWhatWentIn() {
        Random random = new Random(3);
        for (int trial = 0; trial < 10_000; trial++) {
            int x = random.nextInt(64);
            int z = random.nextInt(64);
            int y = random.nextInt(4096);
            int direction = random.nextInt(6);
            int material = random.nextInt(FaceRecord.MATERIALS);
            int light = random.nextInt(16);
            int occlusion = random.nextInt(256);
            int height = 1 + random.nextInt(4096);
            long side = FaceRecord.side(x, z, y, height, direction, material, light, occlusion);
            assertEquals(x, FaceRecord.x(side));
            assertEquals(z, FaceRecord.z(side));
            assertEquals(y, FaceRecord.y(side));
            assertEquals(height, FaceRecord.height(side));
            assertEquals(direction, FaceRecord.direction(side));
            assertEquals(material, FaceRecord.material(side));
            assertEquals(light, FaceRecord.light(side));
            assertEquals(occlusion, FaceRecord.occlusion(side));
            int width = 1 + random.nextInt(64);
            int depth = 1 + random.nextInt(64);
            long flat = FaceRecord.flat(x, z, y, width, depth, direction, material, light, occlusion);
            assertEquals(width, FaceRecord.width(flat));
            assertEquals(depth, FaceRecord.depth(flat));
            assertEquals(y, FaceRecord.y(flat));
            assertEquals(material, FaceRecord.material(flat));
            assertEquals(occlusion, FaceRecord.occlusion(flat));
        }
        assertEquals(8, FaceRecord.BYTES);
    }
}
