package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.frame.TemporalScaler;

import net.minecraft.client.shader.Framebuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts framebuffers of the game's kind as they are made, for the scaler's timing log: a mod that sizes
 * its framebuffers by the display would remake them every frame if it saw two sizes. Nothing is counted
 * unless the scaler or the temporal pass is asked for.
 */
@Mixin(Framebuffer.class)
public abstract class MixinFramebufferScale {

    @Inject(method = "createFramebuffer", at = @At("HEAD"))
    private void quadrature$made(int width, int height, CallbackInfo info) {
        TemporalScaler.framebufferMade(width, height);
    }
}
