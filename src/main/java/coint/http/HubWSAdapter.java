package coint.http;

import java.util.List;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import com.google.gson.Gson;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFrame;

import coint.CointCore;

public class HubWSAdapter extends WebSocketAdapter {

    public static int attempts = 0;

    static Gson gson;
    MinecraftServer server;

    public HubWSAdapter() {
        gson = new Gson();
        server = MinecraftServer.getServer();
    }

    @Override
    public void onTextMessage(WebSocket websocket, String text) throws Exception {
        if (server == null) {
            return;
        }

        ChatWSClient.WSMessage msg = gson.fromJson(text, ChatWSClient.WSMessage.class);

        IChatComponent c = new ChatComponentText(
            EnumChatFormatting.GREEN + "[" + msg.server + "] " + EnumChatFormatting.RESET)
                .appendText(msg.senderFormatted + ": ")
                .appendText(msg.text);

        server.getConfigurationManager()
            .sendChatMsg(c);
    }

    @Override
    public void onConnected(WebSocket websocket, Map<String, List<String>> headers) throws Exception {
        String greeting = gson.toJson(new ChatWSClient.WSMessage("server", "server", "greeting"));
        websocket.sendText(greeting);

        CointCore.LOG.info("Hub WS connection established");
    }

    @Override
    public void onConnectError(WebSocket websocket, WebSocketException exception) throws Exception {
        CointCore.LOG.error("Hub WS connection error: {}", exception.getMessage());
        tryReconnect(websocket);
    }

    @Override
    public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame,
        boolean closedByServer) throws Exception {
        if (!closedByServer && clientCloseFrame != null) {
            CointCore.LOG.info("Hub WS closed");
            return;
        }

        tryReconnect(websocket);
    }

    private void tryReconnect(WebSocket websocket) {
        if (attempts < 5) {
            attempts++;
            CointCore.LOG.info("Попытка реконнекта {} из {} через {} сек...\n", attempts, 5, 5000 / 1000);

            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    websocket.connectAsynchronously();
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
            }).start();
        } else {
            CointCore.LOG.info("Достигнуто максимальное количество попыток реконнекта. Стоп.");
        }
    }
}
