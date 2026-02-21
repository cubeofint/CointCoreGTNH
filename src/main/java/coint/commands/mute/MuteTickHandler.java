package coint.commands.mute;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class MuteTickHandler {

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        java.util.List<EntityPlayer> players = (java.util.List<EntityPlayer>) (java.util.List<?>) server
            .getConfigurationManager().playerEntityList;

        for (EntityPlayer player : players) {
            PlayerMuteData muteData = PlayerMuteData.get(player);
            if (muteData != null && muteData.isMuted()) {
                Mute mute = muteData.get();
                if (mute.isExpired()) {
                    muteData.clear();
                    player.addChatMessage(
                        new ChatComponentText(EnumChatFormatting.GREEN + "Ваш мут был автоматически снят"));
                }
            }
        }
    }
}
