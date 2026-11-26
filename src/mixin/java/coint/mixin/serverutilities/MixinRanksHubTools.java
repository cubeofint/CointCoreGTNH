package coint.mixin.serverutilities;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.authlib.GameProfile;

import coint.integration.serverutilities.HubToolPermissions;
import cpw.mods.fml.common.eventhandler.Event;
import serverutils.ranks.Ranks;

@Mixin(value = Ranks.class, remap = false)
public abstract class MixinRanksHubTools {

    @Inject(
        method = "getPermissionResult(Lcom/mojang/authlib/GameProfile;Ljava/lang/String;Z)Lcpw/mods/fml/common/eventhandler/Event$Result;",
        at = @At("HEAD"),
        cancellable = true)
    private void cointcore$hubTools(GameProfile player, String node, boolean checkPrefixes,
        CallbackInfoReturnable<Event.Result> cir) {
        Event.Result scoped = HubToolPermissions.resolve(player, node, checkPrefixes);
        if (scoped != Event.Result.DEFAULT) cir.setReturnValue(scoped);
    }
}
