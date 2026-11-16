package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.distant.DistantHook;

import net.minecraft.client.renderer.EntityRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant terrain's two places in the game's world pass. Both do nothing unless distant terrain is
 * asked for.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererDistant {

    /**
     * Distant terrain is drawn where the game goes from its opaque terrain layers to its entities:
     * after near terrain, so that what near terrain covers is rejected by depth before it is shaded,
     * and before anything that should stand in front of it. The game has just put back the world's
     * model-view matrix. The point is the first draw of entities, not the profiler section before
     * it: the section call's argument is already rewritten by the Mixin that reads section names,
     * and an injection that looks for the name beside the call no longer finds it.
     */
    @Inject(method = "renderWorldPass", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/RenderGlobal;renderEntities(Lnet/minecraft/entity/Entity;"
            + "Lnet/minecraft/client/renderer/culling/ICamera;F)V", ordinal = 0))
    private void quadrature$distantTerrain(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        DistantHook.draw(partialTicks);
    }

    /**
     * Every projection the game builds for the world pass (terrain and entities, the sky, the
     * clouds, the hand) takes its far distance through here, so that all of them share one mapping
     * from distance to depth with distant terrain.
     */
    @ModifyArg(method = {"setupCameraTransform", "renderHand", "renderWorldPass", "renderCloudsCheck"}, index = 3,
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V", remap = false))
    private float quadrature$farPlane(float far) {
        return DistantHook.far(far);
    }
}
