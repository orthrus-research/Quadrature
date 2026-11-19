package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.frame.TemporalScaler;

import net.minecraft.client.renderer.EntityRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two changes to every projection the game builds for the world: the world's own, the sky's and the
 * clouds'. Each is built by loading the identity and calling {@code gluPerspective}, which multiplies onto
 * what is loaded, so the frame's sub-pixel offset multiplied in just before stands in clip space under
 * the perspective; and its shape stays the output's while the display's size is the drawn picture's.
 * The hand's projection is left alone: the hand is drawn after the composite, at the output's size.
 * Nothing is done unless the scaler or the temporal pass is asked for.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererScale {

    @Inject(method = {"setupCameraTransform", "renderWorldPass", "renderCloudsCheck"}, at = @At(value = "INVOKE",
        target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V", remap = false))
    private void quadrature$offsetProjection(CallbackInfo info) {
        TemporalScaler.offsetProjection();
    }

    @ModifyArg(method = {"setupCameraTransform", "renderWorldPass", "renderCloudsCheck"}, index = 1,
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V", remap = false))
    private float quadrature$aspect(float aspect) {
        return TemporalScaler.aspect(aspect);
    }
}
