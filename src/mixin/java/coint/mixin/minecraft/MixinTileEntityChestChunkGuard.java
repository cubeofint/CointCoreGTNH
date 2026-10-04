package coint.mixin.minecraft;

import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.CointConfig;

@Mixin(TileEntityChest.class)
public abstract class MixinTileEntityChestChunkGuard {

    @Inject(method = "checkForAdjacentChests", at = @At("HEAD"), cancellable = true)
    private void cointcore$avoidAdjacentChunkLoad(CallbackInfo ci) {
        if (!CointConfig.limiter.preventChestChunkLoading) {
            return;
        }

        TileEntityChest chest = (TileEntityChest) (Object) this;
        World world = chest.getWorldObj();
        if (world == null || world.isRemote) {
            return;
        }

        IChunkProvider provider = world.getChunkProvider();
        if (provider == null) {
            return;
        }

        int x = chest.xCoord;
        int z = chest.zCoord;
        if (!cointcore$isChunkLoaded(provider, x - 1, z) || !cointcore$isChunkLoaded(provider, x + 1, z)
            || !cointcore$isChunkLoaded(provider, x, z - 1)
            || !cointcore$isChunkLoaded(provider, x, z + 1)) {
            ci.cancel();
        }
    }

    private static boolean cointcore$isChunkLoaded(IChunkProvider provider, int blockX, int blockZ) {
        return provider.chunkExists(blockX >> 4, blockZ >> 4);
    }
}
