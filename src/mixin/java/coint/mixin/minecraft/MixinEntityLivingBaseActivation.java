package coint.mixin.minecraft;

import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.common.ForgeHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.CointConfig;
import coint.performance.EntityActivationAccess;

@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBaseActivation {

    @Redirect(
        method = "onUpdate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/common/ForgeHooks;onLivingUpdate(Lnet/minecraft/entity/EntityLivingBase;)Z",
            remap = false),
        require = 0)
    private boolean cointcore$throttleLivingUpdateEvent(EntityLivingBase entity) {
        if (CointConfig.limiter.livingUpdateThrottleEnabled && entity instanceof EntityActivationAccess
            && !((EntityActivationAccess) entity).cointcore$runFullEntityTick()) {
            return false;
        }
        return ForgeHooks.onLivingUpdate(entity);
    }
}
