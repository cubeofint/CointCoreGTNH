package coint.mixin.minecraft;

import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.pathfinding.PathNavigate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import coint.performance.EntityActivation;
import coint.performance.EntityActivationAccess;

@Mixin(EntityLiving.class)
public abstract class MixinEntityLivingActivation implements EntityActivationAccess {

    @Unique
    private long cointcore$activationTick = Long.MIN_VALUE;

    @Unique
    private boolean cointcore$fullEntityTick = true;

    @Override
    public boolean cointcore$runFullEntityTick() {
        EntityLiving entity = (EntityLiving) (Object) this;
        if (entity.worldObj == null || entity.worldObj.isRemote) {
            return true;
        }

        long worldTick = entity.worldObj.getTotalWorldTime();
        if (cointcore$activationTick != worldTick) {
            cointcore$activationTick = worldTick;
            cointcore$fullEntityTick = EntityActivation.shouldRunFullTick(entity);
        }
        return cointcore$fullEntityTick;
    }

    @Redirect(
        method = "updateEntityActionState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/ai/EntityAITasks;onUpdateTasks()V"),
        require = 0)
    private void cointcore$throttleAiTasks(EntityAITasks tasks) {
        if (cointcore$runFullEntityTick()) {
            tasks.onUpdateTasks();
        }
    }

    @Redirect(
        method = "updateEntityActionState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/pathfinding/PathNavigate;onUpdateNavigation()V"),
        require = 0)
    private void cointcore$throttleNavigation(PathNavigate navigator) {
        if (cointcore$runFullEntityTick()) {
            navigator.onUpdateNavigation();
        }
    }
}
