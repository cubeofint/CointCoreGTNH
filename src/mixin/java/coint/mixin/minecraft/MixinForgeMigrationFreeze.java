package coint.mixin.minecraft;

import net.minecraft.world.ChunkCoordIntPair;
import net.minecraftforge.common.ForgeChunkManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.migration.MigrationGuard;

@Mixin(value = ForgeChunkManager.class, remap = false)
public abstract class MixinForgeMigrationFreeze {

    @Inject(method = "forceChunk", at = @At("HEAD"), cancellable = true)
    private static void cointcore$freezeTicket(ForgeChunkManager.Ticket ticket, ChunkCoordIntPair chunk,
        CallbackInfo ci) {
        if (ticket != null && chunk != null && MigrationGuard.chunk(ticket.world, chunk.chunkXPos, chunk.chunkZPos))
            ci.cancel();
    }
}
