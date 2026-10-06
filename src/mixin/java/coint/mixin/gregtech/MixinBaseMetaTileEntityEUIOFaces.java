package coint.mixin.gregtech;

import java.util.Arrays;

import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.common.covers.Cover;

@Mixin(value = BaseMetaTileEntity.class, remap = false)
public abstract class MixinBaseMetaTileEntityEUIOFaces {

    @Shadow
    @Final
    private boolean[] mActiveEUInputs;

    @Shadow
    @Final
    private boolean[] mActiveEUOutputs;

    @Shadow
    protected MetaTileEntity mMetaTileEntity;

    @Shadow
    private boolean mReleaseEnergy;

    @Inject(method = "updateActiveEUIOFaces", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void cointcore$fastUpdateActiveEUIOFaces(CallbackInfo ci) {
        MetaTileEntity meta = this.mMetaTileEntity;
        boolean enetInput = meta.isEnetInput();
        boolean enetOutput = meta.isEnetOutput();
        if (!enetInput && !enetOutput) {
            ci.cancel();
            return;
        }

        BaseMetaTileEntity self = (BaseMetaTileEntity) (Object) this;
        if (this.mReleaseEnergy) {
            Arrays.fill(this.mActiveEUInputs, false);
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                this.mActiveEUOutputs[side.ordinal()] = self.getCoverAtSide(side)
                    .letsEnergyOut();
            }
            ci.cancel();
            return;
        }

        if (self.isInvalid() || !self.canAccessData() || !meta.isElectric()) {
            Arrays.fill(this.mActiveEUInputs, false);
            Arrays.fill(this.mActiveEUOutputs, false);
            ci.cancel();
            return;
        }

        if (enetInput && enetOutput) {
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                Cover cover = self.getCoverAtSide(side);
                int ordinal = side.ordinal();
                this.mActiveEUInputs[ordinal] = cover.letsEnergyIn() && meta.isInputFacing(side);
                this.mActiveEUOutputs[ordinal] = cover.letsEnergyOut() && meta.isOutputFacing(side);
            }
        } else if (enetInput) {
            Arrays.fill(this.mActiveEUOutputs, false);
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                this.mActiveEUInputs[side.ordinal()] = self.getCoverAtSide(side)
                    .letsEnergyIn() && meta.isInputFacing(side);
            }
        } else {
            Arrays.fill(this.mActiveEUInputs, false);
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                this.mActiveEUOutputs[side.ordinal()] = self.getCoverAtSide(side)
                    .letsEnergyOut() && meta.isOutputFacing(side);
            }
        }
        ci.cancel();
    }
}
