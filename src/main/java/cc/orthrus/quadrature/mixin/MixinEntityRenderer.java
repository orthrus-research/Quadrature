package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.frame.FrameHost;

import net.minecraft.client.renderer.EntityRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes the boundaries of the game's world pass without changing it. The game already marks each part
 * of {@code renderWorldPass} with a profiler section; these injections observe the start of the pass,
 * each section name as it is entered, and the end. Nothing is redirected, so Forge's hooks, Cleanroom's
 * own calls and other mods' injections in this method are unaffected.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {

    @Inject(method = "renderWorldPass", at = @At("HEAD"))
    private void quadrature$beginPass(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        FrameHost.beginPass();
    }

    @ModifyArg(method = "renderWorldPass", index = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V"))
    private String quadrature$section(String name) {
        FrameHost.section(name);
        return name;
    }

    @Inject(method = "renderWorldPass", at = @At("RETURN"))
    private void quadrature$endPass(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        FrameHost.endPass();
    }
}
