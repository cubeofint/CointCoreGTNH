package coint.mixin.thaumicexploration;

import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.events.MeteorBootsExecutionContext;

@Pseudo
@Mixin(targets = "flaxbeard.thaumicexploration.event.TXBootsEventHandler", remap = false)
public class MixinTXBootsEventHandler {

    @Inject(method = "livingTick", at = @At("HEAD"), remap = false)
    private void cointcore$enterMeteorBootsTick(LivingUpdateEvent event, CallbackInfo ci) {
        MeteorBootsExecutionContext.enter(event.entityLiving);
    }

    @Inject(method = "livingTick", at = @At("RETURN"), remap = false)
    private void cointcore$exitMeteorBootsTick(LivingUpdateEvent event, CallbackInfo ci) {
        MeteorBootsExecutionContext.exit();
    }
}
