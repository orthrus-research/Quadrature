package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.terrain.TerrainHook;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal {

    /**
     * With own terrain on, the three opaque layers are drawn from Quadrature's meshes at the point
     * where the game would draw them, under the state it has set for each, and the game's draw is
     * skipped.
     */
    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
        at = @At("HEAD"), cancellable = true)
    private void quadrature$drawLayer(BlockRenderLayer layer, double partialTicks, int pass, Entity viewer,
        CallbackInfoReturnable<Integer> callback) {
        // Only the three layers the game itself calls opaque. Any other layer, the translucent one and
        // those other mods add to the enumeration, is left to the game.
        boolean opaque = layer == BlockRenderLayer.SOLID || layer == BlockRenderLayer.CUTOUT_MIPPED
            || layer == BlockRenderLayer.CUTOUT;
        if (opaque && TerrainHook.active()) {
            TerrainHook.draw(layer, partialTicks, viewer);
            callback.setReturnValue(0);
        }
    }
}
