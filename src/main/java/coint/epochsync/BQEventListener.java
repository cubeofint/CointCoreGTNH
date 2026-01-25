package coint.epochsync;

import betterquesting.api.events.QuestEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class BQEventListener {

    @SubscribeEvent
    public void onQuestApproval(QuestEvent event) {
        System.out.println(
            event.getQuestIDs()
                .toString());

        // Minecraft.getMinecraft().thePlayer.addChatComponentMessage(
        // new ChatComponentText(
        // event.getQuestIDs()
        // .toString()));
    }
}
