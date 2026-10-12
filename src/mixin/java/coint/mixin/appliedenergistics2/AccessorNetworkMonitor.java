package coint.mixin.appliedenergistics2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.api.storage.data.IItemList;
import appeng.me.cache.NetworkMonitor;

@Mixin(value = NetworkMonitor.class, remap = false)
public interface AccessorNetworkMonitor {

    /** The last built storage list, possibly stale; reading it does not trigger a rebuild. */
    @Accessor("cachedList")
    IItemList<?> cointcore$getCachedList();
}
