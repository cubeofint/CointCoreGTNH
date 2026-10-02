package coint.integration.personalspace;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;

public enum PDimRewardSyncEvents {

    INSTANCE;

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PersonalSpaceTeamReward.syncClientState((EntityPlayerMP) event.player);
        }
    }
}
