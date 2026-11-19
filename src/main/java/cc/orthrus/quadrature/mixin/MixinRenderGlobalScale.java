package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.frame.TemporalScaler;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The outlines of glowing entities while the drawn picture stands in for the game's. The game draws
 * them into a framebuffer of its own, binding it without setting the viewport, runs its outline shader
 * group over it, whose passes leave the viewport at that framebuffer's size, and binds the game's
 * framebuffer back, again without a viewport; after the world pass it lays the result over the
 * picture. That framebuffer and the shader group stay at the output's size, which the group's
 * projection is made for, so the outlines are drawn at the output's size and in place; the drawn
 * viewport goes back when the game's framebuffer does, so block entities after them are drawn in the
 * drawn picture. Nothing is done unless the drawn picture stands in.
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobalScale {

    @Inject(method = "renderEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/shader/Framebuffer;bindFramebuffer(Z)V", ordinal = 0, shift = At.Shift.AFTER))
    private void quadrature$outlinesBound(Entity viewer, ICamera camera, float partialTicks, CallbackInfo info) {
        TemporalScaler.outlinesBegin(((RenderGlobalScaleAccess) (Object) this).quadrature$outlineFramebuffer());
    }

    @Inject(method = "renderEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/shader/Framebuffer;bindFramebuffer(Z)V", ordinal = 1, shift = At.Shift.AFTER))
    private void quadrature$worldBound(Entity viewer, ICamera camera, float partialTicks, CallbackInfo info) {
        TemporalScaler.outlinesEnd();
    }
}
