package coint.mixin.enderio;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.CointConfig;

/**
 * A neighbor that changes every tick makes this conduit destroy and rebuild its whole redstone network on every tick.
 * Cancelling here leaves {@code neighbourDirty} set, so the rebuild still happens on the next allowed tick.
 */
@Mixin(targets = "crazypants.enderio.conduit.redstone.RedstoneConduit", remap = false)
public abstract class MixinRedstoneConduitRebuild {

    @Unique
    private int cointcore$rebuildCooldown;

    @Inject(
        method = "updateEntity",
        at = @At(
            value = "INVOKE",
            target = "Lcrazypants/enderio/conduit/redstone/RedstoneConduitNetwork;destroyNetwork()V"),
        cancellable = true,
        remap = false)
    private void cointcore$throttleNetworkRebuild(World world, CallbackInfo ci) {
        if (!CointConfig.conduits.redstoneRebuildThrottle) {
            return;
        }
        int interval = Math.max(1, CointConfig.conduits.redstoneRebuildIntervalTicks);
        if (interval <= 1) {
            return;
        }
        if (this.cointcore$rebuildCooldown > 0) {
            this.cointcore$rebuildCooldown--;
            ci.cancel();
            return;
        }
        this.cointcore$rebuildCooldown = interval - 1;
    }
}
