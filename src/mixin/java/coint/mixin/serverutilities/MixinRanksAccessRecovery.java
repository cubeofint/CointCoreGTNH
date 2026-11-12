package coint.mixin.serverutilities;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.cluster.AccessRanks;
import serverutils.ranks.Ranks;

/** Keep assignments for players unknown to this node's Universe across SU's reload/save cycle. */
@Mixin(value = Ranks.class, remap = false)
public abstract class MixinRanksAccessRecovery {

    @Unique
    private AccessRanks.Recovery cointcore$accessRecovery;

    @Inject(method = "reload", at = @At("HEAD"))
    private void cointcore$beforeAccessReload(CallbackInfoReturnable<Boolean> cir) {
        cointcore$accessRecovery = AccessRanks.beforeReload((Ranks) (Object) this);
    }

    @Inject(method = "reload", at = @At("RETURN"))
    private void cointcore$afterAccessReload(CallbackInfoReturnable<Boolean> cir) {
        if (cointcore$accessRecovery != null) {
            cointcore$accessRecovery.restore((Ranks) (Object) this);
            cointcore$accessRecovery = null;
        }
    }
}
