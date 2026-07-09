package coint.events;

import net.minecraftforge.event.entity.player.PlayerEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.ServerUtilitiesPermissions;
import serverutils.ranks.Ranks;

@EventBusSubscriber
public class PlayerNameFormatter {

    @EventBusSubscriber.Condition
    public static boolean isEnabled() {
        return false;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void formate(PlayerEvent.NameFormat event) {
        String plainName = event.username;

        try {
            if (Ranks.INSTANCE == null) {
                return;
            }

            String format = Ranks.INSTANCE.getPlayerRank(event.entityPlayer)
                .getPermission(ServerUtilitiesPermissions.CHAT_NAME_FORMAT);
            if (format.isEmpty()) {
                return;
            }

            format = format.replace("{name}", plainName)
                .replace("<", "")
                .replace(">", "")
                .replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1")
                .replaceAll(":\\s*$", "")
                .trim();

            event.displayname = format;
        } catch (Exception e) {
            CointCore.LOG.warn("Failed to get rank format for {}:\n{}", plainName, e.getMessage());
        }
    }
}
