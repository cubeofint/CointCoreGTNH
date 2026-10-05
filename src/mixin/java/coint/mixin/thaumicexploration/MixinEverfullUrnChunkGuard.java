package coint.mixin.thaumicexploration;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.performance.ChunkLoadGuard;

@Mixin(targets = "flaxbeard.thaumicexploration.tile.TileEntityEverfullUrn", remap = false)
public abstract class MixinEverfullUrnChunkGuard {

    @Redirect(
        method = "updateEntity",
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
