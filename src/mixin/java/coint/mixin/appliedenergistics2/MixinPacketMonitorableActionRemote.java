package coint.mixin.appliedenergistics2;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.core.sync.AppEngPacket;
import appeng.core.sync.network.INetworkInfo;
import appeng.core.sync.packets.PacketMonitorableAction;
import appeng.helpers.MonitorableAction;
import coint.cluster.RemoteMe;

/** Craft asked for in AE2's window onto a remote network: our amount dialog, since AE2's plans on a local network. */
@Mixin(value = PacketMonitorableAction.class, remap = false)
public abstract class MixinPacketMonitorableActionRemote {

    @Shadow
    @Final
    private MonitorableAction action;

    @Inject(method = "serverPacketData", at = @At("HEAD"), cancellable = true)
    private void cointcore$remoteCraft(INetworkInfo manager, AppEngPacket packet, EntityPlayer player,
        CallbackInfo ci) {
        if (action == MonitorableAction.AUTO_CRAFT && RemoteMe.onAutoCraft(player)) {
            ci.cancel();
        }
    }
}
