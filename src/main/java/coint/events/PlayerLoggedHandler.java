package coint.events;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.http.HubWebSocket;
import coint.http.WebSocketMessage;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

@EventBusSubscriber
public class PlayerLoggedHandler {

    @SubscribeEvent
    public static void playerIn(PlayerEvent.PlayerLoggedInEvent event) {
        HubWebSocket.get()
            .send(WebSocketMessage.PlayerLoggedMessage.create(event.player, true));
    }

    @SubscribeEvent
    public static void playerOut(PlayerEvent.PlayerLoggedOutEvent event) {
        HubWebSocket.get()
            .send(WebSocketMessage.PlayerLoggedMessage.create(event.player, false));
    }
}
