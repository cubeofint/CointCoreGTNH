package coint.mixin.minecraft;

import net.minecraft.network.NetworkManager;
import net.minecraft.server.network.NetHandlerLoginServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.authlib.GameProfile;

@Mixin(NetHandlerLoginServer.class)
public abstract class MixinNetHandlerLoginServerClusterSession {

    @Shadow
    public NetworkManager field_147333_a;
    @Shadow
    private GameProfile field_147337_i;

    @Inject(
        method = "func_147326_c",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/management/ServerConfigurationManager;allowUserToConnect(Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;)Ljava/lang/String;"),
        cancellable = true,
        require = 1)
    private void cointcore$reserveSession(CallbackInfo ci) {
        if (!coint.cluster.ClusterSessions.ready(field_147333_a, field_147337_i)) ci.cancel();
    }
}
