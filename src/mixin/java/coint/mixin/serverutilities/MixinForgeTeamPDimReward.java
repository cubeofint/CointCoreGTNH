package coint.mixin.serverutilities;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.integration.personalspace.PersonalSpaceTeamReward;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;

@Mixin(value = ForgeTeam.class, remap = false)
public abstract class MixinForgeTeamPDimReward {

    @Inject(method = "addMember", at = @At("RETURN"))
    private void coint$onMemberAdded(ForgePlayer player, boolean simulate, CallbackInfoReturnable<Boolean> cir) {
        if (!simulate && Boolean.TRUE.equals(cir.getReturnValue())) {
            PersonalSpaceTeamReward.onServerUtilitiesTeamJoined(player);
        }
    }
}
