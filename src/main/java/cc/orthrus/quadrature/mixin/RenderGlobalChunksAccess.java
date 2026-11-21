package cc.orthrus.quadrature.mixin;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * What {@link MixinRenderGlobalChunks} reads and calls of the game's renderer. Accessors and invokers rather than
 * shadows: this build carries their names into the released jar, and a shadow's name is not.
 */
@Mixin(RenderGlobal.class)
public interface RenderGlobalChunksAccess {

    @Accessor("world")
    WorldClient quadrature$world();

    @Accessor("renderDistanceChunks")
    int quadrature$renderDistanceChunks();

    @Accessor("countEntitiesRendered")
    int quadrature$countEntitiesRendered();

    @Accessor("countEntitiesRendered")
    void quadrature$setCountEntitiesRendered(int count);

    @Invoker("isOutlineActive")
    boolean quadrature$isOutlineActive(Entity entity, Entity viewer, ICamera camera);
}
