package coint.mixin.serverutilities;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import coint.cluster.ClusterCommands;
import serverutils.data.ServerUtilitiesPlayerData;

/** A nickname set here (/nick, /nickfor) is set on the other servers of the network too. */
@Mixin(value = ServerUtilitiesPlayerData.class, remap = false)
public abstract class MixinServerUtilitiesPlayerDataNickSync {

    @Inject(method = "setNickname", at = @At("TAIL"))
    private void cointcore$syncNickname(String name, CallbackInfo ci) {
        ServerUtilitiesPlayerData self = (ServerUtilitiesPlayerData) (Object) this;
        ClusterCommands.nickChanged(self.player.getName(), self.getNickname());
    }
}
