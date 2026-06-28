package coint.events;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.http.HubWebSocket;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

@EventBusSubscriber
public class ServerInfoSender {

    private static int ticks = 0;

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ticks++;

            if (ticks >= 100) {
                HubWebSocket.get()
                    .sendInfo();
                ticks = 0;
            }
        }
    }
}
