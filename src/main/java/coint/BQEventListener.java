package coint;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import betterquesting.api.events.QuestEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class BQEventListener {

    @SubscribeEvent
    public void onQuestApproval(QuestEvent event) {
        Minecraft.getMinecraft().thePlayer.addChatComponentMessage(
            new ChatComponentText(
                event.getQuestIDs()
                    .toString()));
    }
}
