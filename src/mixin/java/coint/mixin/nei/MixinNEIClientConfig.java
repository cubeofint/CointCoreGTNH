package coint.mixin.nei;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.client.ClientEventNei;

@Pseudo
@Mixin(targets = "codechicken.nei.NEIClientConfig", remap = false)
public abstract class MixinNEIClientConfig {

    @Inject(method = "isHidden", at = @At("HEAD"), cancellable = true)
    private static void cointcore$hideDuringEvent(CallbackInfoReturnable<Boolean> cir) {
        if (ClientEventNei.isHidden()) cir.setReturnValue(true);
    }

    @Inject(method = "toggleBooleanSetting", at = @At("HEAD"), cancellable = true)
    private static void cointcore$preserveVisibilitySetting(String setting, CallbackInfo ci) {
        if ("inventory.hidden".equals(setting) && ClientEventNei.isHidden()) ci.cancel();
    }
}
