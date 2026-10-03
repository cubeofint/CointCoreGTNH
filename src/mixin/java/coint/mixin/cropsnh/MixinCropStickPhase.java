package coint.mixin.cropsnh;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.CointConfig;

/**
 * CropsNH does not save the growth counter, so every crop loaded with a chunk starts at zero and grows on the same
 * tick. Shift that counter by position once, before the tile increments it.
 */
@Mixin(targets = "com.gtnewhorizon.cropsnh.tileentity.TileEntityCropSticks", remap = false)
public abstract class MixinCropStickPhase {

    @Shadow(remap = false)
    private int ticker;

    @Unique
    private boolean cointcore$phased;

    @Inject(method = { "updateEntity", "func_145845_h" }, at = @At("HEAD"), remap = false)
    private void cointcore$phaseGrowth(CallbackInfo ci) {
        if (this.cointcore$phased) {
            return;
        }
        this.cointcore$phased = true;
        if (!CointConfig.crops.spreadGrowthTicks || this.ticker != 0) {
            return;
        }
        TileEntity crop = (TileEntity) (Object) this;
        int mixed = (crop.xCoord * 73428767) ^ (crop.zCoord * 912931) ^ (crop.yCoord * 199);
        this.ticker = mixed & 255;
    }
}
