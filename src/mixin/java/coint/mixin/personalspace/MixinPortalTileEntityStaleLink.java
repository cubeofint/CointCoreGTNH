package coint.mixin.personalspace;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.DimensionManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.eigenraven.personalspace.block.PortalTileEntity;
import me.eigenraven.personalspace.world.DimensionConfig;

@Mixin(value = PortalTileEntity.class, remap = false)
public abstract class MixinPortalTileEntityStaleLink {

    @Inject(method = "transport", at = @At("HEAD"))
    private void cointcore$clearStaleTargetBeforeTransport(EntityPlayerMP player, CallbackInfo ci) {
        cointcore$clearStaleTarget();
    }

    @Inject(method = "updateSettings", at = @At("HEAD"))
    private void cointcore$clearStaleTargetBeforeUpdate(EntityPlayerMP player, DimensionConfig unsafeConfig,
        CallbackInfo ci) {
        cointcore$clearStaleTarget();
    }

    private void cointcore$clearStaleTarget() {
        PortalTileEntity self = (PortalTileEntity) (Object) this;
        if (!self.active || self.targetDimId <= 0) {
            return;
        }

        if (!DimensionManager.isDimensionRegistered(self.targetDimId)
            || DimensionConfig.getForDimension(self.targetDimId, false) == null) {
            self.active = false;
            self.targetDimId = 0;
            self.markDirty();
        }
    }
}
