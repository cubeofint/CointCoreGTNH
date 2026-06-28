package coint.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import net.minecraft.server.MinecraftServer;

import com.google.gson.Gson;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketCloseCode;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import coint.CointConfig;
import coint.CointCore;
import coint.util.PlayerUtil;

public class HubWebSocket extends WebSocketAdapter {

    private static HubWebSocket inst;
    private static WebSocket ws;

    private static int attempts = 0;
    private static Gson gson;
    private static MinecraftServer server;

    public static HubWebSocket get() {
        if (inst == null || ws == null) {
            try {
                inst = create(CointConfig.api.getChatWs());
            } catch (IOException | URISyntaxException | WebSocketException e) {
                CointCore.LOG.error("Hub-ws create error:\n{}", e.getMessage());
            }
        }
        return inst;
    }

    public static HubWebSocket create(URI uri) throws IOException, WebSocketException {
        if (!CointConfig.api.wsEnabled) {
            return new HubWebSocket();
        }
        gson = new Gson();
        server = MinecraftServer.getServer();

        WebSocketFactory factory = new WebSocketFactory();
        var adapter = new HubWebSocket();
        ws = factory.createSocket(uri);
        ws.addListener(adapter);
        ws.connect();

        return adapter;
    }

    private HubWebSocket() {}

    public void send(WebSocketMessage msg) {
        if (!CointConfig.api.wsEnabled) return;
        String json = gson.toJson(msg);
        ws.sendText(json);
    }

    public void sendInfo() {
        if (!CointConfig.api.wsEnabled) return;
        String json = gson.toJson(WebSocketMessage.InfoMessage.create(server));
        ws.sendText(json);
    }

    public void recreate() throws IOException, WebSocketException {
        if (!CointConfig.api.wsEnabled) return;
        ws = ws.recreate();
        ws.connect();
    }

    public void closeNormal(String reason) {
        if (!CointConfig.api.wsEnabled) return;
        ws.sendClose(WebSocketCloseCode.NORMAL, reason);
    }

    @Override
    public void onTextMessage(WebSocket websocket, String text) throws Exception {
        if (!CointConfig.api.wsEnabled) return;
        WebSocketMessage msg = gson.fromJson(text, WebSocketMessage.class);
        switch (msg.action) {
            case Chat -> {
                if (server == null) {
                    return;
                }
                var chat = gson.fromJson(msg.payload, WebSocketMessage.ChatMessage.class);
                var c = PlayerUtil.getChatMessage(chat.senderFormatted, chat.text, true, msg.origin);
                server.getConfigurationManager()
                    .sendChatMsg(c);
            }
            case Info -> {
                if (CointConfig.general.isNew) {
                    // TabChannelHandler.INSTANCE.setServerData("");
                    CointCore.LOG.warn("Unimplemented info");
                }
            }
            default -> {
                CointCore.LOG.warn("Unimplemented action: {}", msg.action);
            }
        }
    }

    @Override
    public void onConnected(WebSocket websocket, Map<String, List<String>> headers) throws Exception {
        if (!CointConfig.api.wsEnabled) return;
        attempts = 0;
        String init = gson.toJson(WebSocketMessage.InfoMessage.create(server));
        websocket.sendText(init);

        CointCore.LOG.info("Hub-ws connection established");
    }

    @Override
    public void onConnectError(WebSocket websocket, WebSocketException exception) throws Exception {
        if (!CointConfig.api.wsEnabled) return;
        CointCore.LOG.error("Hub-ws connection error: {}", exception.getMessage());
        tryReconnect();
    }

    @Override
    public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame,
        boolean closedByServer) throws Exception {
        if (!CointConfig.api.wsEnabled) return;
        if (!closedByServer && clientCloseFrame != null) {
            CointCore.LOG.info("Hub-ws closed");
            return;
        }

        tryReconnect();
    }

    private void tryReconnect() {
        if (attempts < 5) {
            attempts++;
            CointCore.LOG.info("Hub-ws reconnect attempt {}...", attempts);

            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    recreate();
                } catch (InterruptedException | IOException | WebSocketException e) {
                    Thread.currentThread()
                        .interrupt();
                }
            }).start();
        } else {
            CointCore.LOG.info("Hub-ws is unavailable.");
        }
    }
}
