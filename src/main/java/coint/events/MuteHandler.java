package coint.events;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.ServerChatEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.cluster.ClusterSanctions;
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
            if (!player.isOnline()) {
                muted.remove(player);
                return;
            }

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
        UUID id = event.player.getUniqueID();
        // Once the network has a word on his mute, that counts; else the mute of this server.
        Mute mute = ClusterSanctions.knowsMute(id) ? ClusterSanctions.mute(id)
            : CointPlayer.get(event.username)
                .getMute();
        if (mute != null && !mute.isExpired()) {
            event.setCanceled(true);
            event.player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "Ваш чат заблокирован по причине:"
                        + mute.reason
                        + ". Доступен через "
                        + EnumChatFormatting.GOLD
                        + TimeUtil.formatDuration(mute.expiresAt - System.currentTimeMillis())));

        }
    }
}
