package coint.mixin.botania;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.performance.ChunkLoadGuard;

@Mixin(targets = "vazkii.botania.common.block.tile.mana.TileSpreader", remap = false)
public abstract class MixinTileSpreaderChunkGuard {

    @Redirect(
        method = "updateEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getTileEntity(III)Lnet/minecraft/tileentity/TileEntity;",
            remap = true),
        remap = false,
        require = 0)
    private TileEntity cointcore$loadedTileOnly(World world, int x, int y, int z) {
        return ChunkLoadGuard.getLoadedTileEntity(world, x, y, z);
    }
}
