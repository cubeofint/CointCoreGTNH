package coint.mixin.betterquesting;

import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import betterquesting.api.questing.IQuest;
import bq_standard.rewards.RewardItem;
import coint.CointCore;
import coint.integration.personalspace.PersonalSpaceTeamReward;

@Mixin(value = RewardItem.class, remap = false)
public abstract class MixinRewardItemTeamReward {

    @Inject(method = "getUnlocalisedName", at = @At("HEAD"), cancellable = true)
    private void coint$getTeamRewardName(CallbackInfoReturnable<String> cir) {
        RewardItem reward = (RewardItem) (Object) this;
        if (PersonalSpaceTeamReward.isPortalReward(reward)) {
            cir.setReturnValue("cointcore.reward.team");
        }
    }

    @Inject(method = "canClaim", at = @At("HEAD"), cancellable = true)
    private void coint$canClaimTeamReward(EntityPlayer player, Map.Entry<UUID, IQuest> quest,
        CallbackInfoReturnable<Boolean> cir) {
        RewardItem reward = (RewardItem) (Object) this;
        if (!PersonalSpaceTeamReward.isPortalReward(reward)) return;
        if (player == null || player.worldObj == null) return;
        if (player.worldObj.isRemote) {
            if (CointCore.proxy.isPDimRewardBlockedClient()) {
                cir.setReturnValue(false);
            }
            return;
        }
        cir.setReturnValue(PersonalSpaceTeamReward.canClaim(player, quest));
    }

    @Inject(method = "claimReward0", at = @At("HEAD"), cancellable = true)
    private void coint$claimTeamReward(EntityPlayer player, Map.Entry<UUID, IQuest> quest, CallbackInfo ci) {
        RewardItem reward = (RewardItem) (Object) this;
        if (!PersonalSpaceTeamReward.isPortalReward(reward)) return;
        if (player == null || player.worldObj == null || player.worldObj.isRemote) return;
        if (!PersonalSpaceTeamReward.beforeClaim(player, quest)) {
            ci.cancel();
        }
    }
}
