package coint.mixin.minecraft;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.events.MeteorBootsExecutionContext;
import coint.events.MeteorBootsExplosionGuard;

@Mixin(Entity.class)
public class MixinEntityMeteorBootsSetDead {

    @Inject(method = "setDead()V", at = @At("HEAD"), cancellable = true)
    private void cointcore$protectMeteorItemFromDirectRemoval(CallbackInfo ci) {
        Object self = this;
        if (!(self instanceof EntityItem)) return;

        EntityItem item = (EntityItem) self;
        if (MeteorBootsExecutionContext.isActive()) {
            MeteorBootsExecutionContext.onSetDeadSuppressed();
            MeteorBootsExplosionGuard.restoreItem(item);
            ci.cancel();
            return;
        }

        if (item.worldObj == null || !item.worldObj.isRemote
            || !MeteorBootsExplosionGuard.shouldSuppressItemDamage(item)) {
            return;
        }

        MeteorBootsExplosionGuard.restoreItem(item);
        ci.cancel();
    }
}
