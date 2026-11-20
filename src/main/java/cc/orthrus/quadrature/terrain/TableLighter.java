package cc.orthrus.quadrature.terrain;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.model.pipeline.VertexLighterSmoothAo;

/**
 * Forge's smooth lighter with the light around each block taken from a table of the cube being meshed (PLAN item
 * 12c, first step). Forge's lighter asks the world four questions about each of the 27 cells around every block it
 * lights, making a position for each, though neighboring blocks ask about the same cells: up to 27 times over. Here
 * each cell of the cube and the layer around it is asked once, the first time a block needs it, through the same
 * world, so the same cells are read and the reads of the cubes around are noted as before. The answers are the
 * same calls' answers, so the light matrix, and every vertex lit from it, is the same.
 *
 * <p>What the matrix holds, for each of the block's eight corners and each of the three axes, is worked out here in
 * Quadrature's own terms of the rule Forge applies, written down in PROVENANCE: the light at the corner of a face is
 * the mean of four cells, the face's neighbor, the two edge cells beyond it toward the corner and the corner cell,
 * where the corner cell counts as the face's neighbor again when neither edge cell lets light through. A dark cell
 * that does not let light through is first filled from those around it: the face's neighbor from the brighter edge
 * cell, each edge cell from the face's neighbor, and the corner from the brighter edge cell as filled, each one
 * less, never below nothing. Before that, the neighbor beyond each side of the block that does not cover that side
 * is raised to at least the block's own light less one. Per-vertex lighting, the occlusion's shading and the colors
 * are Forge's own, from the matrix.
 *
 * <p>One a mesher, used from that mesher's build thread only. {@code -Dquadrature.terrain.lightTable=off} uses
 * Forge's lighter as it is.
 */
final class TableLighter extends VertexLighterSmoothAo {

    static final boolean ON = !"off".equals(System.getProperty("quadrature.terrain.lightTable"));

    /** Cells a side: the cube's 16 and one beyond it on each side. */
    private static final int SIDE = 18;
    private static final int CELLS = SIDE * SIDE * SIDE;
    private static final EnumFacing[] SIDES = EnumFacing.values();

    /** For each cell, the build it was filled for; a cell of an earlier build is filled again. */
    private final int[] filledFor = new int[CELLS];
    private final byte[] sky = new byte[CELLS];
    private final byte[] block = new byte[CELLS];
    private final boolean[] clear = new boolean[CELLS];
    private final float[] occlusion = new float[CELLS];
    private final BlockPos.MutableBlockPos asked = new BlockPos.MutableBlockPos();

    private int build;
    private IBlockAccess world;
    /** The cube's lowest corner, less one: the table's first cell. */
    private int originX;
    private int originY;
    private int originZ;

    /** The 27 cells' sky and block light for the block being lit, after the raise beyond uncovered sides. */
    private final int[] skyAround = new int[27];
    private final int[] blockAround = new int[27];

    TableLighter(BlockColors colors) {
        super(colors);
    }

    /** Starts a cube: what was asked of the last one is forgotten. */
    void beginCube(IBlockAccess world, int minX, int minY, int minZ) {
        this.world = world;
        originX = minX - 1;
        originY = minY - 1;
        originZ = minZ - 1;
        if (++build == 0) {
            // After four billion cubes the stamps start over; none may be mistaken for this build.
            java.util.Arrays.fill(filledFor, 0);
            build = 1;
        }
    }

    /** Lets go of the cube's world, so that a finished build's snapshot is not kept. */
    void endCube() {
        world = null;
    }

    @Override
    public void updateBlockInfo() {
        BlockPos pos = blockInfo.getBlockPos();
        int x = pos.getX() - originX;
        int y = pos.getY() - originY;
        int z = pos.getZ() - originZ;
        if (world == null || blockInfo.getWorld() != world || x < 1 || y < 1 || z < 1 || x > 16 || y > 16 || z > 16) {
            // A block lit outside a cube begun here: Forge's own way.
            super.updateBlockInfo();
            return;
        }
        blockInfo.updateShift();
        boolean[][][] clearAround = blockInfo.getTranslucent();
        float[][][] occlusionAround = blockInfo.getAo();
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                for (int dz = 0; dz < 3; dz++) {
                    int cell = cell(x + dx - 1, y + dy - 1, z + dz - 1);
                    int around = around(dx, dy, dz);
                    clearAround[dx][dy][dz] = clear[cell];
                    occlusionAround[dx][dy][dz] = occlusion[cell];
                    skyAround[around] = sky[cell];
                    blockAround[around] = block[cell];
                }
            }
        }
        IBlockState state = blockInfo.getState();
        int center = around(1, 1, 1);
        for (EnumFacing side : SIDES) {
            if (!state.doesSideBlockRendering(blockInfo.getWorld(), pos, side)) {
                int beyond = around(side.getXOffset() + 1, side.getYOffset() + 1, side.getZOffset() + 1);
                skyAround[beyond] = Math.max(skyAround[center] - 1, skyAround[beyond]);
                blockAround[beyond] = Math.max(blockAround[center] - 1, blockAround[beyond]);
            }
        }
        float[][][][] skyMatrix = blockInfo.getSkyLight();
        float[][][][] blockMatrix = blockInfo.getBlockLight();
        for (int cx = 0; cx < 2; cx++) {
            for (int cy = 0; cy < 2; cy++) {
                for (int cz = 0; cz < 2; cz++) {
                    // The corner's offsets in the 3x3x3 around the block: 0 below, 2 above, on each axis.
                    int ox = cx * 2;
                    int oy = cy * 2;
                    int oz = cz * 2;
                    int corner = around(ox, oy, oz);
                    for (int axis = 0; axis < 3; axis++) {
                        // The face across the axis, and the two edge cells beyond it along the other two axes.
                        int face = axis == 0 ? around(ox, 1, 1) : axis == 1 ? around(1, oy, 1) : around(1, 1, oz);
                        int edgeA = axis == 0 ? around(ox, oy, 1) : axis == 1 ? around(ox, oy, 1) : around(ox, 1, oz);
                        int edgeB = axis == 0 ? around(ox, 1, oz) : axis == 1 ? around(1, oy, oz) : around(1, oy, oz);
                        skyMatrix[axis][cx][cy][cz] = cornerLight(skyAround, clearAround, face, edgeA, edgeB, corner);
                        blockMatrix[axis][cx][cy][cz] = cornerLight(blockAround, clearAround, face, edgeA, edgeB, corner);
                    }
                }
            }
        }
    }

    /** Whether quads are lit straight into the buffer ({@link #putQuad}); off with {@code quadrature.terrain.directLight=off}. */
    static final boolean DIRECT = !"off".equals(System.getProperty("quadrature.terrain.directLight"));
    private final int[] quadOut = new int[4 * 7];
    private final float[] lightmapOut = new float[4];

    /**
     * Lights a quad of the block being lit straight into the buffer, writing what Forge's pipeline writes for it: the
     * quad's vertices are read as its unpacking reads them, each vertex lit from the matrix by Forge's own per-vertex
     * methods (the lightmap by {@code calcLightmap}, the occlusion by {@code getAo}), tinted, occluded and shaded in
     * its order, and packed with its rounding, without the unpacking into floats element by element, the gathering
     * and the packing back that cost most of a block's meshing (PLAN item 12c). Where the quad is not of the
     * game's item format, the buffer not of its block format, colors are left out, anaglyph is on, or the quad's first
     * normal asks for one to be made, nothing is written and the quad goes through Forge's pipeline.
     *
     * @return whether the quad was written
     */
    boolean putQuad(net.minecraft.client.renderer.block.model.BakedQuad quad, net.minecraft.client.renderer.BufferBuilder buffer) {
        if (!DIRECT || quad.getFormat() != net.minecraft.client.renderer.vertex.DefaultVertexFormats.ITEM
            || buffer.getVertexFormat() != net.minecraft.client.renderer.vertex.DefaultVertexFormats.BLOCK
            || buffer.isColorDisabled() || net.minecraft.client.renderer.EntityRenderer.anaglyphEnable) {
            return false;
        }
        int[] from = quad.getVertexData();
        if (from.length < 4 * 7) {
            return false;
        }
        // Forge uses the quad's normals unless the first vertex's is (-1, -1, -1), when it makes one.
        int firstNormal = from[6];
        if (normal(firstNormal, 0) == -1 && normal(firstNormal, 8) == -1 && normal(firstNormal, 16) == -1) {
            return false;
        }
        int tint = quad.hasTintIndex() ? quad.getTintIndex() : -1;
        int multiplier = tint != -1 ? blockInfo.getColorMultiplier(tint) : -1;
        boolean diffuse = quad.shouldApplyDiffuseLighting();
        float shx = blockInfo.getShx();
        float shy = blockInfo.getShy();
        float shz = blockInfo.getShz();
        for (int v = 0; v < 4; v++) {
            int in = v * 7;
            float px = Float.intBitsToFloat(from[in]) + shx;
            float py = Float.intBitsToFloat(from[in + 1]) + shy;
            float pz = Float.intBitsToFloat(from[in + 2]) + shz;
            int color = from[in + 3];
            float r = (float) (color & 0xFF) / 255;
            float g = (float) (color >>> 8 & 0xFF) / 255;
            float b = (float) (color >>> 16 & 0xFF) / 255;
            float a = (float) (color >>> 24 & 0xFF) / 255;
            int packedNormal = from[in + 6];
            float nx = normal(packedNormal, 0);
            float ny = normal(packedNormal, 8);
            float nz = normal(packedNormal, 16);
            float x = px - .5f + nx * .5f;
            float y = py - .5f + ny * .5f;
            float z = pz - .5f + nz * .5f;
            calcLightmap(lightmapOut, x, y, z);
            if (tint != -1) {
                r *= (float) (multiplier >> 0x10 & 0xFF) / 0xFF;
                g *= (float) (multiplier >> 0x8 & 0xFF) / 0xFF;
                b *= (float) (multiplier & 0xFF) / 0xFF;
            }
            float occlusion = getAo(x, y, z);
            r *= occlusion;
            g *= occlusion;
            b *= occlusion;
            if (diffuse) {
                float shade = net.minecraftforge.client.model.pipeline.LightUtil.diffuseLight(nx, ny, nz);
                r *= shade;
                g *= shade;
                b *= shade;
            }
            int out = v * 7;
            quadOut[out] = Float.floatToRawIntBits(px);
            quadOut[out + 1] = Float.floatToRawIntBits(py);
            quadOut[out + 2] = Float.floatToRawIntBits(pz);
            quadOut[out + 3] = (Math.round(r * 255) & 0xFF) | (Math.round(g * 255) & 0xFF) << 8
                | (Math.round(b * 255) & 0xFF) << 16 | (Math.round(a * 255) & 0xFF) << 24;
            quadOut[out + 4] = Float.floatToRawIntBits(Float.intBitsToFloat(from[in + 4]));
            quadOut[out + 5] = Float.floatToRawIntBits(Float.intBitsToFloat(from[in + 5]));
            quadOut[out + 6] = (Math.round(lightmapOut[0] * 32767) & 0xFFFF) | (Math.round(lightmapOut[1] * 32767) & 0xFFFF) << 16;
        }
        buffer.addVertexData(quadOut);
        // As Forge's buffer consumer does, with its offset kept at the origin by the walk.
        buffer.putPosition(0, 0, 0);
        return true;
    }

    /** One component of a packed normal, as Forge's unpacking reads a signed byte. */
    private static float normal(int packed, int shift) {
        return ((float) (byte) (packed >>> shift)) / 127;
    }

    /** The light at one corner of one face, from four cells of the 3x3x3 around the block. */
    private float cornerLight(int[] light, boolean[][][] clearAround, int face, int edgeA, int edgeB, int corner) {
        boolean faceClear = clearAt(clearAround, face);
        boolean edgeAClear = clearAt(clearAround, edgeA);
        boolean edgeBClear = clearAt(clearAround, edgeB);
        // Seen past two edges that both stop light, the corner is the face's neighbor again.
        boolean cornerSeen = edgeAClear || edgeBClear;
        int f = light[face];
        int a = light[edgeA];
        int b = light[edgeB];
        int c = cornerSeen ? light[corner] : f;
        boolean cornerClear = cornerSeen ? clearAt(clearAround, corner) : faceClear;
        if (f == 0 && !faceClear) {
            f = Math.max(0, Math.max(a, b) - 1);
        }
        if (a == 0 && !edgeAClear) {
            a = Math.max(0, f - 1);
        }
        if (b == 0 && !edgeBClear) {
            b = Math.max(0, f - 1);
        }
        if (c == 0 && !cornerClear) {
            c = Math.max(0, Math.max(a, b) - 1);
        }
        // The game's lightmap coordinate of a light level, averaged over the four: the same float operations.
        return (float) (f + a + b + c) * 32f / 262140f;
    }

    private static boolean clearAt(boolean[][][] clearAround, int around) {
        return clearAround[around / 9][around / 3 % 3][around % 3];
    }

    /** The index of a cell of the 3x3x3 around the block, each offset 0 to 2. */
    private static int around(int dx, int dy, int dz) {
        return dx * 9 + dy * 3 + dz;
    }

    /** The table's cell at a place relative to its first cell, filled for this build if it was not. */
    private int cell(int x, int y, int z) {
        int cell = (y * SIDE + z) * SIDE + x;
        if (filledFor[cell] != build) {
            asked.setPos(originX + x, originY + y, originZ + z);
            IBlockState state = world.getBlockState(asked);
            // The four questions Forge's lighter asks of each cell, in the same words.
            clear[cell] = state.getLightOpacity(world, asked) < 15;
            int packed = state.getPackedLightmapCoords(world, asked);
            sky[cell] = (byte) (packed >> 20 & 0xF);
            block[cell] = (byte) (packed >> 4 & 0xF);
            occlusion[cell] = state.getAmbientOcclusionLightValue();
            filledFor[cell] = build;
        }
        return cell;
    }
}
