package coint.mixin.minecraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.CointConfig;
import coint.performance.ChunkLoadGuard;

@Mixin(BlockChest.class)
public abstract class MixinBlockChestChunkGuard {

    @Redirect(
        method = "func_149951_m",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBlock(III)Lnet/minecraft/block/Block;"))
    private Block cointcore$loadedAdjacentBlockOnly(World world, int x, int y, int z) {
        if (!CointConfig.limiter.preventChestChunkLoading || world == null || world.isRemote) {
            return world.getBlock(x, y, z);
        }
        return ChunkLoadGuard.getLoadedBlockOrAir(world, x, y, z);
    }
}
