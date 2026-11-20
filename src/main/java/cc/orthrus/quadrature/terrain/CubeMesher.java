package cc.orthrus.quadrature.terrain;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.scene.LightScene;
import cc.orthrus.quadrature.core.terrain.AtlasRectangles;
import cc.orthrus.quadrature.core.terrain.CubeConnectivity;
import cc.orthrus.quadrature.core.terrain.FaceRecord;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import io.github.opencubicchunks.cubicchunks.api.client.IClientBuildWork;
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
 *
 * <p>A mesher belongs to one thread at a time: builds run on Cubature's build threads, each taking a
 * mesher from those idle. What a build makes is copied into a {@link BuiltCube}, so the mesher's
 * buffers are free again when it returns. What meshers share is numbered under locks: the atlas's
 * rectangles, and the material table's rows. The light scene's states are not written here; each
 * build hands back the states this mesher had not met, for the client thread to define.
 */
final class CubeMesher {

    private static final BlockRenderLayer[] LAYERS = {BlockRenderLayer.SOLID, BlockRenderLayer.CUTOUT_MIPPED,
        BlockRenderLayer.CUTOUT};
    private static final int QUADS = 7;

    private final BufferBuilder[] builders = new BufferBuilder[LAYERS.length];
    private final boolean[] started = new boolean[LAYERS.length];
    /** Whether a layer's buffer was begun and not yet finished: a build that threw leaves one so. */
    private final boolean[] drawing = new boolean[LAYERS.length];
    private final int[] counts = new int[LAYERS.length];
    /**
     * The translucent layer, where own terrain draws it ({@link TranslucentCube#OWN}): drawn by the game's dispatcher
     * alone, block by block in the order the game's chunk rebuild takes them, so its quads are the game's.
     */
    private final BufferBuilder translucent = TranslucentCube.OWN ? new BufferBuilder(1 << 16) : null;
    private boolean translucentStarted;
    private boolean translucentDrawing;
    private int translucentCount;
    private final long[] opaque = new long[64];
    /** Whether each cube's cells are noted for the light scene. */
    private final boolean keepCells;
    private final short[] cells = new short[LightScene.CELLS];
    /** The number of each block state this mesher has seen, so the registry is asked once for each. */
    private final Reference2IntOpenHashMap<IBlockState> stateNumbers = new Reference2IntOpenHashMap<>();
    /** The states first met in the cube being built, as {@link BuiltCube#packState} packs them. */
    private final IntArrayList newStates = new IntArrayList();
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
    /**
     * Whether a block drawn by its model is drawn through the mesher's own walk in plain vertex meshes as well,
     * which writes the same vertices as the game's dispatcher with one kept position for its face tests and none
     * copied for the consumer's offset (PLAN item 12). On unless {@code -Dquadrature.terrain.mesh=dispatcher};
     * the face check keeps the dispatcher, since it sets the walk beside it.
     */
    static final boolean WALK = !"dispatcher".equals(System.getProperty("quadrature.terrain.mesh")) && !FACE_CHECK && !FACES;
    private final QuadWalk walk = FACE_CHECK || FACES ? new QuadWalk(true) : WALK ? new QuadWalk(false) : null;
    /** What each build reads of the cubes around it. */
    private final SideReads sideReads = new SideReads();
    /**
     * A diagnostic, off unless {@code -Dquadrature.faces.count=true}: counts in every cube meshed the
     * quads that a record for a whole face could hold.
     */
    static final boolean CENSUS = Boolean.getBoolean("quadrature.faces.count");
    /**
     * Off unless {@code -Dquadrature.gbuffer=true}, which needs the face stream: kept vertices carry
     * their quad's header, and each block state's surface values are set the first time it is met.
     */
    static final boolean TARGETS = FACES && Boolean.getBoolean("quadrature.gbuffer");
    private final FaceBuilder faces;
    /** The material table every mesher fills, or null when it is not kept. */
    private final Materials materials;
    /** The states this mesher has asked the table to fill, so it takes the table's lock once for each. */
    private final java.util.BitSet materialsAsked = new java.util.BitSet(1 << 16);
    private final java.nio.ByteBuffer[] builtData = new java.nio.ByteBuffer[LAYERS.length];
    private final BufferBuilder[] walked = new BufferBuilder[LAYERS.length];
    private final boolean[] walkedStarted = new boolean[LAYERS.length];
    private final java.nio.ByteBuffer[] walkedData = new java.nio.ByteBuffer[LAYERS.length];
    private final int[] walkedVertices = new int[LAYERS.length];
    /** The face check's counts over the cube being built, by the indices below. */
    static final int CUBES_WALKED = 0, CUBES_DIFFERING = 1, LAYERS_DIFFERING = 2, BLOCKS_WALKED = 3,
        BLOCKS_LEFT_TO_THE_DISPATCHER = 4, CHECK_COUNTS = 5;
    private final long[] checkCounts = new long[CHECK_COUNTS];
    private int differencesLogged;
    private final CubeConnectivity connectivity = new CubeConnectivity();
    private int failures;
    /** The six cubes that share a face with a cube, as offsets. */
    private static final int[][] FACE_NEIGHBORS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    /**
     * @param keepCells  whether each cube's cells are noted for the light scene
     * @param materials  the material table every mesher fills, or null when it is not kept
     * @param rectangles the numbering of the atlas's rectangles every mesher shares
     */
    CubeMesher(boolean keepCells, Materials materials, AtlasRectangles rectangles) {
        this.keepCells = keepCells;
        this.materials = materials;
        this.faces = FACE_CHECK || FACES ? new FaceBuilder(TARGETS, rectangles) : null;
        stateNumbers.defaultReturnValue(-1);
        for (int i = 0; i < builders.length; i++) {
            builders[i] = new BufferBuilder(i == 0 ? 1 << 18 : 1 << 16);
        }
    }

    /**
     * What a cube's shape follows: the block versions of the cube and of the six that share a face
     * with it, which decide every face drawn and every face left out against a neighbor.
     */
    static long geometry(ICubeSnapshot snapshot) {
        long hash = snapshot.blockVersion(0, 0, 0);
        for (int[] d : FACE_NEIGHBORS) {
            hash = hash * 0x9E3779B97F4A7C15L + (snapshot.present(d[0], d[1], d[2]) ? snapshot.blockVersion(d[0], d[1], d[2]) : -1);
        }
        return hash;
    }

    /**
     * Meshes a cube and copies what it made out of this mesher's buffers.
     *
     * @param sequence the order the build was handed in, carried to the result
     * @param task     the pool's task, asked now and then whether to stop; null for a build on the client thread
     * @return the cube built, or null when the task stopped it
     */
    /**
     * The build threads' own CPU time over every build, and the builds counted: unlike a build's time on the clock it
     * leaves out waits for a core, so it compares runs whose other threads take more or less of the machine.
     */
    static final java.util.concurrent.atomic.LongAdder CPU_NANOS = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder CPU_BUILDS = new java.util.concurrent.atomic.LongAdder();
    /**
     * Under the face check, the time spent drawing blocks through the game's dispatcher and through the walk (with its
     * notes for face records, and the light matrix from the cube's table unless that is off): the same blocks, one
     * after the other on one thread, so the two compare with every other load on the machine the same for both.
     */
    static final java.util.concurrent.atomic.LongAdder CHECK_DISPATCHER_NANOS = new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder CHECK_WALK_NANOS = new java.util.concurrent.atomic.LongAdder();
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();

    BuiltCube build(ICubeSnapshot snapshot, int cubeX, int cubeY, int cubeZ, long sequence, IClientBuildWork.Task<?> task)
        throws InterruptedException {
        long cpuBegan = THREADS.getCurrentThreadCpuTime();
        try {
            return timedBuild(snapshot, cubeX, cubeY, cubeZ, sequence, task);
        } finally {
            CPU_NANOS.add(THREADS.getCurrentThreadCpuTime() - cpuBegan);
            CPU_BUILDS.increment();
        }
    }

    private BuiltCube timedBuild(ICubeSnapshot snapshot, int cubeX, int cubeY, int cubeZ, long sequence,
        IClientBuildWork.Task<?> task) throws InterruptedException {
        long began = System.nanoTime();
        long geometry = geometry(snapshot);
        if (snapshot.centerEmpty()) {
            // Nothing in it is drawn, whatever lies around it: it read nothing of its neighbors.
            return new BuiltCube(cubeX, cubeY, cubeZ, sequence, true, false, CubeConnectivity.ALL, geometry, null, null,
                null, NO_VERTICES, NO_COUNTS, null, null, null, null, null, System.nanoTime() - began, 0);
        }
        try {
            boolean any = mesh(snapshot, cubeX, cubeY, cubeZ, task);
            if (task != null && task.cancelled()) {
                return null;
            }
            return copy(cubeX, cubeY, cubeZ, sequence, any, geometry, System.nanoTime() - began);
        } finally {
            sideReads.end();
            done();
        }
    }

    private static final byte[][] NO_VERTICES = {new byte[0], new byte[0], new byte[0]};
    private static final int[] NO_COUNTS = new int[3];

    /** Copies what {@link #mesh} left in the buffers. */
    private BuiltCube copy(int cubeX, int cubeY, int cubeZ, long sequence, boolean any, long geometry, long nanos) {
        short links = connectivity.of(opaque);
        byte[][] vertices = new byte[LAYERS.length][];
        int[] vertexCounts = new int[LAYERS.length];
        int[][] records = null;
        int[] recordCounts = null;
        long[] faceCounts = null;
        if (FACES) {
            records = new int[LAYERS.length][];
            recordCounts = new int[LAYERS.length];
            for (int layer = 0; layer < LAYERS.length; layer++) {
                vertexCounts[layer] = faces.vertexCount(layer);
                java.nio.ByteBuffer kept = faces.vertices(layer).duplicate();
                kept.flip();
                vertices[layer] = new byte[kept.remaining()];
                kept.get(vertices[layer]);
                recordCounts[layer] = faces.recordCount(layer);
                records[layer] = java.util.Arrays.copyOf(faces.records(layer), recordCounts[layer] * FaceRecord.WORDS);
            }
            faceCounts = faces.counts().clone();
        } else {
            for (int layer = 0; layer < LAYERS.length; layer++) {
                vertexCounts[layer] = counts[layer];
                vertices[layer] = new byte[counts[layer] * CubeMesh.STRIDE];
                if (counts[layer] > 0) {
                    java.nio.ByteBuffer data = builders[layer].getByteBuffer().duplicate();
                    data.get(vertices[layer]);
                }
            }
            if (FACE_CHECK) {
                faceCounts = faces.counts().clone();
            }
        }
        long[] census = null;
        if (CENSUS) {
            census = new long[3];
            census(census);
        }
        BuiltCube built = new BuiltCube(cubeX, cubeY, cubeZ, sequence, false, any, links, geometry,
            keepCells ? cells.clone() : null, keepCells ? opaque.clone() : null, keepCells ? newStates.toIntArray() : null,
            vertices, vertexCounts, records, recordCounts, faceCounts, FACE_CHECK ? checkCounts.clone() : null, census, nanos,
            (long) sideReads.light() << 32 | sideReads.blocks() & 0xFFFF_FFFFL);
        if (translucentCount > 0) {
            byte[] bytes = new byte[translucentCount * CubeMesh.STRIDE];
            translucent.getByteBuffer().duplicate().get(bytes);
            built.translucent(bytes, translucentCount);
        }
        return built;
    }

    /**
     * Meshes a cube into this mesher's buffers.
     *
     * @return whether the cube draws anything in the opaque layers
     */
    private boolean mesh(ICubeSnapshot snapshot, int cubeX, int cubeY, int cubeZ, IClientBuildWork.Task<?> task)
        throws InterruptedException {
        BlockRendererDispatcher dispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
        int minX = cubeX << 4;
        int minY = cubeY << 4;
        int minZ = cubeZ << 4;
        // The models and lighters read the world through this, so what they read of the cubes around is known.
        SideReads world = sideReads.begin(snapshot, minX, minY, minZ);
        if (walk != null) {
            walk.beginCube(world, minX, minY, minZ);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        java.util.Arrays.fill(opaque, 0);
        java.util.Arrays.fill(checkCounts, 0);
        newStates.clear();
        try {
            for (int y = 0; y < 16; y++) {
                if (task != null && (y & 3) == 0) {
                    // A quarter of the cube at a time: a stale build stops, finishing its buffers as any
                    // build does, and a build waits here if the pool's permits were lowered.
                    if (task.cancelled()) {
                        break;
                    }
                    task.checkpoint();
                }
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        pos.setPos(minX + x, minY + y, minZ + z);
                        IBlockState state = snapshot.getBlockState(pos);
                        Block block = state.getBlock();
                        if (state.isOpaqueCube()) {
                            int cell = CubeConnectivity.cell(x, y, z);
                            opaque[cell >> 6] |= 1L << cell;
                        }
                        if (keepCells) {
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
                                drawing[layer] = true;
                                builders[layer].begin(QUADS, DefaultVertexFormats.BLOCK);
                                builders[layer].setTranslation(-minX, -minY, -minZ);
                            }
                            try {
                                if (materials != null) {
                                    material(state);
                                }
                                if (FACES && walk.usable() && state.getRenderType() == EnumBlockRenderType.MODEL) {
                                    // The dispatcher takes a block's actual state before it asks for the model.
                                    walk.render(dispatcher, world, state.getActualState(world, pos), pos, builders[layer],
                                        LightScene.index(x, y, z), layer, Block.getStateId(state) & 0xFFFF);
                                } else if (FACES) {
                                    // Not a model: what is drawn is still noted, with the block's state.
                                    int from = builders[layer].getVertexCount();
                                    dispatcher.renderBlock(state, pos, world, builders[layer]);
                                    walk.foreign(builders[layer], from, LightScene.index(x, y, z), layer, Block.getStateId(state) & 0xFFFF);
                                } else if (WALK && walk.usable() && state.getRenderType() == EnumBlockRenderType.MODEL) {
                                    // The state number is noted only for face records, which this walk does not keep.
                                    walk.render(dispatcher, world, state.getActualState(world, pos), pos, builders[layer],
                                        LightScene.index(x, y, z), layer, 0);
                                } else if (FACE_CHECK) {
                                    long began = System.nanoTime();
                                    dispatcher.renderBlock(state, pos, world, builders[layer]);
                                    CHECK_DISPATCHER_NANOS.add(System.nanoTime() - began);
                                } else {
                                    dispatcher.renderBlock(state, pos, world, builders[layer]);
                                }
                            } catch (RuntimeException e) {
                                if (failures++ < 5) {
                                    Quadrature.LOGGER.error("A block model failed for {} at {}", state, pos, e);
                                }
                            }
                            if (FACE_CHECK) {
                                long began = System.nanoTime();
                                walk(dispatcher, world, state, pos, layer, LightScene.index(x, y, z), minX, minY, minZ);
                                CHECK_WALK_NANOS.add(System.nanoTime() - began);
                            }
                        }
                        if (translucent != null && block.canRenderInLayer(state, BlockRenderLayer.TRANSLUCENT)) {
                            ForgeHooksClient.setRenderLayer(BlockRenderLayer.TRANSLUCENT);
                            if (!translucentStarted) {
                                translucentStarted = true;
                                translucentDrawing = true;
                                translucent.begin(QUADS, DefaultVertexFormats.BLOCK);
                                translucent.setTranslation(-minX, -minY, -minZ);
                            }
                            try {
                                dispatcher.renderBlock(state, pos, world, translucent);
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
            if (walk != null) {
                walk.endCube();
            }
        }
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
            drawing[layer] = false;
            counts[layer] = builders[layer].getVertexCount();
            any |= counts[layer] > 0;
        }
        translucentCount = 0;
        if (translucentStarted) {
            translucent.finishDrawing();
            translucentDrawing = false;
            translucentCount = translucent.getVertexCount();
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
                checkCounts[BLOCKS_WALKED]++;
            } else {
                int from = walked[layer].getVertexCount();
                dispatcher.renderBlock(state, pos, snapshot, walked[layer]);
                walk.foreign(walked[layer], from, cell, layer, Block.getStateId(state) & 0xFFFF);
                checkCounts[BLOCKS_LEFT_TO_THE_DISPATCHER]++;
            }
        } catch (RuntimeException e) {
            if (failures++ < 5) {
                Quadrature.LOGGER.error("The walk failed for {} at {}", state, pos, e);
            }
        }
    }

    /** Sets what the walk wrote for the cube beside what the dispatcher wrote, and sorts the walk's quads. */
    private void compare(int cubeX, int cubeY, int cubeZ) {
        checkCounts[CUBES_WALKED]++;
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
                checkCounts[LAYERS_DIFFERING]++;
                if (differencesLogged++ < 10) {
                    Quadrature.LOGGER.warn("Face check: cube {} {} {} layer {}: the walk wrote {} vertices and the "
                        + "dispatcher {}, or their bytes differ", cubeX, cubeY, cubeZ, LAYERS[layer], walkedVertices[layer], game);
                }
            }
        }
        if (differs) {
            checkCounts[CUBES_DIFFERING]++;
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

    /**
     * The number a state's row of the material table has, the row filled if no mesher has met the
     * state before. Without a table, only the number.
     */
    int material(IBlockState state) {
        int number = Block.getStateId(state) & 0xFFFF;
        if (materials != null && !materialsAsked.get(number)) {
            materialsAsked.set(number);
            materials.defineOnce(state, number);
        }
        return number;
    }

    /** What the face check's counts say, summed over every build. */
    static String checkSummary(long[] totals, long[] faceTotals, int rectangles) {
        return String.format("face check: %d cubes drawn both ways, %d differ in %d layers; %d blocks walked, %d left to "
                + "the dispatcher; %s", totals[CUBES_WALKED], totals[CUBES_DIFFERING], totals[LAYERS_DIFFERING],
            totals[BLOCKS_WALKED], totals[BLOCKS_LEFT_TO_THE_DISPATCHER], FaceBuilder.summary(faceTotals, rectangles));
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
            // The client thread tells the scene, before it puts the cube's cells there.
            newStates.add(BuiltCube.packState(number, Math.min(15, Math.max(0, state.getLightValue())),
                Math.min(15, Math.max(0, state.getLightOpacity())), state.isOpaqueCube()));
        }
        return number;
    }

    /**
     * A diagnostic: counts, in the cube last built, the quads that are an axis-aligned square of one
     * cell on a cell boundary, and of those the ones whose texture coordinates take two values each way,
     * which a record for a whole face could hold.
     *
     * @param totals added to: quads, unit faces, unit faces with rectangular texture coordinates
     */
    private void census(long[] totals) {
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

    /**
     * Makes the buffers ready for the next cube, however the last build ended: one that stopped early
     * or threw leaves buffers begun and notes of quads taken.
     */
    private void done() {
        for (int layer = 0; layer < LAYERS.length; layer++) {
            if (started[layer]) {
                if (drawing[layer]) {
                    builders[layer].finishDrawing();
                    drawing[layer] = false;
                }
                started[layer] = false;
                builders[layer].setTranslation(0, 0, 0);
                builders[layer].reset();
            }
            counts[layer] = 0;
            if (walkedStarted[layer]) {
                walkedStarted[layer] = false;
                walked[layer].finishDrawing();
                walked[layer].setTranslation(0, 0, 0);
                walked[layer].reset();
            }
        }
        if (translucentStarted) {
            if (translucentDrawing) {
                translucent.finishDrawing();
                translucentDrawing = false;
            }
            translucentStarted = false;
            translucent.setTranslation(0, 0, 0);
            translucent.reset();
        }
        translucentCount = 0;
        if (walk != null) {
            walk.begin();
        }
    }
}