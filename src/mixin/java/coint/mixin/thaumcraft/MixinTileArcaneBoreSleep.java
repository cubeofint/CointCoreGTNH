package coint.mixin.thaumcraft;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.CointConfig;
import coint.performance.ChunkLoadGuard;
import thaumcraft.common.tiles.TileArcaneBore;

@Mixin(value = TileArcaneBore.class, remap = false)
public abstract class MixinTileArcaneBoreSleep {

    @Redirect(
        method = "findNextBlockToDig",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlock(III)Lnet/minecraft/block/Block;",
            remap = true),
        remap = false,
        require = 0)
    private Block cointcore$loadedBlockOnly(World world, int x, int y, int z) {
        if (!CointConfig.arcaneBore.preventChunkLoading || world == null || world.isRemote) {
            return world.getBlock(x, y, z);
        }
        return ChunkLoadGuard.getLoadedBlockOrAir(world, x, y, z);
    }
}
