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
import appeng.core.sync.packets.PacketCraftRequest;
import coint.cluster.RemoteMe;

/** "Next" in AE2's amount window onto a remote network: the remote network plans the order, not a local one. */
@Mixin(value = PacketCraftRequest.class, remap = false)
public abstract class MixinPacketCraftRequestRemote {

    @Shadow
    @Final
    private long amount;

    @Inject(method = "serverPacketData", at = @At("HEAD"), cancellable = true)
    private void cointcore$remotePlan(INetworkInfo manager, AppEngPacket packet, EntityPlayer player, CallbackInfo ci) {
        if (RemoteMe.onCraftRequest(player, amount)) {
            ci.cancel();
        }
    }
}
