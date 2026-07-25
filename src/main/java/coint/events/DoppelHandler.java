package coint.events;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

@EventBusSubscriber
public class DoppelHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDoppelLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP newp) {
            for (EntityPlayerMP oldp : MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList) {
                if (oldp.getCommandSenderName()
                    .equalsIgnoreCase(newp.getCommandSenderName()) && newp != oldp) {
                    oldp.getServerForPlayer()
                        .getEntityTracker()
                        .removePlayerFromTrackers(oldp);
                    oldp.getServerForPlayer()
                        .removePlayerEntityDangerously(oldp);
                    if (oldp.playerNetServerHandler != null) oldp.playerNetServerHandler.kickPlayerFromServer("Doppel");
                }
            }
        }
    }
}
