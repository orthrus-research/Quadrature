package cc.orthrus.quadrature.mixin;

import cc.orthrus.quadrature.terrain.GameChunkRebuilds;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.chunk.ChunkCompileTaskGenerator;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.BlockRenderLayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game's chunk rebuild leaves out the opaque layers own terrain draws (PLAN item 12a; {@link GameChunkRebuilds}).
 * The per-block layer test is wrapped, not redirected, so other mods' wraps of the same call still run; for the
 * layers it leaves out, the original test, and whatever other mods hook into it, is not called at all.
 */
@Mixin(RenderChunk.class)
public abstract class MixinRenderChunkLayers {

    @Inject(method = "rebuildChunk", at = @At("HEAD"))
    private void quadrature$decide(float x, float y, float z, ChunkCompileTaskGenerator generator, CallbackInfo callback,
        @Share("leaveOut") LocalBooleanRef leaveOut, @Share("started") LocalLongRef started,
        @Share("cpuStarted") LocalLongRef cpuStarted) {
        // Once a rebuild, so every block of it follows one rule.
        leaveOut.set(GameChunkRebuilds.leavesOutOpaque(((RenderChunk) (Object) this).getWorld()));
        started.set(System.nanoTime());
        cpuStarted.set(GameChunkRebuilds.threadCpu());
    }

    @WrapOperation(method = "rebuildChunk", at = @At(value = "INVOKE", remap = false,
        target = "Lnet/minecraft/block/Block;canRenderInLayer(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/BlockRenderLayer;)Z"))
    private boolean quadrature$layer(Block block, IBlockState state, BlockRenderLayer layer, Operation<Boolean> original,
        @Share("leaveOut") LocalBooleanRef leaveOut) {
        if (leaveOut.get() && GameChunkRebuilds.opaque(layer)) {
            return false;
        }
        return original.call(block, state, layer);
    }

    @Inject(method = "rebuildChunk", at = @At("RETURN"))
    private void quadrature$rebuilt(float x, float y, float z, ChunkCompileTaskGenerator generator, CallbackInfo callback,
        @Share("leaveOut") LocalBooleanRef leaveOut, @Share("started") LocalLongRef started,
        @Share("cpuStarted") LocalLongRef cpuStarted) {
        GameChunkRebuilds.rebuilt(System.nanoTime() - started.get(), GameChunkRebuilds.threadCpu() - cpuStarted.get(),
            leaveOut.get());
    }
}
