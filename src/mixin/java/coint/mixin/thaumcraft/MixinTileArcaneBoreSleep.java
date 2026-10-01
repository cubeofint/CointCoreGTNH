package coint.mixin.thaumcraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.CointConfig;
import thaumcraft.common.tiles.TileArcaneBore;

@Mixin(value = TileArcaneBore.class, remap = false)
public abstract class MixinTileArcaneBoreSleep {

    @Unique
    private int cointcore$idleTicks;

    @Unique
    private int cointcore$sleepCheckTicks;

    @Unique
    private boolean cointcore$sleeping;

    @Unique
    private boolean cointcore$foundWorkThisTick;

    @Inject(method = "updateEntity", at = @At("HEAD"), cancellable = true, remap = false)
    private void cointcore$skipIdleTicks(CallbackInfo ci) {
        TileArcaneBore bore = (TileArcaneBore) (Object) this;
        if (bore.getWorldObj() == null || bore.getWorldObj().isRemote) {
            return;
        }
        if (!CointConfig.arcaneBore.enabled) {
            cointcore$resetSleep();
            return;
        }
        if (!bore.gettingPower()) {
            cointcore$resetSleep();
            return;
        }

        cointcore$foundWorkThisTick = false;
        if (!cointcore$sleeping) {
            return;
        }

        int interval = Math.max(1, CointConfig.arcaneBore.sleepCheckIntervalTicks);
        if (++cointcore$sleepCheckTicks < interval) {
            ci.cancel();
            return;
        }
        cointcore$sleepCheckTicks = 0;
    }

    @Inject(method = "updateEntity", at = @At("RETURN"), remap = false)
    private void cointcore$trackIdleState(CallbackInfo ci) {
        TileArcaneBore bore = (TileArcaneBore) (Object) this;
        if (bore.getWorldObj() == null || bore.getWorldObj().isRemote) {
            return;
        }
        if (!CointConfig.arcaneBore.enabled || !bore.gettingPower()) {
            cointcore$resetSleep();
            return;
        }

        if (cointcore$foundWorkThisTick) {
            cointcore$idleTicks = 0;
            cointcore$sleepCheckTicks = 0;
            cointcore$sleeping = false;
            return;
        }

        if (cointcore$sleeping) {
            return;
        }

        int timeoutTicks = Math.max(1, CointConfig.arcaneBore.idleTimeoutSeconds) * 20;
        if (++cointcore$idleTicks >= timeoutTicks) {
            cointcore$idleTicks = timeoutTicks;
            cointcore$sleepCheckTicks = 0;
            cointcore$sleeping = true;
        }
    }

    @Inject(method = "sendDigEvent", at = @At("HEAD"), remap = false)
    private void cointcore$wakeOnDigTarget(CallbackInfo ci) {
        TileArcaneBore bore = (TileArcaneBore) (Object) this;
        if (bore.getWorldObj() == null || bore.getWorldObj().isRemote) {
            return;
        }
        cointcore$foundWorkThisTick = true;
        cointcore$idleTicks = 0;
        cointcore$sleepCheckTicks = 0;
        cointcore$sleeping = false;
    }

    @Inject(method = "setInventorySlotContents", at = @At("RETURN"), remap = false)
    private void cointcore$wakeOnInventoryChange(int slot, net.minecraft.item.ItemStack stack, CallbackInfo ci) {
        cointcore$resetSleep();
    }

    @Inject(method = "setOrientation", at = @At("RETURN"), remap = false)
    private void cointcore$wakeOnOrientationChange(net.minecraftforge.common.util.ForgeDirection orientation,
        boolean initial, CallbackInfo ci) {
        cointcore$resetSleep();
    }

    @Unique
    private void cointcore$resetSleep() {
        cointcore$idleTicks = 0;
        cointcore$sleepCheckTicks = 0;
        cointcore$sleeping = false;
        cointcore$foundWorkThisTick = false;
    }
}
