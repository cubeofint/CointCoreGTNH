package coint.mixin.minecraft;

import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

@Mixin(NetworkManager.class)
public abstract class MixinNetworkManagerClusterSession {

    @WrapOperation(
        method = "processReceivedPackets",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/Packet;processPacket(Lnet/minecraft/network/INetHandler;)V"),
        require = 1)
    private void cointcore$checkSession(Packet packet, INetHandler handler, Operation<Void> original) {
        if (coint.cluster.ClusterSessions.mayProcess((NetworkManager) (Object) this)) original.call(packet, handler);
    }
}
