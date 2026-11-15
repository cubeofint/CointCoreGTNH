package coint.mixin.minecraft;

import net.minecraft.network.NetworkManager;
import net.minecraft.network.ServerStatusResponse;
import net.minecraft.server.network.NetHandlerStatusServer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(NetHandlerStatusServer.class)
public abstract class MixinNetHandlerStatusServerLocalCounts {

    @Shadow
    @Final
    private NetworkManager field_147313_b;

    @ModifyArg(
        method = "processServerQuery",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/status/server/S00PacketServerInfo;<init>(Lnet/minecraft/network/ServerStatusResponse;)V"),
        index = 0,
        require = 1)
    private ServerStatusResponse cointcore$localCounts(ServerStatusResponse response) {
        return coint.events.ClusterPingOnline.forAddress(response, field_147313_b.getSocketAddress());
    }
}
