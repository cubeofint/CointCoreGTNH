package coint.commands.mute;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.ServerChatEvent;

import coint.util.TimeUtil;
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
                            + TimeUtil.formatDuration(remainingMs)));
            }
        }
    }
}
