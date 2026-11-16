package cc.orthrus.quadrature.terrain;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import cc.orthrus.quadrature.core.terrain.FaceRecord;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.EnumFacing;
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
 */
final class QuadWalk {

    private static final EnumFacing[] SIDES = EnumFacing.values();

    private final VertexLighterFlat flat;
    private final VertexLighterSmoothAo smooth;
    private final VertexBufferConsumer consumer = new VertexBufferConsumer();
    private final boolean usable;

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
    private int count;
    private final BlockColors colors;
    private IBlockAccess world;
    private BlockPos pos;
    private IBlockState state;
    private boolean occluded;
    private int layer;
    private int stateNumber;

    QuadWalk() {
        Minecraft minecraft = Minecraft.getMinecraft();
        BlockRendererDispatcher dispatcher = minecraft.getBlockRendererDispatcher();
        usable = dispatcher.getBlockModelRenderer().getClass() == ForgeBlockModelRenderer.class;
        colors = minecraft.getBlockColors();
        flat = new VertexLighterFlat(minecraft.getBlockColors());
        smooth = new VertexLighterSmoothAo(minecraft.getBlockColors());
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
        consumer.setOffset(pos);
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
            if (!list.isEmpty() && state.shouldSideBeRendered(world, pos, side)) {
                if (empty) {
                    lighter.updateBlockInfo();
                }
                empty = false;
                pipe(list, lighter, buffer, cell);
            }
        }
        lighter.resetBlockInfo();
        return !empty;
    }

    private void pipe(List<BakedQuad> list, VertexLighterFlat lighter, BufferBuilder buffer, int cell) {
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

    /** What the game did to its color, as {@link FaceRecord}'s flags. */
    int flags(int index) {
        return flags[index];
    }
}
