package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.depth.DepthCalls;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    /**
     * The first pass of the game loop is the first moment the render thread certainly holds the
     * window's own context: loading is over and the loading screen's thread has given it back.
     */
    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void quadrature$beginLoop(CallbackInfo callback) {
        DepthCalls.enable();
    }
}
