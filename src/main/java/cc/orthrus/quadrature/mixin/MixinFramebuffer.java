package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.depth.DepthCalls;

import net.minecraft.client.shader.Framebuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(Framebuffer.class)
public abstract class MixinFramebuffer {

    private static final int DEPTH_24 = 0x81A6;
    private static final int DEPTH_24_STENCIL_8 = 0x88F0;
    private static final int DEPTH_32_FLOAT = 0x8CAC;
    private static final int DEPTH_32_FLOAT_STENCIL_8 = 0x8CAD;

    /**
     * Reversed depth gains its precision from a floating-point depth buffer, so the game's
     * framebuffers get one in place of their 24-bit fixed-point buffer.
     */
    @ModifyArg(method = "createFramebuffer", index = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/OpenGlHelper;glRenderbufferStorage(IIII)V"))
    private int quadrature$depthFormat(int format) {
        if (!DepthCalls.REQUESTED) {
            return format;
        }
        if (format == DEPTH_24) {
            return DEPTH_32_FLOAT;
        }
        return format == DEPTH_24_STENCIL_8 ? DEPTH_32_FLOAT_STENCIL_8 : format;
    }
}
