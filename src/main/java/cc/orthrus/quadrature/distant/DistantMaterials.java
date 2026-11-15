package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.lod.MaterialTable;
import cc.orthrus.quadrature.core.lod.RunTile;

import net.minecraft.block.BlockLeaves;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ColorizerFoliage;
import net.minecraft.world.ColorizerGrass;
import net.minecraft.world.biome.Biome;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Materials for the first frames of distant terrain: each block state met gets an index, and each
 * index a flat color for its top and one for its sides, the mean of the image the block's model
 * shows there, with a mark for those the biome tints. No images are sampled when drawing.
 *
 * <p>Indices and kinds are asked for by the threads that sample tiles. Colors are worked out on the
 * client thread, which owns the models and the graphics context, for the indices it has not seen;
 * until then a material draws black, for at most a frame.
 */
final class DistantMaterials {

    private static final int BIOMES = 256;
    private static final int ENTRY_INTS = 2;

    private final MaterialTable<IBlockState> table = new MaterialTable<>();
    private int colored = 1;
    private ByteBuffer entries = ByteBuffer.allocateDirect(1024 * ENTRY_INTS * 4).order(ByteOrder.nativeOrder());
    private int materialBuffer;
    private int biomeBuffer;
    private boolean warned;

    /** The index a face of this block carries. Any thread. */
    int index(IBlockState state) {
        return table.index(state);
    }

    /** What the mesher makes of this block. Any thread. */
    static byte kind(IBlockState state) {
        Material material = state.getMaterial();
        if (material == Material.AIR) {
            return RunTile.AIR;
        }
        if (material == Material.WATER) {
            return RunTile.WATER;
        }
        if (material == Material.LEAVES) {
            return RunTile.LEAVES;
        }
        if (state.isOpaqueCube()) {
            return RunTile.OPAQUE;
        }
        // A full cube that is not opaque (glass, ice) is drawn as leaves are; anything else is detail.
        return state.isFullCube() ? RunTile.LEAVES : RunTile.DETAIL;
    }

    /** Materials known. */
    int size() {
        return table.size();
    }

    /** The buffer of two colors a material. */
    int materialBuffer() {
        return materialBuffer;
    }

    /** The buffer of three tints a biome: grass, foliage, water. */
    int biomeBuffer() {
        return biomeBuffer;
    }

    /** Colors the materials met since the last call and hands the table to the driver. Client thread. */
    void refresh() {
        if (biomeBuffer == 0) {
            biomeBuffer = GL45.glCreateBuffers();
            materialBuffer = GL45.glCreateBuffers();
            biomes();
            // Index 0, no material: a color that shows if it is ever drawn.
            entries.putInt(0, MaterialTable.entry(0xFF00FF, MaterialTable.PLAIN));
            entries.putInt(4, MaterialTable.entry(0xFF00FF, MaterialTable.PLAIN));
            colored = 1;
            upload();
        }
        int size = table.size();
        if (size == colored) {
            return;
        }
        if (entries.capacity() < size * ENTRY_INTS * 4) {
            ByteBuffer larger = ByteBuffer.allocateDirect(Math.max(entries.capacity() * 2, size * ENTRY_INTS * 4))
                .order(ByteOrder.nativeOrder());
            larger.put(0, entries, 0, colored * ENTRY_INTS * 4);
            entries = larger;
        }
        for (int index = colored; index < size; index++) {
            IBlockState state = table.key(index);
            int top;
            int side;
            try {
                top = color(state, EnumFacing.UP);
                side = color(state, EnumFacing.NORTH);
            } catch (RuntimeException failure) {
                if (!warned) {
                    warned = true;
                    Quadrature.LOGGER.warn("Distant terrain: no color for " + state + "; it draws gray.", failure);
                }
                top = MaterialTable.entry(0x808080, MaterialTable.PLAIN);
                side = top;
            }
            entries.putInt(index * ENTRY_INTS * 4, top);
            entries.putInt(index * ENTRY_INTS * 4 + 4, side);
        }
        colored = size;
        upload();
    }

    private void upload() {
        ByteBuffer part = entries.duplicate().order(ByteOrder.nativeOrder());
        part.position(0).limit(colored * ENTRY_INTS * 4);
        GL45.glNamedBufferData(materialBuffer, part, GL15.GL_DYNAMIC_DRAW);
    }

    /** A material's color on one face, and how its biome tints it. */
    private static int color(IBlockState state, EnumFacing facing) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (state.getMaterial() == Material.WATER) {
            // Water has no model; its still image, tinted by the biome.
            TextureAtlasSprite still = minecraft.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/water_still");
            return MaterialTable.entry(average(still), MaterialTable.WATER_TINT);
        }
        IBakedModel model = minecraft.getBlockRendererDispatcher().getModelForState(state);
        BakedQuad quad = null;
        List<BakedQuad> quads = model.getQuads(state, facing, 0L);
        if (!quads.isEmpty()) {
            quad = quads.get(0);
        } else {
            for (BakedQuad general : model.getQuads(state, null, 0L)) {
                if (general.getFace() == facing) {
                    quad = general;
                    break;
                }
            }
        }
        if (quad == null) {
            return MaterialTable.entry(average(model.getParticleTexture()), MaterialTable.PLAIN);
        }
        int color = average(quad.getSprite());
        if (!quad.hasTintIndex()) {
            return MaterialTable.entry(color, MaterialTable.PLAIN);
        }
        // What the block's color handler gives with no world to ask: the plain grass or foliage
        // color for blocks the biome tints, a color of their own for the rest (spruce, birch).
        int fixed = minecraft.getBlockColors().colorMultiplier(state, null, null, quad.getTintIndex());
        if (fixed == -1) {
            return MaterialTable.entry(color, MaterialTable.PLAIN);
        }
        if (state.getBlock() instanceof BlockLeaves && fixed == ColorizerFoliage.getFoliageColorBasic()) {
            return MaterialTable.entry(color, MaterialTable.FOLIAGE);
        }
        if (fixed == ColorizerGrass.getGrassColor(0.5, 1.0)) {
            return MaterialTable.entry(color, MaterialTable.GRASS);
        }
        return MaterialTable.entry(MaterialTable.multiply(color, fixed), MaterialTable.PLAIN);
    }

    private static int average(TextureAtlasSprite sprite) {
        if (sprite == null || sprite.getFrameCount() == 0) {
            return 0x808080;
        }
        return MaterialTable.average(sprite.getFrameTextureData(0)[0]);
    }

    /** Each biome's grass, foliage and water tints, as the game gives them away from any block. */
    private void biomes() {
        ByteBuffer tints = ByteBuffer.allocateDirect(BIOMES * 3 * 4).order(ByteOrder.nativeOrder());
        for (int id = 0; id < BIOMES; id++) {
            Biome biome = Biome.getBiome(id);
            int grass = ColorizerGrass.getGrassColor(0.5, 1.0);
            int foliage = ColorizerFoliage.getFoliageColorBasic();
            int water = 0xFFFFFF;
            if (biome != null) {
                try {
                    grass = biome.getGrassColorAtPos(BlockPos.ORIGIN);
                    foliage = biome.getFoliageColorAtPos(BlockPos.ORIGIN);
                    water = biome.getWaterColorMultiplier();
                } catch (RuntimeException failure) {
                    Quadrature.LOGGER.warn("Distant terrain: no tints for biome " + id + ".", failure);
                }
            }
            tints.putInt(grass & 0xFFFFFF).putInt(foliage & 0xFFFFFF).putInt(water & 0xFFFFFF);
        }
        tints.flip();
        GL45.glNamedBufferData(biomeBuffer, tints, GL15.GL_STATIC_DRAW);
    }
}
