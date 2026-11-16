package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.scene.LightScene;
import cc.orthrus.quadrature.core.terrain.CubeConnectivity;

import net.minecraft.client.renderer.BlockRendererDispatcher;

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

    /**
     * A diagnostic, off unless {@code -Dquadrature.faces.check=true}: every cube is also drawn by the
     * mesher's own walk over each model's quads, into buffers that are not drawn from; what the walk
     * wrote is set beside what the game's dispatcher wrote, byte for byte, and sorted into face records.
     */
    static final boolean FACE_CHECK = Boolean.getBoolean("quadrature.faces.check") && !Boolean.getBoolean("quadrature.faces");
    /**
     * Off unless {@code -Dquadrature.faces=true}: a block drawn by its model is drawn through the
     * mesher's own walk, and the cube's quads are sorted into face records and kept vertices.
     */
    static final boolean FACES = Boolean.getBoolean("quadrature.faces");
    private final QuadWalk walk = FACE_CHECK || FACES ? new QuadWalk() : null;
    private final FaceBuilder faces = FACE_CHECK || FACES ? new FaceBuilder() : null;
    private final java.nio.ByteBuffer[] builtData = new java.nio.ByteBuffer[LAYERS.length];
    private final BufferBuilder[] walked = new BufferBuilder[LAYERS.length];
    private final boolean[] walkedStarted = new boolean[LAYERS.length];
    private final java.nio.ByteBuffer[] walkedData = new java.nio.ByteBuffer[LAYERS.length];
    private final int[] walkedVertices = new int[LAYERS.length];
    private long cubesWalked;
    private long cubesDiffering;
    private long layersDiffering;
    private long blocksWalked;
    private long blocksLeftToTheDispatcher;
    private int differencesLogged;
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
                                if (FACES && walk.usable() && state.getRenderType() == EnumBlockRenderType.MODEL) {
                                    // The dispatcher takes a block's actual state before it asks for the model.
                                    walk.render(dispatcher, snapshot, state.getActualState(snapshot, pos), pos, builders[layer],
                                        LightScene.index(x, y, z), layer, Block.getStateId(state) & 0xFFFF);
                                } else {
                                    dispatcher.renderBlock(state, pos, snapshot, builders[layer]);
                                }
                            } catch (RuntimeException e) {
                                if (failures++ < 5) {
                                    Quadrature.LOGGER.error("A block model failed for {} at {}", state, pos, e);
                                }
                            }
                            if (FACE_CHECK) {
                                walk(dispatcher, snapshot, state, pos, layer, LightScene.index(x, y, z), minX, minY, minZ);
                            }
                        }
                    }
                }
            }
        } finally {
            ForgeHooksClient.setRenderLayer(null);
        }
        links = connectivity.of(opaque);
        if (FACE_CHECK) {
            compare(cubeX, cubeY, cubeZ);
        }
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
        if (FACES) {
            for (int layer = 0; layer < LAYERS.length; layer++) {
                builtData[layer] = counts[layer] == 0 ? null : builders[layer].getByteBuffer();
            }
            faces.build(builtData, counts, walk);
            walk.begin();
        }
        return any;
    }

    /** Draws a block's layer a second time, by the walk where the game would light a model through its pipeline. */
    private void walk(BlockRendererDispatcher dispatcher, ICubeSnapshot snapshot, IBlockState state, BlockPos pos,
        int layer, int cell, int minX, int minY, int minZ) {
        if (walked[layer] == null) {
            walked[layer] = new BufferBuilder(1 << 16);
        }
        if (!walkedStarted[layer]) {
            walkedStarted[layer] = true;
            walked[layer].begin(QUADS, DefaultVertexFormats.BLOCK);
            walked[layer].setTranslation(-minX, -minY, -minZ);
        }
        try {
            if (walk.usable() && state.getRenderType() == EnumBlockRenderType.MODEL) {
                // The dispatcher takes a block's actual state before it asks for the model.
                IBlockState actual = state.getActualState(snapshot, pos);
                walk.render(dispatcher, snapshot, actual, pos, walked[layer], cell, layer, Block.getStateId(state) & 0xFFFF);
                blocksWalked++;
            } else {
                dispatcher.renderBlock(state, pos, snapshot, walked[layer]);
                blocksLeftToTheDispatcher++;
            }
        } catch (RuntimeException e) {
            if (failures++ < 5) {
                Quadrature.LOGGER.error("The walk failed for {} at {}", state, pos, e);
            }
        }
    }

    /** Sets what the walk wrote for the cube beside what the dispatcher wrote, and sorts the walk's quads. */
    private void compare(int cubeX, int cubeY, int cubeZ) {
        cubesWalked++;
        boolean differs = false;
        for (int layer = 0; layer < LAYERS.length; layer++) {
            walkedVertices[layer] = 0;
            walkedData[layer] = null;
            int game = 0;
            java.nio.ByteBuffer gameData = null;
            if (started[layer]) {
                // Finishing is left to build(); the bytes written so far are what is compared.
                game = builders[layer].getVertexCount();
                gameData = builders[layer].getByteBuffer();
            }
            if (walkedStarted[layer]) {
                walkedVertices[layer] = walked[layer].getVertexCount();
                walkedData[layer] = walked[layer].getByteBuffer();
            }
            boolean same = game == walkedVertices[layer];
            for (int i = 0, bytes = game * CubeMesh.STRIDE; same && i < bytes; i++) {
                same = gameData.get(i) == walkedData[layer].get(i);
            }
            if (!same) {
                differs = true;
                layersDiffering++;
                if (differencesLogged++ < 10) {
                    Quadrature.LOGGER.warn("Face check: cube {} {} {} layer {}: the walk wrote {} vertices and the "
                        + "dispatcher {}, or their bytes differ", cubeX, cubeY, cubeZ, LAYERS[layer], walkedVertices[layer], game);
                }
            }
        }
        if (differs) {
            cubesDiffering++;
        }
        faces.build(walkedData, walkedVertices, walk);
        walk.begin();
        for (int layer = 0; layer < LAYERS.length; layer++) {
            if (walkedStarted[layer]) {
                walkedStarted[layer] = false;
                walked[layer].finishDrawing();
                walked[layer].setTranslation(0, 0, 0);
                walked[layer].reset();
            }
        }
    }

    /** The quads of the cube last built, sorted into face records and kept vertices. */
    FaceBuilder faceBuilder() {
        return faces;
    }

    /** What the face check found, for the timing log. */
    String faceSummary() {
        if (FACES) {
            return "faces: " + faces.summary();
        }
        return String.format("face check: %d cubes drawn both ways, %d differ in %d layers; %d blocks walked, %d left to "
                + "the dispatcher; %s", cubesWalked, cubesDiffering, layersDiffering, blocksWalked, blocksLeftToTheDispatcher,
            faces.summary());
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

    /**
     * A diagnostic: counts, in the cube last built, the quads that are an axis-aligned square of one
     * cell on a cell boundary, and of those the ones whose texture coordinates take two values each way,
     * which a record for a whole face could hold.
     *
     * @param totals added to: quads, unit faces, unit faces with rectangular texture coordinates
     */
    void census(long[] totals) {
        float[] p = new float[12];
        float[] t = new float[8];
        for (int layer = 0; layer < LAYERS.length; layer++) {
            java.nio.ByteBuffer data = builders[layer].getByteBuffer();
            int start = data.position();
            for (int quad = 0; quad < counts[layer] / 4; quad++) {
                for (int vertex = 0; vertex < 4; vertex++) {
                    int at = start + (quad * 4 + vertex) * CubeMesh.STRIDE;
                    p[vertex * 3] = data.getFloat(at);
                    p[vertex * 3 + 1] = data.getFloat(at + 4);
                    p[vertex * 3 + 2] = data.getFloat(at + 8);
                    t[vertex * 2] = data.getFloat(at + 16);
                    t[vertex * 2 + 1] = data.getFloat(at + 20);
                }
                totals[0]++;
                boolean unit = false;
                for (int axis = 0; axis < 3 && !unit; axis++) {
                    int a = (axis + 1) % 3;
                    int b = (axis + 2) % 3;
                    boolean flat = true;
                    boolean whole = p[axis] == Math.rint(p[axis]);
                    float minA = p[a];
                    float maxA = p[a];
                    float minB = p[b];
                    float maxB = p[b];
                    for (int vertex = 1; vertex < 4; vertex++) {
                        flat &= p[vertex * 3 + axis] == p[axis];
                        minA = Math.min(minA, p[vertex * 3 + a]);
                        maxA = Math.max(maxA, p[vertex * 3 + a]);
                        minB = Math.min(minB, p[vertex * 3 + b]);
                        maxB = Math.max(maxB, p[vertex * 3 + b]);
                    }
                    boolean corners = true;
                    for (int vertex = 0; vertex < 4 && corners; vertex++) {
                        float va = p[vertex * 3 + a];
                        float vb = p[vertex * 3 + b];
                        corners = (va == minA || va == maxA) && (vb == minB || vb == maxB);
                    }
                    unit = flat && whole && corners && maxA - minA == 1 && maxB - minB == 1 && minA == Math.rint(minA)
                        && minB == Math.rint(minB);
                }
                if (!unit) {
                    continue;
                }
                totals[1]++;
                float minU = Math.min(Math.min(t[0], t[2]), Math.min(t[4], t[6]));
                float maxU = Math.max(Math.max(t[0], t[2]), Math.max(t[4], t[6]));
                float minV = Math.min(Math.min(t[1], t[3]), Math.min(t[5], t[7]));
                float maxV = Math.max(Math.max(t[1], t[3]), Math.max(t[5], t[7]));
                boolean rectangular = minU != maxU && minV != maxV;
                for (int vertex = 0; vertex < 4 && rectangular; vertex++) {
                    rectangular = (t[vertex * 2] == minU || t[vertex * 2] == maxU)
                        && (t[vertex * 2 + 1] == minV || t[vertex * 2 + 1] == maxV);
                }
                if (rectangular) {
                    totals[2]++;
                }
            }
        }
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