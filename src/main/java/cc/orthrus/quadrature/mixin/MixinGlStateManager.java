package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.frame.GlStateMirror;

import net.minecraft.client.renderer.GlStateManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps {@link GlStateMirror} level with the game's state cache for the state Quadrature's stages
 * change: each setter's argument is the cache's value once it returns, whether or not the cache called
 * the driver.
 */
@Mixin(GlStateManager.class)
public abstract class MixinGlStateManager {

    @Inject(method = "depthFunc", at = @At("HEAD"))
    private static void quadrature$depthFunc(int function, CallbackInfo info) {
        GlStateMirror.depthFunction = function;
    }

    @Inject(method = "depthMask", at = @At("HEAD"))
    private static void quadrature$depthMask(boolean mask, CallbackInfo info) {
        GlStateMirror.depthMask = mask;
    }

    @Inject(method = "enableCull", at = @At("HEAD"))
    private static void quadrature$enableCull(CallbackInfo info) {
        GlStateMirror.cull = true;
    }

    @Inject(method = "disableCull", at = @At("HEAD"))
    private static void quadrature$disableCull(CallbackInfo info) {
        GlStateMirror.cull = false;
    }

    @Inject(method = "enablePolygonOffset", at = @At("HEAD"))
    private static void quadrature$enablePolygonOffset(CallbackInfo info) {
        GlStateMirror.polygonOffsetFill = true;
    }

    @Inject(method = "disablePolygonOffset", at = @At("HEAD"))
    private static void quadrature$disablePolygonOffset(CallbackInfo info) {
        GlStateMirror.polygonOffsetFill = false;
    }

    @Inject(method = "doPolygonOffset", at = @At("HEAD"))
    private static void quadrature$doPolygonOffset(float factor, float units, CallbackInfo info) {
        GlStateMirror.polygonOffsetFactor = factor;
        GlStateMirror.polygonOffsetUnits = units;
    }
}
