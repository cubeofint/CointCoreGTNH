package coint.commands.mute;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.ServerChatEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class MuteChatHandler {

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        EntityPlayer player = event.player;
        if (player == null) {
            return;
        }

        PlayerMuteData muteData = PlayerMuteData.get(player);
        if (muteData != null && muteData.isMuted()) {
            Mute mute = muteData.get();
            long remainingMs = mute.expiresAt - System.currentTimeMillis();

            if (remainingMs > 0) {
                event.setCanceled(true);

                player.addChatMessage(
                    new ChatComponentText(
                        EnumChatFormatting.RED + "Ваш чат заблокирован. Доступен через "
                            + EnumChatFormatting.GOLD
                            + formatDuration(remainingMs)));
            }
        }
    }

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return days + "д " + (hours % 24) + "ч";
        } else if (hours > 0) {
            return hours + "ч " + (minutes % 60) + "м";
        } else if (minutes > 0) {
            return minutes + "м " + (seconds % 60) + "с";
        } else {
            return seconds + "с";
        }
    }
}
