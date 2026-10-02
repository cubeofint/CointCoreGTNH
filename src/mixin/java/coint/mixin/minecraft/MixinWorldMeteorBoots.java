package coint.mixin.minecraft;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.events.MeteorBootsExecutionContext;
import coint.events.MeteorBootsExplosionGuard;

@Mixin(World.class)
public class MixinWorldMeteorBoots {

    @Inject(method = "removeEntity(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void cointcore$protectMeteorItemFromWorldRemoval(Entity entity, CallbackInfo ci) {
        if (!(entity instanceof EntityItem) || !MeteorBootsExecutionContext.isActive()) return;

        MeteorBootsExecutionContext.onRemoveSuppressed();
        MeteorBootsExplosionGuard.restoreItem((EntityItem) entity);
        ci.cancel();
    }
}
