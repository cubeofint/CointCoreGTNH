package coint.mixin.appliedenergistics2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityProvider;
import appeng.me.cache.SecurityCache;

/**
 * Security terminals no longer secure their network. A secured network refuses to join another secured one, or a
 * machine of a player without its permissions, and AE2 then breaks the joining block without drops: players lost
 * blocks to a security terminal standing in a subnet. With no security provider counted, every network keeps the
 * security key -1, so such joins never fail and no permission is ever denied.
 *
 * <p>
 * The block itself still works as a locatable: wireless terminals linked through it keep finding their network.
 * New terminals are linked by right-clicking a wireless access point with them.
 */
@Mixin(value = SecurityCache.class, remap = false)
public abstract class MixinSecurityCacheDisabled {

    @Inject(method = "addNode", at = @At("HEAD"), cancellable = true)
    private void cointcore$ignoreSecurityProviders(IGridNode gridNode, IGridHost machine, CallbackInfo ci) {
        if (machine instanceof ISecurityProvider) {
            ci.cancel();
        }
    }
}
