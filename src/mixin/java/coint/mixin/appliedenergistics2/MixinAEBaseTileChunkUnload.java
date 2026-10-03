package coint.mixin.appliedenergistics2;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.tile.AEBaseTile;
import coint.ae2.AEGridUnloadBatcher;

@Mixin(value = AEBaseTile.class, remap = false)
public abstract class MixinAEBaseTileChunkUnload {

    @Inject(method = "onChunkUnload", at = @At("HEAD"))
    private void cointcore$beginChunkUnload(CallbackInfo ci) {
        World world = ((TileEntity) (Object) this).getWorldObj();
        AEGridUnloadBatcher.begin(world);
    }

    @Inject(method = "onChunkUnload", at = @At("RETURN"))
    private void cointcore$endChunkUnload(CallbackInfo ci) {
        World world = ((TileEntity) (Object) this).getWorldObj();
        AEGridUnloadBatcher.end(world);
    }
}
