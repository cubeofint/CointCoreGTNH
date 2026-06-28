package coint.events;

import net.minecraftforge.event.entity.player.PlayerEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

@EventBusSubscriber
public class PlayerNameFormatter {

    @EventBusSubscriber.Condition
    public static boolean isEnabled() {
        return false;
    }

    @SubscribeEvent
    public static void formate(PlayerEvent.NameFormat event) {

    }
}
