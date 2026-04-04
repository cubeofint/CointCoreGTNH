package coint.events;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import coint.player.CointPlayer;
import coint.util.TimeUtil;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

@EventBusSubscriber
public class BehaviorHandler {

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onEntityJoin(EntityJoinWorldEvent event) {
        if (event.entity instanceof EntityPlayerMP ep && !event.entity.worldObj.isRemote) {
            CointPlayer player = CointPlayer.get(ep.getCommandSenderName());
            if (player.isBanned()) {
                CointCore.LOG.info("Kicking banned player {}", ep.getDisplayName());
                ep.playerNetServerHandler.kickPlayerFromServer(player.getBanMessage());
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void muteTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        java.util.List<EntityPlayerMP> players = MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList;

        for (EntityPlayer ep : players) {
            CointPlayer player = CointPlayer.get(ep.getCommandSenderName());
            if (player.isMuteExpired()) {
                player.unmute();
                ep.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "Ваш мут был автоматически снят"));
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerChat(ServerChatEvent event) {
        CointPlayer player = CointPlayer.get(event.username);
        if (player.isMuted()) {
            event.setCanceled(true);
            event.player.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "Ваш чат заблокирован. Доступен через "
                        + EnumChatFormatting.GOLD
                        + TimeUtil.formatDuration(player.getMuteRemaining())));

        }
    }
}
