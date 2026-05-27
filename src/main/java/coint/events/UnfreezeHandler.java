package coint.events;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.DimensionCleaner;
import coint.player.TeamsManager;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import me.eigenraven.personalspace.world.DimensionConfig;

@EventBusSubscriber
public class UnfreezeHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        int dimId = TeamsManager.get()
            .getDim(event.player);
        if (DimensionCleaner.excludeRegisterList.contains(dimId)) {
            var cfg = DimensionConfig.getForDimension(dimId, false);
            cfg.registerWithDimensionManager(dimId, false);
        }
    }
}
