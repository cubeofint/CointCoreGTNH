package coint.mixin.ic2;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.CointConfig;
import coint.performance.ChunkLoadGuard;

@Pseudo
@Mixin(targets = "ic2.core.block.kineticgenerator.tileentity.TileEntityWaterKineticGenerator", remap = false)
public abstract class MixinIc2WaterKineticChunkGuard {

    @Redirect(
        method = "checkSpace",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getTileEntity(III)Lnet/minecraft/tileentity/TileEntity;",
            remap = true),
        remap = false,
        require = 0)
    private TileEntity cointcore$loadedTileOnly(World world, int x, int y, int z) {
        if (!CointConfig.general.preventIc2WaterKineticChunkLoading || world == null || world.isRemote) {
            return world.getTileEntity(x, y, z);
        }
        return ChunkLoadGuard.getLoadedTileEntity(world, x, y, z);
    }
}
