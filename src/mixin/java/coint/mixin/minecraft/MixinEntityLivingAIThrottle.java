package coint.mixin.minecraft;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.boss.IBossDisplayData;
import net.minecraft.entity.monster.IMob;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.CointConfig;

@Mixin(EntityLiving.class)
public abstract class MixinEntityLivingAIThrottle {

    @Inject(
        method = "updateEntityActionState",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/profiler/Profiler;endSection()V",
            ordinal = 0,
            shift = At.Shift.AFTER),
        cancellable = true)
    private void cointcore$throttleDistantHostileAI(CallbackInfo ci) {
        EntityLiving self = (EntityLiving) (Object) this;
        if (!cointcore$shouldThrottle(self)) {
            return;
        }

        int interval = Math.max(1, CointConfig.limiter.aiThrottleInterval);
        if (Math.floorMod(self.ticksExisted + self.getEntityId(), interval) != 0) {
            ci.cancel();
        }
    }

    @Unique
    private static boolean cointcore$shouldThrottle(EntityLiving self) {
        if (!CointConfig.limiter.aiThrottleEnabled || CointConfig.limiter.aiThrottleInterval <= 1) {
            return false;
        }
        if (self.worldObj == null || self.worldObj.isRemote
            || !(self instanceof IMob)
            || self instanceof IBossDisplayData) {
            return false;
        }
        if (self.getAttackTarget() != null) {
            return false;
        }

        double distance = Math.max(16, CointConfig.limiter.aiFullSpeedDistance);
        return self.worldObj.getClosestPlayerToEntity(self, distance) == null;
    }
}
