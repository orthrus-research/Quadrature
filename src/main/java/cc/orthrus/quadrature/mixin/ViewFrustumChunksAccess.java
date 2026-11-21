package cc.orthrus.quadrature.mixin;

import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What {@link MixinViewFrustumChunks} reads and sets of the game's grid of render chunks. Accessors rather than shadows:
 * this build carries their names into the released jar, and a shadow's name is not.
 */
@Mixin(ViewFrustum.class)
public interface ViewFrustumChunksAccess {

    @Accessor("world")
    World quadrature$world();

    @Accessor("countChunksX")
    void quadrature$setCountChunksX(int count);

    @Accessor("countChunksY")
    void quadrature$setCountChunksY(int count);

    @Accessor("countChunksZ")
    void quadrature$setCountChunksZ(int count);
}
