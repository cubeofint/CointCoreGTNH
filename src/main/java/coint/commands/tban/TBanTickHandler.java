package coint.commands.tban;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class TBanTickHandler {

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
            PlayerTBanData tbanData = PlayerTBanData.get(player);
            if (tbanData != null && tbanData.isBanned()) {
                TBan tban = tbanData.get();
                if (tban.isExpired()) {
                    tbanData.clear();
                    TBanStorage.clearBan(player.getUniqueID());
                    player.addChatMessage(
                        new ChatComponentText(EnumChatFormatting.GREEN + "Ваш бан был автоматически снят"));
                }
            }
        }
    }
}
