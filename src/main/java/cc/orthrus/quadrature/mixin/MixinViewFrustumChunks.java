package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.terrain.GameChunks;

import net.minecraft.client.renderer.ViewFrustum;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In a world Quadrature takes over ({@link GameChunks}, PLAN item 12g) the game's grid of render chunks is never searched,
 * positioned or rebuilt, so it holds one render chunk rather than one per cell of the view distance. The game still
 * marks blocks for update in it, which then come to nothing.
 */
@Mixin(ViewFrustum.class)
public abstract class MixinViewFrustumChunks {

    @Inject(method = "setCountChunksXYZ", at = @At("TAIL"))
    private void quadrature$oneChunk(int renderDistance, CallbackInfo callback) {
        ViewFrustumChunksAccess grid = (ViewFrustumChunksAccess) this;
        if (GameChunks.takesOver(grid.quadrature$world())) {
            grid.quadrature$setCountChunksX(1);
            grid.quadrature$setCountChunksY(1);
            grid.quadrature$setCountChunksZ(1);
        }
    }
}
