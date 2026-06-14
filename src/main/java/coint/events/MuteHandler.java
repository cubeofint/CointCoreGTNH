package coint.events;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.ServerChatEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.player.CointPlayer;
import coint.player.Mute;
import coint.util.TimeUtil;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

@EventBusSubscriber
public class MuteHandler {

    public static List<CointPlayer> muted = new CopyOnWriteArrayList<>();

    @SubscribeEvent
    public static void muteTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        for (CointPlayer player : muted) {
            if (player.isMuteExpired()) {
                player.unmute();
                muted.remove(player);
                player.getPlayer()
                    .addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "Ваш мут был автоматически снят"));
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerChat(ServerChatEvent event) {
        Mute mute = CointPlayer.get(event.username)
            .getMute();
        if (!mute.isExpired()) {
            event.setCanceled(true);
            event.player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "Ваш чат заблокирован ("
                        + mute.reason
                        + "). Доступен через "
                        + EnumChatFormatting.GOLD
                        + TimeUtil.formatDuration(mute.expiresAt - System.currentTimeMillis())));

        }
    }
}
