package cc.orthrus.quadrature.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Reads what the game selected to draw this frame; used only by the terrain selection diagnostics. */
@Mixin(RenderGlobal.class)
public interface RenderGlobalAccess {

    /** One entry per chunk the game's search reached, in the order it reached them. */
    @Accessor("renderInfos")
    List<?> getQuadratureRenderInfos();

    @Accessor("viewFrustum")
    ViewFrustum getQuadratureViewFrustum();

    /** Render chunks waiting for the game to build them. */
    @Accessor("chunksToUpdate")
    java.util.Set<net.minecraft.client.renderer.chunk.RenderChunk> getQuadratureChunksToUpdate();
}
