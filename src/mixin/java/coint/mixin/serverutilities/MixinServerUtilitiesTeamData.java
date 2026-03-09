package coint.mixin.serverutilities;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import coint.integration.serverutilities.CointRankConfigs;
import serverutils.data.ServerUtilitiesTeamData;
import serverutils.lib.data.ForgePlayer;

/**
 * Adds cointcore.bonus_chunks (set per-rank) on top of
 * ServerUtilities' getMaxClaimChunks() result.
 * The bonus is summed across all team members, matching vanilla SU behaviour.
 */
@Mixin(value = ServerUtilitiesTeamData.class, remap = false)
public class MixinServerUtilitiesTeamData {

    @Inject(method = "getMaxClaimChunks", at = @At("RETURN"), cancellable = true)
    private void cointcore$addBonusClaimChunks(CallbackInfoReturnable<Integer> cir) {
        int base = cir.getReturnValue();
        // base < 0 means inactive/invalid — don't touch
        if (base < 0) {
            return;
        }

        ServerUtilitiesTeamData self = (ServerUtilitiesTeamData) (Object) this;
        int bonus = 0;
        for (ForgePlayer player : self.team.getMembers()) {
            bonus += player.getRankConfig(CointRankConfigs.BONUS_CLAIM_CHUNKS)
                .getInt();
        }
        if (bonus > 0) {
            cir.setReturnValue(base + bonus);
        }
    }
}
