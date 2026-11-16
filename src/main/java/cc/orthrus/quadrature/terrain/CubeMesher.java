package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.scene.LightScene;
import cc.orthrus.quadrature.core.terrain.CubeConnectivity;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import io.github.opencubicchunks.cubicchunks.api.client.ICubeSnapshot;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.ForgeHooksClient;

/**
 * Turns a snapshot into a mesh by running the game's block models against it, the way the game's
 * own chunk builder does: each block is offered to each layer it draws in.
 */
final class CubeMesher {

    private static final BlockRenderLayer[] LAYERS = {BlockRenderLayer.SOLID, BlockRenderLayer.CUTOUT_MIPPED,
        BlockRenderLayer.CUTOUT};
    private static final int QUADS = 7;

    private final BufferBuilder[] builders = new BufferBuilder[LAYERS.length];
    private final boolean[] started = new boolean[LAYERS.length];
    private final int[] counts = new int[LAYERS.length];
    private final long[] opaque = new long[64];
    /** The scene the cells are noted for, or null when none is kept. */
    private final LightScene scene;
    private final short[] cells = new short[LightScene.CELLS];
    /** The number of each block state seen, so the registry is asked once for each. */
    private final Reference2IntOpenHashMap<IBlockState> stateNumbers = new Reference2IntOpenHashMap<>();
    private int unnumbered;
    private final CubeConnectivity connectivity = new CubeConnectivity();
    private short links;
    private int failures;

    CubeMesher() {
        this(null);
    }

    /** @param scene where each cube's cells are noted as it is meshed, or null */
    CubeMesher(LightScene scene) {
        this.scene = scene;
        stateNumbers.defaultReturnValue(-1);
        for (int i = 0; i < builders.length; i++) {
            builders[i] = new BufferBuilder(i == 0 ? 1 << 18 : 1 << 16);
        }
    }

    /** Which faces of the cube last built see each other through it, as {@link CubeConnectivity} gives them. */
    short links() {
        return links;
    }

    /**
     * Meshes a cube into this mesher's buffers, where the result stays until {@link #done}.
     *
     * @return whether the cube draws anything in the opaque layers
     */
    boolean build(ICubeSnapshot snapshot, int cubeX, int cubeY, int cubeZ) {
        BlockRendererDispatcher dispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
        int minX = cubeX << 4;
        int minY = cubeY << 4;
        int minZ = cubeZ << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        java.util.Arrays.fill(opaque, 0);
        try {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        pos.setPos(minX + x, minY + y, minZ + z);
                        IBlockState state = snapshot.getBlockState(pos);
                        Block block = state.getBlock();
                        if (state.isOpaqueCube()) {
                            int cell = CubeConnectivity.cell(x, y, z);
                            opaque[cell >> 6] |= 1L << cell;
                        }
                        if (scene != null) {
                            cells[LightScene.index(x, y, z)] = (short) number(state);
                        }
                        if (state.getRenderType() == EnumBlockRenderType.INVISIBLE) {
                            continue;
                        }
                        for (int layer = 0; layer < LAYERS.length; layer++) {
                            if (!block.canRenderInLayer(state, LAYERS[layer])) {
                                continue;
                            }
                            ForgeHooksClient.setRenderLayer(LAYERS[layer]);
                            if (!started[layer]) {
                                started[layer] = true;
                                builders[layer].begin(QUADS, DefaultVertexFormats.BLOCK);
                                builders[layer].setTranslation(-minX, -minY, -minZ);
                            }
                            try {
                                dispatcher.renderBlock(state, pos, snapshot, builders[layer]);
                            } catch (RuntimeException e) {
                                if (failures++ < 5) {
                                    Quadrature.LOGGER.error("A block model failed for {} at {}", state, pos, e);
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            ForgeHooksClient.setRenderLayer(null);
        }
        links = connectivity.of(opaque);
        boolean any = false;
        for (int layer = 0; layer < LAYERS.length; layer++) {
            counts[layer] = 0;
            if (!started[layer]) {
                continue;
            }
            builders[layer].finishDrawing();
            counts[layer] = builders[layer].getVertexCount();
            any |= counts[layer] > 0;
        }
        return any;
    }

    /**
     * The number a block state goes by in the scene: the game's own for it, the block's identity and
     * its variant. The first time a state is seen the scene is told what it does to light, by the
     * state's values alone; a block whose light depends on where it stands is taken at those values.
     */
    private int number(IBlockState state) {
        int number = stateNumbers.getInt(state);
        if (number < 0) {
            number = Block.getStateId(state);
            if (number < 0 || number >= LightScene.STATES) {
                // More blocks than sixteen bits name: such a state is kept as something solid and dark.
                if (unnumbered++ == 0) {
                    Quadrature.LOGGER.warn("A block state's number, {}, does not fit the light scene: {}", number, state);
                }
                number = LightScene.STATES - 1;
            }
            stateNumbers.put(state, number);
            scene.defineState(number, Math.min(15, Math.max(0, state.getLightValue())),
                Math.min(15, Math.max(0, state.getLightOpacity())), state.isOpaqueCube());
        }
        return number;
    }

    /** The state of each cell of the cube last built, as the scene orders them. */
    short[] cells() {
        return cells;
    }

    /** A bit for each cell of the cube last built that an opaque whole cube fills. */
    long[] blocking() {
        return opaque;
    }

    /** The vertices of a layer of the cube last built: its position and the count bound them. */
    java.nio.ByteBuffer data(int layer) {
        return builders[layer].getByteBuffer();
    }

    /** How many vertices a layer of the cube last built has. */
    int count(int layer) {
        return counts[layer];
    }

    /** Makes the buffers ready for the next cube. Call once what {@link #build} left has been stored. */
    void done() {
        for (int layer = 0; layer < LAYERS.length; layer++) {
            if (started[layer]) {
                started[layer] = false;
                builders[layer].setTranslation(0, 0, 0);
                builders[layer].reset();
            }
            counts[layer] = 0;
        }
    }
}