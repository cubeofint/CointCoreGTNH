package coint.mixin.betterquesting;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import betterquesting.questing.QuestInstance;
import coint.cluster.ClusterHomes;

/** A guest on another server of the network gets no quest rewards there: they would be items on a server not his. */
@Mixin(value = QuestInstance.class, remap = false)
public abstract class MixinQuestInstanceGuestReward {

    @Inject(method = "claimReward(Lnet/minecraft/entity/player/EntityPlayer;Z)V", at = @At("HEAD"), cancellable = true)
    private void coint$noRewardsForGuests(EntityPlayer player, boolean forceChoice, CallbackInfo ci) {
        if (ClusterHomes.isGuest(player)) {
            player.addChatMessage(new ChatComponentText("§eВы в гостях: награды за квесты можно забрать только дома."));
            ci.cancel();
        }
    }
}
