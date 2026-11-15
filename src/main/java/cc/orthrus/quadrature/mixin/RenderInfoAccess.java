package cc.orthrus.quadrature.mixin;

import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.EnumFacing;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** One entry of the game's selection: a chunk, the direction it was entered from, the directions travelled. */
@Mixin(targets = "net.minecraft.client.renderer.RenderGlobal$ContainerLocalRenderInformation")
public interface RenderInfoAccess {

    @Accessor("renderChunk")
    RenderChunk getQuadratureRenderChunk();

    @Accessor("facing")
    EnumFacing getQuadratureFacing();

    @Accessor("setFacing")
    byte getQuadratureSetFacing();
}
