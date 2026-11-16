package cc.orthrus.quadrature.core.lod;

import static cc.orthrus.quadrature.core.lod.Tiles.AIR;
import static cc.orthrus.quadrature.core.lod.Tiles.CEILING;
import static cc.orthrus.quadrature.core.lod.Tiles.FLOOR;
import static cc.orthrus.quadrature.core.lod.Tiles.HEIGHT;
import static cc.orthrus.quadrature.core.lod.Tiles.code;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;

class RunMesherTest {

    private static final int STONE = code(1, RunTile.OPAQUE);
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private static TileMesh mesh(RunTile tile) {
        TileMesh mesh = new TileMesh();
        new RunMesher().mesh(tile, mesh);
        return mesh;
    }

    /** Marks the unit cells a group's faces cover and fails if two faces cover one. */
    private static BitSet covered(TileMesh mesh, int layer, int direction) {
        BitSet cells = new BitSet(RunTile.COLUMNS * HEIGHT);
        int group = TileMesh.group(layer, direction);
        for (int i = 0; i < mesh.count(group); i++) {
            long record = mesh.record(group, i);
            assertEquals(direction, FaceRecord.direction(record));
            int x = FaceRecord.x(record);
            int z = FaceRecord.z(record);
            int y = FaceRecord.y(record) + FLOOR;
            if (direction <= FaceRecord.DOWN) {
                for (int dz = 0; dz < FaceRecord.depth(record); dz++) {
                    for (int dx = 0; dx < FaceRecord.width(record); dx++) {
                        int cell = Tiles.index(x + dx, z + dz, direction == FaceRecord.UP ? y - 1 : y);
                        assertFalse(cells.get(cell), "two faces on one cell");
                        cells.set(cell);
                    }
                }
            } else {
                for (int dy = 0; dy < FaceRecord.height(record); dy++) {
                    int cell = Tiles.index(x, z, y + dy);
                    assertFalse(cells.get(cell), "two faces on one cell");
                    cells.set(cell);
                }
            }
        }
        return cells;
    }

    private static boolean space(byte kind) {
        return kind != RunTile.OPAQUE;
    }

    /**
     * Every face the rules call for, block by block, and no other: a side face exactly where a
     * solid block meets space, none between two solid neighbors, tops and bottoms exactly where a
     * run meets space above or below, and the skirt exactly what the neighbor hides.
     */
    private static void assertMatchesBlocks(Tiles.Blocks blocks, TileMesh mesh) {
        byte[] kinds = Tiles.kinds(blocks);
        for (int direction = 0; direction < FaceRecord.DIRECTIONS; direction++) {
            BitSet opaque = new BitSet();
            BitSet cutout = new BitSet();
            BitSet water = new BitSet();
            BitSet skirt = new BitSet();
            for (int z = 0; z < RunTile.CELLS; z++) {
                for (int x = 0; x < RunTile.CELLS; x++) {
                    boolean outward = direction == FaceRecord.WEST && x == 0
                            || direction == FaceRecord.EAST && x == RunTile.CELLS - 1
                            || direction == FaceRecord.NORTH && z == 0
                            || direction == FaceRecord.SOUTH && z == RunTile.CELLS - 1;
                    for (int y = FLOOR; y < CEILING; y++) {
                        byte here = kinds[Tiles.index(x, z, y)];
                        int cell = Tiles.index(x, z, y);
                        byte beside;
                        if (direction == FaceRecord.UP) {
                            if (y + 1 == CEILING) {
                                continue;
                            }
                            beside = kinds[Tiles.index(x, z, y + 1)];
                            if (here == RunTile.WATER && beside != RunTile.WATER && beside != RunTile.OPAQUE) {
                                water.set(cell);
                            }
                        } else if (direction == FaceRecord.DOWN) {
                            if (y == FLOOR) {
                                continue;
                            }
                            beside = kinds[Tiles.index(x, z, y - 1)];
                        } else {
                            beside = kinds[Tiles.index(x + DX[direction], z + DZ[direction], y)];
                            if (outward && here == RunTile.OPAQUE && !space(beside)) {
                                skirt.set(cell);
                            }
                        }
                        if (here == RunTile.OPAQUE && space(beside)) {
                            opaque.set(cell);
                        }
                        if (here == RunTile.LEAVES && beside != RunTile.OPAQUE && beside != RunTile.LEAVES) {
                            cutout.set(cell);
                        }
                    }
                }
            }
            assertEquals(opaque, covered(mesh, TileMesh.OPAQUE, direction), "opaque, direction " + direction);
            assertEquals(cutout, covered(mesh, TileMesh.CUTOUT, direction), "leaves, direction " + direction);
            assertEquals(water, covered(mesh, TileMesh.WATER, direction), "water, direction " + direction);
            assertEquals(skirt, covered(mesh, TileMesh.SKIRT, direction), "skirt, direction " + direction);
        }
    }

    @Test
    void flatGroundIsOneTopAndAClosedRim() {
        RunTile tile = Tiles.fill((x, z, y) -> y < 10 ? STONE : AIR);
        TileMesh mesh = mesh(tile);
        assertEquals(1, mesh.layerCount(TileMesh.OPAQUE));
        long top = mesh.record(TileMesh.group(TileMesh.OPAQUE, FaceRecord.UP), 0);
        assertEquals(64, FaceRecord.width(top));
        assertEquals(64, FaceRecord.depth(top));
        assertEquals(10 - FLOOR, FaceRecord.y(top));
        assertEquals(15, FaceRecord.light(top));
        assertEquals(0, FaceRecord.occlusion(top));
        assertEquals(64 * 64, mesh.topCells());
        // The neighbors hide the whole rim: one skirt face a rim column and side.
        assertEquals(4 * 64, mesh.layerCount(TileMesh.SKIRT));
        long rim = mesh.record(TileMesh.group(TileMesh.SKIRT, FaceRecord.WEST), 0);
        assertEquals(0, FaceRecord.y(rim));
        assertEquals(10 - FLOOR, FaceRecord.height(rim));
        // A skirt is seen when the tile beside it is missing: it has the ground's material and
        // the light over the column beside it, not none of either.
        assertEquals(FaceRecord.material(top), FaceRecord.material(rim));
        assertEquals(15, FaceRecord.light(rim));
        assertEquals(FLOOR, mesh.minimumY());
        assertEquals(10, mesh.maximumY());

        TileMesh open = new TileMesh();
        new RunMesher().closed(false).mesh(tile, open);
        assertEquals(1, open.faces());
    }

    @Test
    void facesAreExactlyWhereSolidMeetsSpace() {
        for (long seed = 1; seed <= 6; seed++) {
            Tiles.Blocks blocks = Tiles.terrain(seed);
            TileMesh mesh = mesh(Tiles.fill(blocks));
            assertTrue(mesh.layerCount(TileMesh.CUTOUT) > 0 && mesh.layerCount(TileMesh.WATER) > 0);
            assertMatchesBlocks(blocks, mesh);
        }
    }

    @Test
    void aClosedTileIsCoveredFromFloorToSurfaceOnItsFourSides() {
        Tiles.Blocks blocks = Tiles.terrain(3);
        byte[] kinds = Tiles.kinds(blocks);
        TileMesh mesh = mesh(Tiles.fill(blocks));
        for (int direction = FaceRecord.NORTH; direction <= FaceRecord.EAST; direction++) {
            BitSet side = covered(mesh, TileMesh.OPAQUE, direction);
            BitSet skirt = covered(mesh, TileMesh.SKIRT, direction);
            assertFalse(side.intersects(skirt));
            for (int i = 0; i < RunTile.CELLS; i++) {
                int x = direction == FaceRecord.WEST ? 0 : direction == FaceRecord.EAST ? 63 : i;
                int z = direction == FaceRecord.NORTH ? 0 : direction == FaceRecord.SOUTH ? 63 : i;
                for (int y = FLOOR; y < CEILING; y++) {
                    int cell = Tiles.index(x, z, y);
                    assertEquals(kinds[cell] == RunTile.OPAQUE, side.get(cell) || skirt.get(cell),
                            "rim " + x + " " + z + " " + y);
                }
            }
        }
    }

    @Test
    void mergedTopsCoverWhatTheUnmergedDid() {
        Tiles.Blocks blocks = Tiles.terrain(5);
        TileMesh mesh = mesh(Tiles.fill(blocks));
        int area = 0;
        int faces = 0;
        for (int layer = 0; layer < TileMesh.LAYERS; layer++) {
            int group = TileMesh.group(layer, FaceRecord.UP);
            for (int i = 0; i < mesh.count(group); i++) {
                long record = mesh.record(group, i);
                area += FaceRecord.width(record) * FaceRecord.depth(record);
                faces++;
                // A face joins others only along an axis on which its corners' occlusion agrees.
                int o = FaceRecord.occlusion(record);
                assertTrue(FaceRecord.width(record) == 1 || (o & 3) == (o >> 2 & 3) && (o >> 4 & 3) == (o >> 6));
                assertTrue(FaceRecord.depth(record) == 1 || (o & 3) == (o >> 4 & 3) && (o >> 2 & 3) == (o >> 6));
            }
        }
        assertEquals(mesh.topCells(), area);
        assertTrue(faces < mesh.topCells());
    }

    @Test
    void aSideFaceCrossesStrataAndCarriesTheMaterialAtItsTop() {
        // Columns west of x = 32 stand 12 high in two strata; the rest stand 4 high.
        RunTile tile = Tiles.fill((x, z, y) -> {
            int height = x < 32 ? 12 : 4;
            return y >= height ? AIR : code(y < 7 ? 1 : 2, RunTile.OPAQUE);
        });
        TileMesh mesh = mesh(tile);
        int east = TileMesh.group(TileMesh.OPAQUE, FaceRecord.EAST);
        assertEquals(64, mesh.count(east));
        long face = mesh.record(east, 0);
        assertEquals(31, FaceRecord.x(face));
        assertEquals(4 - FLOOR, FaceRecord.y(face));
        assertEquals(8, FaceRecord.height(face));
        assertEquals(2, FaceRecord.material(face));
        assertEquals(15, FaceRecord.light(face));
        // The low ground closes in the face's two lower corners: the floor under the space and
        // the floor beside it, two of the three cells at each.
        assertEquals(2 | 2 << 2, FaceRecord.occlusion(face));
    }

    @Test
    void aTrunkAndTheGroundUnderABranchAreLitFromBeside() {
        int wood = code(7, RunTile.OPAQUE);
        // A trunk at (30, 30) from 4 to 10, a branch of one log beside it at (31, 30, 9), and a
        // crown one block thick over both and around them.
        RunTile tile = Tiles.fill((x, z, y) -> {
            if (y < 4) {
                return STONE;
            }
            if (x == 30 && z == 30 && y < 10 || x == 31 && z == 30 && y == 9) {
                return wood;
            }
            if (y == 10 && x >= 27 && x <= 33 && z >= 27 && z <= 33) {
                return code(6, RunTile.LEAVES);
            }
            return AIR;
        });
        TileMesh mesh = mesh(tile);
        // The ground under the branch: no sky straight above it, 15 - 1 beside it, so 13.
        int tops = TileMesh.group(TileMesh.OPAQUE, FaceRecord.UP);
        int under = -1;
        int beside = -1;
        for (int i = 0; i < mesh.count(tops); i++) {
            long record = mesh.record(tops, i);
            if (FaceRecord.y(record) != 4 - FLOOR || FaceRecord.z(record) > 30
                    || FaceRecord.z(record) + FaceRecord.depth(record) <= 30) {
                continue;
            }
            if (FaceRecord.x(record) <= 31 && FaceRecord.x(record) + FaceRecord.width(record) > 31) {
                under = FaceRecord.light(record);
            }
            if (FaceRecord.x(record) <= 32 && FaceRecord.x(record) + FaceRecord.width(record) > 32) {
                beside = FaceRecord.light(record);
            }
        }
        assertEquals(14, beside);
        assertEquals(13, under);
        // The trunk's east face looks into the space under the branch and takes that light too.
        int east = TileMesh.group(TileMesh.OPAQUE, FaceRecord.EAST);
        int trunk = -1;
        for (int i = 0; i < mesh.count(east); i++) {
            long record = mesh.record(east, i);
            if (FaceRecord.x(record) == 30 && FaceRecord.z(record) == 30 && FaceRecord.material(record) == 7) {
                trunk = FaceRecord.light(record);
            }
        }
        assertEquals(13, trunk);
    }

    @Test
    void lightFallsThroughLeavesAndWaterAndStopsAtRock() {
        RunTile tile = Tiles.fill((x, z, y) -> {
            if (y < 4) {
                return STONE;
            }
            if (x < 20) {
                return y < 6 ? code(9, RunTile.WATER) : AIR;
            }
            if (x >= 40) {
                return y >= 8 && y < 11 ? code(6, RunTile.LEAVES) : y >= 20 && y < 21 && x >= 50 ? STONE : AIR;
            }
            return AIR;
        });
        TileMesh mesh = mesh(tile);
        int tops = TileMesh.group(TileMesh.OPAQUE, FaceRecord.UP);
        int[] lightAt = new int[64];
        java.util.Arrays.fill(lightAt, -1);
        for (int i = 0; i < mesh.count(tops); i++) {
            long record = mesh.record(tops, i);
            if (FaceRecord.y(record) == 4 - FLOOR && FaceRecord.z(record) == 0) {
                for (int dx = 0; dx < FaceRecord.width(record); dx++) {
                    lightAt[FaceRecord.x(record) + dx] = FaceRecord.light(record);
                }
            }
        }
        assertEquals(15 - 3 * 2, lightAt[5]);
        assertEquals(15, lightAt[30]);
        assertEquals(15 - 3, lightAt[45]);
        assertEquals(0, lightAt[55]);
        // Under the slab's edge light comes in from the column beside it, one less: 15 - 3 - 1.
        assertEquals(11, lightAt[50]);
        assertEquals(0, lightAt[51]);
        long water = mesh.record(TileMesh.group(TileMesh.WATER, FaceRecord.UP), 0);
        assertEquals(20, FaceRecord.width(water));
        assertEquals(9, FaceRecord.material(water));
    }

    @Test
    void aPillarClosesInTheCornersOfTheGroundRoundIt() {
        RunTile tile = Tiles.fill((x, z, y) -> y < 4 || x == 10 && z == 10 && y < 9 ? STONE : AIR);
        TileMesh mesh = mesh(tile);
        int tops = TileMesh.group(TileMesh.OPAQUE, FaceRecord.UP);
        int closed = 0;
        for (int i = 0; i < mesh.count(tops); i++) {
            long record = mesh.record(tops, i);
            if (FaceRecord.occlusion(record) != 0) {
                closed++;
                int x = FaceRecord.x(record);
                int z = FaceRecord.z(record);
                assertTrue(Math.abs(x - 10) <= 1 && Math.abs(z - 10) <= 1);
                if (x == 9 && z == 10) {
                    // The pillar stands east: both eastern corners, one each.
                    assertEquals(1 << 2 | 1 << 6, FaceRecord.occlusion(record));
                }
                if (x == 9 && z == 9) {
                    // Diagonal only: the south-east corner.
                    assertEquals(1 << 6, FaceRecord.occlusion(record));
                }
            }
        }
        assertEquals(8, closed);
    }

    @Test
    void groundAlongAWallJoinsAlongTheWallOnly() {
        // A wall along x = 20; the ground west of it is closed in at its eastern corners, each by
        // the wall beside it and the wall diagonal to it.
        RunTile tile = Tiles.fill((x, z, y) -> y < 4 || x == 20 && y < 9 ? STONE : AIR);
        TileMesh mesh = mesh(tile);
        int tops = TileMesh.group(TileMesh.OPAQUE, FaceRecord.UP);
        int beside = 0;
        for (int i = 0; i < mesh.count(tops); i++) {
            long record = mesh.record(tops, i);
            if (FaceRecord.x(record) == 19) {
                beside++;
                assertEquals(1, FaceRecord.width(record));
                assertEquals(64, FaceRecord.depth(record));
                assertEquals(2 << 2 | 2 << 6, FaceRecord.occlusion(record));
            }
        }
        assertEquals(1, beside);
        // West ground, the strip each side of the wall, the wall's top and the east ground.
        assertEquals(5, mesh.count(tops));
    }

    @Test
    void exposureLeavesOutSpaceTheSkyCannotReach() {
        // A sealed room under the ground, and a shaft that opens another room to the sky.
        Tiles.Blocks blocks = (x, z, y) -> {
            boolean sealed = x >= 10 && x < 14 && z >= 10 && z < 14 && y >= 0 && y < 3;
            boolean opened = x >= 30 && x < 34 && z >= 30 && z < 34 && y >= 0 && y < 3;
            boolean shaft = x == 31 && z == 31 && y >= 3;
            return y < 12 && !sealed && !opened && !shaft ? STONE : AIR;
        };
        RunTile tile = Tiles.fill(blocks);
        TileMesh all = mesh(tile);
        assertMatchesBlocks(blocks, all);
        TileMesh exposed = new TileMesh();
        new RunMesher().exposure(true).mesh(tile, exposed);
        // The sealed room is gone and nothing else changed: its floor (the four middle cells as
        // one face, the two along each wall as one, each corner alone), its 16 ceiling cells and
        // 16 walls.
        int roomFaces = all.faces() - exposed.faces();
        assertEquals(1 + 4 + 4 + 16 + 16, roomFaces);
        assertEquals(all.count(TileMesh.group(TileMesh.OPAQUE, FaceRecord.DOWN)) - 16,
                exposed.count(TileMesh.group(TileMesh.OPAQUE, FaceRecord.DOWN)));
        assertEquals(all.layerCount(TileMesh.SKIRT), exposed.layerCount(TileMesh.SKIRT));
    }

    @Test
    void collapsingBuriedStrataLeavesTheMeshAsItWas() {
        for (long seed = 1; seed <= 3; seed++) {
            RunTile tile = Tiles.fill(Tiles.terrain(seed));
            TileMesh before = mesh(tile);
            int runs = tile.runs();
            int removed = tile.collapseBuried();
            assertTrue(removed > 0);
            assertEquals(runs - removed, tile.runs());
            TileMesh after = mesh(tile);
            for (int group = 0; group < TileMesh.GROUPS; group++) {
                assertEquals(before.count(group), after.count(group));
                for (int i = 0; i < before.count(group); i++) {
                    assertEquals(before.record(group, i), after.record(group, i));
                }
            }
            assertEquals(0, tile.collapseBuried());
        }
    }

    @Test
    void recordsAreWrittenGroupAfterGroupInTheMachinesByteOrder() {
        RunTile tile = Tiles.fill(Tiles.terrain(2));
        TileMesh mesh = mesh(tile);
        ByteBuffer buffer = ByteBuffer.allocateDirect(mesh.bytes() + 16).order(ByteOrder.nativeOrder());
        buffer.position(8);
        mesh.write(buffer);
        assertEquals(8 + mesh.bytes(), buffer.position());
        int total = 0;
        for (int group = 0; group < TileMesh.GROUPS; group++) {
            assertEquals(total, mesh.offset(group));
            for (int i = 0; i < mesh.count(group); i++) {
                assertEquals(mesh.record(group, i), buffer.getLong(8 + (total + i) * FaceRecord.BYTES));
            }
            total += mesh.count(group);
        }
        assertEquals(mesh.faces(), total);
        assertEquals(mesh.faces() * 8, mesh.bytes());

        ByteBuffer small = ByteBuffer.allocate(mesh.bytes() - 8).order(ByteOrder.nativeOrder());
        assertThrows(IllegalArgumentException.class, () -> mesh.write(small));
        ByteOrder other = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? ByteOrder.BIG_ENDIAN
                : ByteOrder.LITTLE_ENDIAN;
        ByteBuffer swapped = ByteBuffer.allocate(mesh.bytes()).order(other);
        assertThrows(IllegalArgumentException.class, () -> mesh.write(swapped));

        // The tint of each of the 64 by 64 columns, as given.
        assertEquals(0, mesh.tint(0, 0));
        assertEquals(63 * 1000 + 5, mesh.tint(63, 5));
        ByteBuffer tints = ByteBuffer.allocate(64 * 64 * 4).order(ByteOrder.nativeOrder());
        mesh.writeTints(tints);
        assertEquals(7 * 1000 + 2, tints.getInt((2 * 64 + 7) * 4));
    }

    @Test
    void aMesherAndItsMeshAreReusedWithoutCarryingFacesOver() {
        RunMesher mesher = new RunMesher();
        TileMesh mesh = new TileMesh();
        mesher.mesh(Tiles.fill(Tiles.terrain(4)), mesh);
        int rough = mesh.faces();
        mesher.mesh(Tiles.fill((x, z, y) -> y < 10 ? STONE : AIR), mesh);
        assertEquals(1 + 256, mesh.faces());
        mesher.mesh(Tiles.fill(Tiles.terrain(4)), mesh);
        assertEquals(rough, mesh.faces());
    }
}
