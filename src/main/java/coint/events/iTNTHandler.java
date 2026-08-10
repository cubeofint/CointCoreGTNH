package coint.events;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import ic2.api.event.ExplosionEvent;

@EventBusSubscriber
public class iTNTHandler {

    @SubscribeEvent
    public static void onExplode(ExplosionEvent event) {
        if (CointConfig.general.ic2ExplosionEnabled) return;
        event.setCanceled(true);
    }
}
