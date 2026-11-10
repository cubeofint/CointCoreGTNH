package coint.mixin.appliedenergistics2;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.me.cache.NetworkMonitor;
import coint.ae2.ListenerSnapshot;

/**
 * The network monitor tells its listeners (terminals, buses, hatches) about changes by walking its listener map. A
 * listener that adds or removes a listener while being told (seen when a quantum bridge splits a network) broke that
 * walk with a ConcurrentModificationException and crashed the server. The walk now goes over a copy; removing an
 * invalid listener still removes it from the real map.
 */
@Mixin(value = NetworkMonitor.class, remap = false)
public abstract class MixinNetworkMonitorListenerSnapshot {

    @Shadow
    @Final
    private Map<IMEMonitorHandlerReceiver, Object> listeners;

    @Inject(method = "getListeners", at = @At("HEAD"), cancellable = true)
    private void cointcore$snapshot(CallbackInfoReturnable<Iterator<Entry<IMEMonitorHandlerReceiver, Object>>> cir) {
        if (!this.listeners.isEmpty()) {
            cir.setReturnValue(new ListenerSnapshot<>(this.listeners));
        }
    }
}
