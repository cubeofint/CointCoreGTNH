package coint.mixin.minecraft;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.gen.ChunkProviderServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.migration.MigrationGuard;

@Mixin(ChunkProviderServer.class)
public abstract class MixinChunkProviderMigrationFreeze {

    @Shadow
    public WorldServer worldObj;

    @Inject(
        method = "loadChunk(IILjava/lang/Runnable;)Lnet/minecraft/world/chunk/Chunk;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false)
    private void cointcore$hideArchivedChunk(int x, int z, Runnable callback, CallbackInfoReturnable<Chunk> cir) {
        if (MigrationGuard.chunk(worldObj, x, z)) {
            if (callback != null) callback.run();
            cir.setReturnValue(new EmptyChunk(worldObj, x, z));
        }
    }

    @Inject(method = "originalLoadChunk", at = @At("HEAD"), cancellable = true, remap = false)
    private void cointcore$hideArchivedOriginal(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        if (MigrationGuard.chunk(worldObj, x, z)) cir.setReturnValue(new EmptyChunk(worldObj, x, z));
    }
}
