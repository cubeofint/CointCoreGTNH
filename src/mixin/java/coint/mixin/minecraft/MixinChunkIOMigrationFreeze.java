package coint.mixin.minecraft;

import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.DimensionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.migration.MigrationGuard;

/** An async read queued before the freeze must not resurrect a retired chunk/world. */
@Mixin(targets = "net.minecraftforge.common.chunkio.ChunkIOProvider", remap = false)
public abstract class MixinChunkIOMigrationFreeze {

    @Inject(
        method = "callStage2(Lnet/minecraftforge/common/chunkio/QueuedChunk;Lnet/minecraft/world/chunk/Chunk;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void cointcore$discardFrozenRead(@Coerce Object queued, Chunk chunk, CallbackInfo ci) {
        AccessorQueuedMigrationChunk job = (AccessorQueuedMigrationChunk) queued;
        if (MigrationGuard.chunk(job.cointcore$world(), job.cointcore$x(), job.cointcore$z())
            || DimensionManager.getWorld(job.cointcore$world().provider.dimensionId) != job.cointcore$world())
            ci.cancel();
    }
}
