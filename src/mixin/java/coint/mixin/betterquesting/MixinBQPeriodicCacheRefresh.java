package coint.mixin.betterquesting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import betterquesting.handlers.EventHandler;

/**
 * Every player's quest cache is rebuilt from scratch every 10 seconds even when nothing changed (changes already
 * trigger an immediate rebuild). Stretch the unconditional rebuild to 30 seconds.
 */
@Mixin(value = EventHandler.class, remap = false)
public abstract class MixinBQPeriodicCacheRefresh {

    @ModifyConstant(method = "onLivingUpdate", constant = @Constant(intValue = 200), require = 0)
    private int cointcore$slowerPeriodicRefresh(int ticks) {
        return 600;
    }
}
