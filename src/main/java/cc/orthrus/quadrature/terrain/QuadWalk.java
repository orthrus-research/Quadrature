package cc.orthrus.quadrature.terrain;

import net.minecraft.block.Block;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import cc.orthrus.quadrature.core.terrain.FaceRecord;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.model.pipeline.ForgeBlockModelRenderer;
import net.minecraftforge.client.model.pipeline.VertexBufferConsumer;
import net.minecraftforge.client.model.pipeline.VertexLighterFlat;
import net.minecraftforge.client.model.pipeline.VertexLighterSmoothAo;
import net.minecraftforge.common.ForgeModContainer;

import java.util.List;

/**
 * Draws a block's model into a buffer as the game does, and says which quad each four vertices came
 * from: its sprite, its face, its tint index and whether the game shades it by the way it is turned.
 * The game's own path writes the vertices and keeps none of that.
 *
 * <p>The vertices are the game's: each quad is handed to the game's lighter, which writes it through
 * the game's consumer into the buffer. Only the walk over the model's quads is done here, in the order
 * the game's block model renderer takes them: the quads of no side, then each side's in turn where
 * that side of the block is to be drawn.
 *
 * <p>This stands in for the game's dispatcher only where the game would itself take that path: a block
 * drawn by its model, the game's block model renderer in place, and its lighting pipeline on. Anything
 * else is left to the dispatcher, and its quads come without a note.
 *
 * <p>Two things the game's path makes anew for every block are not made here, since the walk runs for
 * every block of every cube on Cubature's build threads: the position of the neighbor a face is tested
 * against, for blocks that keep the game's own face test (see {@link #drawsSide}), and the copy of the
 * block's position the game's consumer keeps as its offset, which the walk puts in the buffer's
 * translation instead. The vertices written are the same to the bit.
 */
final class QuadWalk {

    private static final EnumFacing[] SIDES = EnumFacing.values();
    /** The face test's names: as the development environment names them, and as the game runs. */
    private static final String BLOCK_TEST = runtimeName(Block.class, "shouldSideBeRendered", "func_176225_a",
        IBlockState.class, IBlockAccess.class, BlockPos.class, EnumFacing.class);
    private static final String STATE_TEST = runtimeName(BlockStateContainer.StateImplementation.class,
        "shouldSideBeRendered", "func_185894_c", IBlockAccess.class, BlockPos.class, EnumFacing.class);
    /** Whether a class of block keeps the game's own face test. */
    private static final ClassValue<Boolean> BLOCK_KEEPS_TEST = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return declaredBy(type, Block.class, BLOCK_TEST, IBlockState.class, IBlockAccess.class, BlockPos.class,
                EnumFacing.class);
        }
    };
    /** Whether a class of block state hands the face test to its block, as the game's own states do. */
    private static final ClassValue<Boolean> STATE_KEEPS_TEST = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return declaredBy(type, BlockStateContainer.StateImplementation.class, STATE_TEST, IBlockAccess.class,
                BlockPos.class, EnumFacing.class);
        }
    };
    /** The neighbor a face is tested against, set for each test. */
    private final BlockPos.MutableBlockPos beside = new BlockPos.MutableBlockPos();

    private final VertexLighterFlat flat;
    private final VertexLighterSmoothAo smooth;
    private final VertexBufferConsumer consumer = new VertexBufferConsumer();
    private final boolean usable;
    /** Whether each quad drawn is noted for face records; a walk for plain vertex meshes notes nothing. */
    private final boolean notes;

    /** The quads noted since {@link #begin}, in the order their vertices were written. */
    private BakedQuad[] quads = new BakedQuad[256];
    /** For each, the first of its four vertices in its buffer. */
    private int[] firstVertex = new int[256];
    /** For each, the cell of the cube its block is in, as the scene orders cells. */
    private int[] cells = new int[256];
    private byte[] layers = new byte[256];
    private int[] states = new int[256];
    private int[] tints = new int[256];
    private byte[] flags = new byte[256];
    private byte[] faces = new byte[256];
    private byte[] fronts = new byte[256];

    /**
     * {@code -Dquadrature.gbuffer.audit=true}: counts the occlusion the targets are given, by the way a
     * face is turned and by what stands in front of it.
     */
    static final boolean AUDIT = Boolean.getBoolean("quadrature.gbuffer.audit");
    static final cc.orthrus.quadrature.core.material.OcclusionAudit OCCLUSION =
        AUDIT ? new cc.orthrus.quadrature.core.material.OcclusionAudit() : null;
    private int count;
    private final BlockColors colors;
    private IBlockAccess world;
    private BlockPos pos;
    private IBlockState state;
    private boolean occluded;
    private int layer;
    private int stateNumber;

    /** @param notes whether each quad drawn is noted for face records ({@link #count} and the rest) */
    QuadWalk(boolean notes) {
        this.notes = notes;
        Minecraft minecraft = Minecraft.getMinecraft();
        BlockRendererDispatcher dispatcher = minecraft.getBlockRendererDispatcher();
        usable = dispatcher.getBlockModelRenderer().getClass() == ForgeBlockModelRenderer.class;
        colors = minecraft.getBlockColors();
        flat = new VertexLighterFlat(minecraft.getBlockColors());
        smooth = TableLighter.ON ? new TableLighter(minecraft.getBlockColors()) : new VertexLighterSmoothAo(minecraft.getBlockColors());
    }

    /** Starts a cube: the smooth lighter's table of the light around its blocks starts empty. */
    void beginCube(IBlockAccess world, int minX, int minY, int minZ) {
        if (smooth instanceof TableLighter table) {
            table.beginCube(world, minX, minY, minZ);
        }
    }

    /** Ends a cube, letting go of its world. */
    void endCube() {
        if (smooth instanceof TableLighter table) {
            table.endCube();
        }
    }

    /** Whether the game would light a model through its pipeline, so that this may stand in for it. */
    boolean usable() {
        return usable && ForgeModContainer.forgeLightPipelineEnabled;
    }

    /** Forgets the quads noted: call before a layer of a cube is begun. */
    void begin() {
        count = 0;
    }

    /**
     * Draws a block's model into a buffer, noting each quad.
     *
     * @param state the block's state as the dispatcher would have it: its actual state
     * @param cell  where in its cube the block is
     * @return whether anything was drawn
     */
    boolean render(BlockRendererDispatcher dispatcher, IBlockAccess world, IBlockState state, BlockPos pos,
        BufferBuilder buffer, int cell, int layer, int stateNumber) {
        this.world = world;
        this.pos = pos;
        this.layer = layer;
        this.stateNumber = stateNumber;
        IBakedModel model = dispatcher.getModelForState(state);
        state = state.getBlock().getExtendedState(state, world, pos);
        boolean occluded = Minecraft.isAmbientOcclusionEnabled() && state.getLightValue(world, pos) == 0
            && model.isAmbientOcclusion(state);
        VertexLighterFlat lighter = occluded ? smooth : flat;
        this.state = state;
        this.occluded = occluded;
        consumer.setBuffer(buffer);
        // The consumer's offset stays at the origin; the block's place in its cube goes in the buffer's
        // translation for the block, and the cube's back after it. The game adds the two before the
        // vertex's own coordinate, so the sum, and the vertex, are the same.
        int cellX = cell & 15;
        int cellY = cell >> 8 & 15;
        int cellZ = cell >> 4 & 15;
        buffer.setTranslation(cellX, cellY, cellZ);
        lighter.setParent(consumer);
        long random = MathHelper.getPositionRandom(pos);
        lighter.setWorld(world);
        lighter.setState(state);
        lighter.setBlockPos(pos);
        boolean empty = true;
        List<BakedQuad> list = model.getQuads(state, null, random);
        if (!list.isEmpty()) {
            lighter.updateBlockInfo();
            empty = false;
            pipe(list, lighter, buffer, cell);
        }
        for (EnumFacing side : SIDES) {
            list = model.getQuads(state, side, random);
            if (!list.isEmpty() && drawsSide(state, world, pos, side)) {
                if (empty) {
                    lighter.updateBlockInfo();
                }
                empty = false;
                pipe(list, lighter, buffer, cell);
            }
        }
        lighter.resetBlockInfo();
        buffer.setTranslation(cellX - pos.getX(), cellY - pos.getY(), cellZ - pos.getZ());
        return !empty;
    }

    /**
     * The game's test of whether a side of a block is drawn. A block that keeps the game's own test, in
     * a state that hands it to its block, is tested here as the game's test does it: a side its bounds
     * stop short of is drawn, and otherwise one the neighbor does not block. The game's test makes two
     * positions for the neighbor; this sets one it keeps. Any other block is asked as the game asks it.
     */
    private boolean drawsSide(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        if (!STATE_KEEPS_TEST.get(state.getClass()) || !BLOCK_KEEPS_TEST.get(state.getBlock().getClass())) {
            return state.shouldSideBeRendered(world, pos, side);
        }
        AxisAlignedBB bounds = state.getBoundingBox(world, pos);
        switch (side) {
            case DOWN:
                if (bounds.minY > 0.0) {
                    return true;
                }
                break;
            case UP:
                if (bounds.maxY < 1.0) {
                    return true;
                }
                break;
            case NORTH:
                if (bounds.minZ > 0.0) {
                    return true;
                }
                break;
            case SOUTH:
                if (bounds.maxZ < 1.0) {
                    return true;
                }
                break;
            case WEST:
                if (bounds.minX > 0.0) {
                    return true;
                }
                break;
            case EAST:
                if (bounds.maxX < 1.0) {
                    return true;
                }
                break;
            default:
                break;
        }
        beside.setPos(pos).move(side);
        return !world.getBlockState(beside).doesSideBlockRendering(world, beside, side.getOpposite());
    }

    /** The name a method goes by where the game runs: the development name if it has one, else the game's own. */
    private static String runtimeName(Class<?> owner, String development, String game, Class<?>... parameters) {
        try {
            owner.getMethod(development, parameters);
            return development;
        } catch (NoSuchMethodException running) {
            return game;
        }
    }

    /** Whether a class's public method of this name and parameters is the one declared by the owner. */
    private static boolean declaredBy(Class<?> type, Class<?> owner, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters).getDeclaringClass() == owner;
        } catch (NoSuchMethodException | SecurityException unknown) {
            return false;
        }
    }

    private void pipe(List<BakedQuad> list, VertexLighterFlat lighter, BufferBuilder buffer, int cell) {
        if (!notes) {
            for (int i = 0, size = list.size(); i < size; i++) {
                list.get(i).pipe(lighter);
            }
            return;
        }
        for (int i = 0, size = list.size(); i < size; i++) {
            BakedQuad quad = list.get(i);
            int before = buffer.getVertexCount();
            quad.pipe(lighter);
            if (buffer.getVertexCount() != before + 4) {
                // A quad the lighter left out, or one that is no quad: nothing to note.
                continue;
            }
            if (count == quads.length) {
                quads = java.util.Arrays.copyOf(quads, count * 2);
                firstVertex = java.util.Arrays.copyOf(firstVertex, count * 2);
                cells = java.util.Arrays.copyOf(cells, count * 2);
                layers = java.util.Arrays.copyOf(layers, count * 2);
                states = java.util.Arrays.copyOf(states, count * 2);
                tints = java.util.Arrays.copyOf(tints, count * 2);
                flags = java.util.Arrays.copyOf(flags, count * 2);
                faces = java.util.Arrays.copyOf(faces, count * 2);
                fronts = java.util.Arrays.copyOf(fronts, count * 2);
            }
            quads[count] = quad;
            firstVertex[count] = before;
            layers[count] = (byte) layer;
            states[count] = stateNumber;
            int flag = (occluded ? FaceRecord.OCCLUDED : 0) | (quad.shouldApplyDiffuseLighting() ? FaceRecord.SHADED : 0);
            int tint = 0xFFFFFF;
            if (quad.hasTintIndex()) {
                // The same question the game's lighter asks for this quad, so the same answer.
                tint = colors.colorMultiplier(state, world, pos, quad.getTintIndex()) & 0xFFFFFF;
                flag |= FaceRecord.TINTED;
            }
            tints[count] = tint;
            flags[count] = (byte) flag;
            EnumFacing face = quad.getFace();
            faces[count] = (byte) (face == null ? -1 : face.ordinal());
            if (AUDIT && face != null) {
                // What the game's lighter is told of the cell in front: whether it darkens its neighbors.
                IBlockState ahead = world.getBlockState(pos.offset(face));
                fronts[count] = (byte) (ahead.getAmbientOcclusionLightValue() >= 1f
                    ? cc.orthrus.quadrature.core.material.OcclusionAudit.FRONT_OPEN
                    : ahead.getMaterial() == net.minecraft.block.material.Material.LEAVES
                        ? cc.orthrus.quadrature.core.material.OcclusionAudit.FRONT_FOLIAGE
                        : cc.orthrus.quadrature.core.material.OcclusionAudit.FRONT_OTHER);
            }
            cells[count++] = cell;
        }
    }

    /**
     * Notes the quads something other than a model wrote, a fluid's or those of a block drawn by a
     * renderer of its own: they have no quad of a model, no tint and no face, only the state of the
     * block they belong to, so that what is drawn of them can be given that state's surface.
     *
     * @param from the buffer's count of vertices before the block was drawn
     */
    void foreign(BufferBuilder buffer, int from, int cell, int layer, int stateNumber) {
        for (int vertex = from; vertex + 4 <= buffer.getVertexCount(); vertex += 4) {
            if (count == quads.length) {
                quads = java.util.Arrays.copyOf(quads, count * 2);
                firstVertex = java.util.Arrays.copyOf(firstVertex, count * 2);
                cells = java.util.Arrays.copyOf(cells, count * 2);
                layers = java.util.Arrays.copyOf(layers, count * 2);
                states = java.util.Arrays.copyOf(states, count * 2);
                tints = java.util.Arrays.copyOf(tints, count * 2);
                flags = java.util.Arrays.copyOf(flags, count * 2);
                faces = java.util.Arrays.copyOf(faces, count * 2);
                fronts = java.util.Arrays.copyOf(fronts, count * 2);
            }
            quads[count] = null;
            firstVertex[count] = vertex;
            layers[count] = (byte) layer;
            states[count] = stateNumber;
            tints[count] = 0xFFFFFF;
            flags[count] = 0;
            faces[count] = -1;
            fronts[count] = 0;
            cells[count++] = cell;
        }
    }

    /** How many quads were noted since {@link #begin}. */
    int count() {
        return count;
    }

    BakedQuad quad(int index) {
        return quads[index];
    }

    /** The first of a noted quad's four vertices in its buffer. */
    int firstVertex(int index) {
        return firstVertex[index];
    }

    /** The cell of the cube a noted quad's block is in. */
    int cell(int index) {
        return cells[index];
    }

    /** The layer a noted quad was drawn in. */
    int layer(int index) {
        return layers[index];
    }

    /** The number its block's state goes by. */
    int state(int index) {
        return states[index];
    }

    /** Its tint, white where it has none. */
    int tint(int index) {
        return tints[index];
    }

    /** The way a noted quad's face is turned, as the game numbers the six, or -1 where it has none. */
    int face(int index) {
        return faces[index];
    }

    /** What stands in the cell in front of a noted quad's face, where the occlusion is audited. */
    int front(int index) {
        return fronts[index];
    }

    /** What the game did to its color, as {@link FaceRecord}'s flags. */
    int flags(int index) {
        return flags[index];
    }
}
