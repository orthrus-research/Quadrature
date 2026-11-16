package cc.orthrus.quadrature.distant;

import cc.orthrus.quadrature.Quadrature;
import cc.orthrus.quadrature.core.lod.MaterialTable;
import cc.orthrus.quadrature.core.lod.RunTile;

import net.minecraft.block.Block;
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

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
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
    private static final int ENTRY_INTS = MaterialTable.ENTRY_WORDS;

    private final MaterialTable<IBlockState> table = new MaterialTable<>();
    private int colored = 1;
    private ByteBuffer entries = ByteBuffer.allocateDirect(1024 * ENTRY_INTS * 4).order(ByteOrder.nativeOrder());
    private int materialBuffer;
    private int biomeBuffer;
    private boolean warned;
    /** The materials' images: an array texture, layer {@code 2 * index} a top and the next a side. */
    private int images;
    private int imageLayers;
    private final ByteBuffer texels = ByteBuffer.allocateDirect(MaterialTable.IMAGE_SIDE * MaterialTable.IMAGE_SIDE * 4);
    /** Left by {@link #color}: the image the color was taken from, and the block's fixed color for it. */
    private TextureAtlasSprite colorSprite;
    private int colorBy;

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

    /** The buffer of three words a material: its top, its sides, its state's number. */
    int materialBuffer() {
        return materialBuffer;
    }

    /** The array texture of the materials' images. */
    int images() {
        return images;
    }

    /** How many layers the array has: a material past half that draws its flat color only. */
    int imageLayers() {
        return imageLayers;
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
            // Sixteen texels a side with every smaller copy, repeated a block apart. 2,048 layers,
            // or as many as the driver has, hold 1,024 materials in under 3 MiB.
            imageLayers = Math.min(2048, GL11.glGetInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS));
            images = GL45.glCreateTextures(GL30.GL_TEXTURE_2D_ARRAY);
            GL45.glTextureStorage3D(images, 5, GL11.GL_RGBA8, MaterialTable.IMAGE_SIDE, MaterialTable.IMAGE_SIDE,
                imageLayers);
            GL45.glTextureParameteri(images, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL45.glTextureParameteri(images, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL45.glTextureParameteri(images, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL45.glTextureParameteri(images, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            image(0, null, 0xFFFFFF);
            image(1, null, 0xFFFFFF);
            // Index 0, no material: a color that shows if it is ever drawn.
            MaterialTable.put(entries, 0, MaterialTable.entry(0xFF00FF, MaterialTable.PLAIN),
                MaterialTable.entry(0xFF00FF, MaterialTable.PLAIN), 0);
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
                image(2 * index, colorSprite, colorBy);
                side = color(state, EnumFacing.NORTH);
                image(2 * index + 1, colorSprite, colorBy);
            } catch (RuntimeException failure) {
                if (!warned) {
                    warned = true;
                    Quadrature.LOGGER.warn("Distant terrain: no color for " + state + "; it draws gray.", failure);
                }
                top = MaterialTable.entry(0x808080, MaterialTable.PLAIN);
                side = top;
                image(2 * index, null, 0xFFFFFF);
                image(2 * index + 1, null, 0xFFFFFF);
            }
            // The state's number as near terrain numbers it, for the table the two will share.
            MaterialTable.put(entries, index, top, side, Block.getStateId(state));
        }
        colored = size;
        upload();
        GL45.glGenerateTextureMipmap(images);
    }

    /** Writes one layer of the images: a sprite's first frame, or gray for none. */
    private void image(int layer, TextureAtlasSprite sprite, int by) {
        if (layer >= imageLayers) {
            return;
        }
        int[] image = sprite == null || sprite.getFrameCount() == 0
            ? MaterialTable.image(new int[0], 0, 0, by)
            : MaterialTable.image(sprite.getFrameTextureData(0)[0], sprite.getIconWidth(), sprite.getIconHeight(), by);
        texels.clear();
        for (int pixel : image) {
            texels.put((byte) (pixel >> 16)).put((byte) (pixel >> 8)).put((byte) pixel).put((byte) (pixel >>> 24));
        }
        texels.flip();
        GL45.glTextureSubImage3D(images, 0, 0, 0, layer, MaterialTable.IMAGE_SIDE, MaterialTable.IMAGE_SIDE, 1,
            GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels);
    }

    private void upload() {
        ByteBuffer part = entries.duplicate().order(ByteOrder.nativeOrder());
        part.position(0).limit(colored * ENTRY_INTS * 4);
        GL45.glNamedBufferData(materialBuffer, part, GL15.GL_DYNAMIC_DRAW);
    }

    /**
     * A material's color on one face, and how its biome tints it. Leaves behind the image the color
     * came from and the block's fixed color, for the face's layer of the images.
     */
    private int color(IBlockState state, EnumFacing facing) {
        Minecraft minecraft = Minecraft.getMinecraft();
        colorBy = 0xFFFFFF;
        if (state.getMaterial() == Material.WATER) {
            // Water has no model; its still image, tinted by the biome.
            TextureAtlasSprite still = minecraft.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/water_still");
            colorSprite = still;
            // Its cover is the image's own alpha, so that distant water lets its bed through as near
            // water does: drawn opaque it was far brighter than near water over a dark bed.
            return MaterialTable.entry(average(still), MaterialTable.WATER_TINT, cover(still));
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
            colorSprite = model.getParticleTexture();
            return MaterialTable.entry(average(colorSprite), MaterialTable.PLAIN);
        }
        colorSprite = quad.getSprite();
        int color = average(quad.getSprite());
        int cover = MaterialTable.FULL_COVER;
        if (state.getMaterial() == Material.LEAVES) {
            // Leaves are drawn solid, as the game's own are from afar, and in their color: see seenFromAfar.
            cover = cover(quad.getSprite());
            color = seenFromAfar(quad.getSprite());
        }
        if (!quad.hasTintIndex()) {
            return MaterialTable.entry(color, MaterialTable.PLAIN, cover);
        }
        // What the block's color handler gives with no world to ask: the plain grass or foliage
        // color for blocks the biome tints, a color of their own for the rest (spruce, birch).
        int fixed = minecraft.getBlockColors().colorMultiplier(state, null, null, quad.getTintIndex());
        if (fixed == -1) {
            return MaterialTable.entry(color, MaterialTable.PLAIN, cover);
        }
        if (state.getBlock() instanceof BlockLeaves && fixed == ColorizerFoliage.getFoliageColorBasic()) {
            return MaterialTable.entry(color, MaterialTable.FOLIAGE, cover);
        }
        if (fixed == ColorizerGrass.getGrassColor(0.5, 1.0)) {
            return MaterialTable.entry(color, MaterialTable.GRASS, cover);
        }
        colorBy = fixed & 0xFFFFFF;
        return MaterialTable.entry(MaterialTable.multiply(color, fixed), MaterialTable.PLAIN, cover);
    }

    private static int seenFromAfar(TextureAtlasSprite sprite) {
        if (sprite == null || sprite.getFrameCount() == 0) {
            return 0x808080;
        }
        return MaterialTable.seenFromAfar(sprite.getFrameTextureData(0)[0]);
    }

    private static int cover(TextureAtlasSprite sprite) {
        if (sprite == null || sprite.getFrameCount() == 0) {
            return MaterialTable.FULL_COVER;
        }
        return MaterialTable.cover(sprite.getFrameTextureData(0)[0]);
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
        // Written once and never again: immutable storage, which carries no usage hint. Given as
        // data with a static-draw hint, the driver warned that the hint did not fit a buffer only
        // the GPU reads.
        GL45.glNamedBufferStorage(biomeBuffer, tints, 0);
    }
}
