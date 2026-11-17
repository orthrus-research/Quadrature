package cc.orthrus.quadrature.mixin;

import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ViewFrustum.class)
public interface ViewFrustumAccess {

    /** The game's render chunk holding a block, or null outside its grid. */
    @Invoker("getRenderChunk")
    RenderChunk invokeQuadratureGetRenderChunk(BlockPos pos);
}
