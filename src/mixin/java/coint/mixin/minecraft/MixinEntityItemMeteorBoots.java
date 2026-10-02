package coint.mixin.minecraft;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.DamageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.events.MeteorBootsExecutionContext;
import coint.events.MeteorBootsExplosionGuard;

@Mixin(EntityItem.class)
public class MixinEntityItemMeteorBoots {

    @Inject(method = "attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z", at = @At("HEAD"), cancellable = true)
    private void cointcore$protectFromMeteorBootsDamage(DamageSource source, float amount,
        CallbackInfoReturnable<Boolean> cir) {
        EntityItem self = (EntityItem) (Object) this;

        if (MeteorBootsExecutionContext.isActive()) {
            MeteorBootsExecutionContext.onDamageSuppressed(source, amount);
            MeteorBootsExplosionGuard.restoreItem(self);
            cir.setReturnValue(false);
            return;
        }

        if (!MeteorBootsExplosionGuard.shouldSuppressItemDamage(self)) return;

        MeteorBootsExplosionGuard.restoreProtectedItem(self);
        cir.setReturnValue(false);
    }
}
