package coint.integration.cchat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IChatComponent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.http.HubWebSocket;
import coint.http.WebSocketMessage;
import coint.util.ChatUtil;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import ru.cube.cchat.channel.ChannelDef;
import ru.cube.cchat.channel.ChannelRegistry;
import ru.cube.cchat.channel.ServerChannelRegistry;
import ru.cube.cchat.events.ChannelMessageEvent;

@EventBusSubscriber
public class CChatBridge {

    public static final String DISCORD_ID = "discord";
    private static final int BRIDGE_COLOR = 0xFF5865F2;

    private static final Set<String> bridgeChannelIds = ConcurrentHashMap.newKeySet();

    public static void init() {
        if (!CointConfig.api.wsEnabled) return;
        registerBridgeChannel(DISCORD_ID, "Discord");
    }

    private static void registerBridgeChannel(String id, String displayName) {
        if (bridgeChannelIds.add(id)) {
            ServerChannelRegistry.register(new ChannelDef(id, displayName, BRIDGE_COLOR, "", true));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onChannelMessage(ChannelMessageEvent event) {
        if (!CointConfig.api.wsEnabled) return;
        if (!ChannelRegistry.GLOBAL_ID.equals(event.channelId)) return;

        EntityPlayerMP sender = event.sender;
        String formatted = ChatUtil.getRankFormattedName(sender);
        HubWebSocket.get()
            .send(WebSocketMessage.ChatMessage.create(sender, formatted, event.text));
    }

    public static void deliverInbound(String origin, IChatComponent message) {
        if (!CointConfig.api.wsEnabled) return;

        boolean isDiscord = DISCORD_ID.equalsIgnoreCase(origin);
        String id = isDiscord ? DISCORD_ID : "bridge:" + origin.toLowerCase();

        registerBridgeChannel(id, isDiscord ? "Discord" : origin);
        ServerChannelRegistry.send(id, message);
    }
}
