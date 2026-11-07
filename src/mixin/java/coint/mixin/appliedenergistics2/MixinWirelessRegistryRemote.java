package coint.mixin.appliedenergistics2;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.core.features.registries.WirelessRegistry;
import coint.cluster.RemoteMe;

/**
 * A wireless terminal linked to an access point on another server of the network opens that network through the
 * cluster, instead of saying the station can't be found. Terminals of this server's networks open as always.
 */
@Mixin(value = WirelessRegistry.class, remap = false)
public abstract class MixinWirelessRegistryRemote {

    @Inject(method = "openWirelessTerminalGui", at = @At("HEAD"), cancellable = true)
    private void cointcore$openRemote(ItemStack item, World world, EntityPlayer player, CallbackInfo ci) {
        if (RemoteMe.openFromTerminal(player, item)) {
            ci.cancel();
        }
    }
}
