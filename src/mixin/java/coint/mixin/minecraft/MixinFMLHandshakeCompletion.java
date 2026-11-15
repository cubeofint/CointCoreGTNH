package coint.mixin.minecraft;

import java.util.Collections;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.cluster.HandshakeCompletionPacket;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import cpw.mods.fml.common.network.internal.FMLMessage;
import cpw.mods.fml.common.network.internal.FMLNetworkHandler;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;

@Mixin(value = FMLNetworkHandler.class, remap = false)
public abstract class MixinFMLHandshakeCompletion {

    @Inject(method = "forwardHandshake", at = @At("HEAD"), cancellable = true, require = 1)
    private static void cointcore$connectionOwnedCompletion(FMLMessage.CompleteHandshake message,
        NetworkDispatcher connection, Side side, CallbackInfoReturnable<List<FMLProxyPacket>> cir) {
        if (side == Side.SERVER) {
            cir.setReturnValue(Collections.singletonList(new HandshakeCompletionPacket(connection)));
        }
    }
}
