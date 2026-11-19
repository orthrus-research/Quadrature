package cc.orthrus.quadrature.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game's framebuffer, which the scaler replaces with the drawn one for the world pass and puts back. */
@Mixin(Minecraft.class)
public interface MinecraftScaleAccess {

    @Accessor("framebuffer")
    void quadrature$setFramebuffer(Framebuffer framebuffer);
}
