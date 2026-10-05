package coint.mixin.thaumcraft;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.performance.ChunkLoadGuard;

@Mixin(targets = "thaumcraft.api.ThaumcraftApiHelper", remap = false)
public abstract class MixinThaumcraftApiHelperChunkGuard {

    @Redirect(
        method = "getConnectableTile",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getTileEntity(III)Lnet/minecraft/tileentity/TileEntity;",
            remap = true),
        remap = false,
        require = 0)
    private static TileEntity cointcore$loadedTileOnly(World world, int x, int y, int z) {
        return ChunkLoadGuard.getLoadedTileEntity(world, x, y, z);
    }
}
