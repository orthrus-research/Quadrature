package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.distant.DistantHook;

import net.minecraft.client.renderer.EntityRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One fog for near and distant terrain. The game fogs the world out between three quarters of its
 * view distance and the whole of it, which with distant terrain on hides near terrain's edge in
 * front of clear distant land. After the game has set its fog for the world (every {@code setupFog}
 * call but the sky's and the clouds'), the linear fog's two distances are replaced by distant
 * terrain's. Nothing is done unless distant terrain is asked for and sampling a preview.
 */
@Mixin(EntityRenderer.class)
public abstract class MixinEntityRendererFog {

    /** Whether the game is drawing its clouds, whose fog is left as the game sets it. */
    @Unique
    private boolean quadrature$inClouds;

    @Inject(method = "renderCloudsCheck", at = @At("HEAD"))
    private void quadrature$cloudsBegin(CallbackInfo ci) {
        quadrature$inClouds = true;
    }

    @Inject(method = "renderCloudsCheck", at = @At("RETURN"))
    private void quadrature$cloudsEnd(CallbackInfo ci) {
        quadrature$inClouds = false;
    }

    /**
     * {@code startCoords} is -1 for the sky, whose fog shades the dome toward the horizon and stays
     * the game's; any other value is the world's fog.
     *
     * <p>The game's view distance is read from its settings, not from the renderer's own field: a
     * shadowed field's name is not carried into the released jar by this build, and the first start
     * of a game with one stopped there.
     */
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void quadrature$sharedFog(int startCoords, float partialTicks, CallbackInfo ci) {
        if (startCoords != -1 && !quadrature$inClouds) {
            DistantHook.fog();
        }
    }
}
