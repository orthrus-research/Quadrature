package cc.orthrus.quadrature.core.lod;

/** Test tiles built from a function of position, and the block-by-block truth to check meshes against. */
final class Tiles {

    static final int FLOOR = -8, CEILING = 56, HEIGHT = CEILING - FLOOR;

    /** What stands at a block: {@code material << 3 | kind}. */
    @FunctionalInterface
    interface Blocks {
        int at(int x, int z, int y);
    }

    private Tiles() {
    }

    static int code(int material, byte kind) {
        return material << 3 | kind;
    }

    static final int AIR = code(0, RunTile.AIR);

    static RunTile fill(Blocks blocks) {
        RunTile tile = new RunTile(FLOOR, CEILING);
        tile.reset(0, 0, 0);
        for (int z = -1; z <= RunTile.CELLS; z++) {
            for (int x = -1; x <= RunTile.CELLS; x++) {
                tile.beginColumn(x * 1000 + z);
                int from = FLOOR;
                int held = blocks.at(x, z, FLOOR);
                for (int y = FLOOR + 1; y <= CEILING; y++) {
                    int next = y == CEILING ? -1 : blocks.at(x, z, y);
                    if (next != held) {
                        tile.addRun(from, y, held >> 3, (byte) (held & 7));
                        from = y;
                        held = next;
                    }
                }
            }
        }
        tile.finish();
        return tile;
    }

    /** The kind at every block of the tile and its apron: index {@link #index}. */
    static byte[] kinds(Blocks blocks) {
        byte[] kinds = new byte[RunTile.COLUMNS * HEIGHT];
        for (int z = -1; z <= RunTile.CELLS; z++) {
            for (int x = -1; x <= RunTile.CELLS; x++) {
                for (int y = FLOOR; y < CEILING; y++) {
                    kinds[index(x, z, y)] = (byte) (blocks.at(x, z, y) & 7);
                }
            }
        }
        return kinds;
    }

    static int index(int x, int z, int y) {
        return RunTile.column(x, z) * HEIGHT + y - FLOOR;
    }

    /** Rolling ground with cliffs, overhangs, a pond, trees and a few detail blocks, from a seed. */
    static Blocks terrain(long seed) {
        return (x, z, y) -> {
            int ground = 12 + (int) (6 * Math.sin((x + seed % 17) * 0.21) + 5 * Math.cos((z - seed % 11) * 0.17))
                    + (hash(seed, x >> 3, z >> 3) % 5 == 0 ? 7 : 0);
            int water = 10;
            // A ledge: air under an upper slab, in a band of columns.
            boolean ledge = hash(seed, x >> 2, 77) % 4 == 0 && y >= ground - 4 && y < ground - 2;
            if (y < ground && !ledge) {
                return code(y < ground - 3 ? 1 : y < ground - 1 ? 2 : 3, RunTile.OPAQUE);
            }
            if (ledge) {
                return AIR;
            }
            if (y < water) {
                return code(9, RunTile.WATER);
            }
            boolean tree = hash(seed, x, z) % 9 == 0 && ground >= water;
            if (tree && y < ground + 3) {
                return code(5, RunTile.OPAQUE);
            }
            boolean crown = hash(seed, (x + 1) / 3, (z + 1) / 3) % 3 == 0 && ground >= water;
            if (crown && y >= ground + 3 && y < ground + 6) {
                return code(6, RunTile.LEAVES);
            }
            if (y == ground && hash(seed, x * 3, z * 5) % 7 == 0 && ground >= water) {
                return code(7, RunTile.DETAIL);
            }
            return AIR;
        };
    }

    static int hash(long seed, int a, int b) {
        long h = seed * 0x9E3779B97F4A7C15L + a * 0xBF58476D1CE4E5B9L + b * 0x94D049BB133111EBL;
        h ^= h >>> 29;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 32;
        return (int) (h & 0x7FFFFFFF);
    }
}
