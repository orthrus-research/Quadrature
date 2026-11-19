package cc.orthrus.quadrature.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.shader.Framebuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The framebuffer the game draws glowing entities' outlines into, for the scaler's viewport while it is bound. */
@Mixin(RenderGlobal.class)
public interface RenderGlobalScaleAccess {

    @Accessor("entityOutlineFramebuffer")
    Framebuffer quadrature$outlineFramebuffer();
}
