package coint.mixin.serverutilities;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.CointConfig;
import coint.cluster.ClusterHub;
import serverutils.lib.data.Universe;
import serverutils.task.CleanupTask;
import serverutils.task.NotifyTask;

/** Covers SU's native cleanup as well as CointCore's replacement task. */
@Mixin(value = CleanupTask.class, remap = false)
public class MixinCleanupHub {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void cointcore$noHubCleanup(Universe universe, CallbackInfo ci) {
        if (CointConfig.cluster.enabled && ClusterHub.isHub()) ci.cancel();
    }

    @Inject(method = "getNotifications", at = @At("HEAD"), cancellable = true)
    private void cointcore$noHubCleanupNotice(CallbackInfoReturnable<java.util.List<NotifyTask>> cir) {
        if (CointConfig.cluster.enabled && ClusterHub.isHub()) cir.setReturnValue(java.util.Collections.emptyList());
    }
}
