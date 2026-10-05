package coint.mixin.harvestcraft;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.performance.ChunkLoadGuard;

@Mixin(targets = "com.pam.harvestcraft.TileEntityPamFishTrap", remap = false)
public abstract class MixinPamFishTrapChunkGuard {

    @Redirect(
        method = "countFlowers",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlock(III)Lnet/minecraft/block/Block;",
            remap = true),
        remap = false,
        require = 0)
    private Block cointcore$loadedBlockOnly(World world, int x, int y, int z) {
        return ChunkLoadGuard.getLoadedBlockOrAir(world, x, y, z);
    }
}
