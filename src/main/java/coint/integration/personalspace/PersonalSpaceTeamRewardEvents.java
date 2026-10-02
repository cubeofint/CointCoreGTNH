package coint.integration.personalspace;

import java.util.UUID;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import betterquesting.api.events.QuestEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@EventBusSubscriber
public class PersonalSpaceTeamRewardEvents {

    @SideOnly(Side.SERVER)
    @SubscribeEvent
    public static void onQuestComplete(QuestEvent event) {
        if (event.getType() != QuestEvent.Type.COMPLETED || event.getQuestIDs()
            .isEmpty()) return;
        UUID playerId = event.getPlayerID();
        for (UUID questId : event.getQuestIDs()) {
            PersonalSpaceTeamReward.onQuestCompleted(playerId, questId);
        }
    }
}
