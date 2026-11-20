package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.frame.StillTextures;

import net.minecraft.client.renderer.texture.TextureMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds animated textures at their first frame where {@link StillTextures} is on. */
@Mixin(TextureMap.class)
public abstract class MixinTextureMapStill {

    @Inject(method = "updateAnimations", at = @At("HEAD"), cancellable = true)
    private void quadrature$still(CallbackInfo callback) {
        if (StillTextures.ON) {
            callback.cancel();
        }
    }
}
