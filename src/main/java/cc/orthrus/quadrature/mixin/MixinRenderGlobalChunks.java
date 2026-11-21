package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.terrain.EntityPass;
import cc.orthrus.quadrature.terrain.EntityPassHost;
import cc.orthrus.quadrature.terrain.GameChunks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game's chunk system in a world Quadrature takes over ({@link GameChunks}, PLAN item 12g). Its terrain setup and
 * chunk updates do nothing, so it never searches, positions, rebuilds or uploads a render chunk and its list of render
 * chunks to draw stays empty; its rebuild buffers are made a single set; and in its entity pass, whose two loops over
 * that list then find nothing, entities and block entities are drawn for the cubes Quadrature's view sees
 * ({@link EntityPass}), at the same points and with the same tests. Everything else of the entity pass, the outlines
 * of glowing entities, the global block entities and the overlays of blocks being broken, is the game's.
 *
 * <p>The renderer's fields and private methods are reached through {@link RenderGlobalChunksAccess}, not shadows, whose
 * names this build does not carry into the released jar.
 */
@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobalChunks implements EntityPassHost {

    private RenderGlobalChunksAccess quadrature$access() {
        return (RenderGlobalChunksAccess) this;
    }

    /** The game's own reaction to a changed view distance is kept; nothing after it runs. */
    @Inject(method = "setupTerrain", at = @At("HEAD"), cancellable = true)
    private void quadrature$setupTerrain(Entity viewEntity, double partialTicks, ICamera camera, int frameCount,
        boolean playerSpectator, CallbackInfo callback) {
        RenderGlobalChunksAccess access = quadrature$access();
        if (GameChunks.takesOver(access.quadrature$world())) {
            if (Minecraft.getMinecraft().gameSettings.renderDistanceChunks != access.quadrature$renderDistanceChunks()) {
                ((RenderGlobal) (Object) this).loadRenderers();
            }
            List<?> infos = ((RenderGlobalAccess) this).getQuadratureRenderInfos();
            if (!infos.isEmpty()) {
                infos.clear();
            }
            callback.cancel();
        }
    }

    @Inject(method = "updateChunks", at = @At("HEAD"), cancellable = true)
    private void quadrature$updateChunks(long finishTimeNano, CallbackInfo callback) {
        if (GameChunks.takesOver(quadrature$access().quadrature$world())) {
            callback.cancel();
        }
    }

    /**
     * The game makes its rebuild buffers once, ten sets a core up to a share of the heap, each about 10 MiB; a world
     * taken over never rebuilds, so one set is made. The dispatcher outlives the world it was made for.
     */
    @WrapOperation(method = "loadRenderers",
        at = @At(value = "NEW", target = "()Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;"))
    private ChunkRenderDispatcher quadrature$dispatcher(Operation<ChunkRenderDispatcher> original) {
        return GameChunks.takesOver(quadrature$access().quadrature$world()) ? new ChunkRenderDispatcher(1) : original.call();
    }

    @Inject(method = "renderEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/util/math/BlockPos$PooledMutableBlockPos;release()V"))
    private void quadrature$entities(Entity viewer, ICamera camera, float partialTicks, CallbackInfo callback,
        @Local(ordinal = 1) List<Entity> outlined, @Local(ordinal = 2) List<Entity> multipass) {
        RenderGlobalChunksAccess access = quadrature$access();
        if (GameChunks.takesOver(access.quadrature$world())) {
            EntityPass.entities(this, access.quadrature$world(), viewer, camera, partialTicks, outlined, multipass);
        }
    }

    @Inject(method = "renderEntities", at = @At(value = "INVOKE", remap = false,
        target = "Lnet/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher;preDrawBatch()V",
        shift = At.Shift.AFTER))
    private void quadrature$blockEntities(Entity viewer, ICamera camera, float partialTicks, CallbackInfo callback) {
        RenderGlobalChunksAccess access = quadrature$access();
        if (GameChunks.takesOver(access.quadrature$world())) {
            EntityPass.blockEntities(access.quadrature$world(), camera, partialTicks);
        }
    }

    @Override
    public boolean quadrature$outlined(Entity entity, Entity viewer, ICamera camera) {
        return quadrature$access().quadrature$isOutlineActive(entity, viewer, camera);
    }

    @Override
    public void quadrature$counted() {
        RenderGlobalChunksAccess access = quadrature$access();
        access.quadrature$setCountEntitiesRendered(access.quadrature$countEntitiesRendered() + 1);
    }
}
