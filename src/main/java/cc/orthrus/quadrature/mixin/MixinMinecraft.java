package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.depth.DepthCalls;
import cc.orthrus.quadrature.platform.OutOfMemoryWatch;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    /**
     * The first pass of the game loop is the first moment the render thread certainly holds the
     * window's own context: loading is over and the loading screen's thread has given it back. Each
     * pass also logs an out-of-memory error the last one ended with.
     */
    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void quadrature$beginLoop(CallbackInfo callback) {
        DepthCalls.enable();
        OutOfMemoryWatch.pass();
    }

    /**
     * The game catches an out-of-memory error from its loop and logs nothing. It is noted here and
     * thrown on, so the game's own handling (freeing memory, its memory screen) runs as before.
     */
    @WrapOperation(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runGameLoop()V"))
    private void quadrature$noteOutOfMemory(Minecraft game, Operation<Void> loop) {
        try {
            loop.call(game);
        } catch (OutOfMemoryError error) {
            OutOfMemoryWatch.caught(error, "the game loop");
            throw error;
        }
    }
}
