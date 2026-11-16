package coint.mixin.minecraft;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.cluster.HandshakeCompletionPacket;
import coint.network.NetworkCompatibility;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import cpw.mods.fml.common.network.internal.FMLMessage;
import cpw.mods.fml.common.network.internal.FMLNetworkHandler;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;

@Mixin(value = FMLNetworkHandler.class, remap = false)
public abstract class MixinFMLHandshakeCompletion {

    @Inject(
        method = "checkModList(Ljava/util/Map;Lcpw/mods/fml/relauncher/Side;)Ljava/lang/String;",
        at = @At("RETURN"),
        cancellable = true,
        require = 1)
    private static void cointcore$explainVersionMismatch(Map<String, String> versions, Side side,
        CallbackInfoReturnable<String> cir) {
        String rejection = cir.getReturnValue();
        if (rejection != null) {
            // Keep Forge's list of rejected mods for diagnosis. This changes only the explanation,
            // never acceptance checks belonging to other mods.
            cir.setReturnValue(NetworkCompatibility.UPDATE_MESSAGE + "\n" + rejection);
        }
    }

    @Inject(method = "forwardHandshake", at = @At("HEAD"), cancellable = true, require = 1)
    private static void cointcore$connectionOwnedCompletion(FMLMessage.CompleteHandshake message,
        NetworkDispatcher connection, Side side, CallbackInfoReturnable<List<FMLProxyPacket>> cir) {
        if (side == Side.SERVER) {
            cir.setReturnValue(Collections.singletonList(new HandshakeCompletionPacket(connection)));
        }
    }
}
