package coint.core;

import java.net.URI;
import java.net.URISyntaxException;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.Gson;

import coint.CointCore;
import coint.config.CointConfig;

public class ChatWSClient extends WebSocketClient {

    public static ChatWSClient inst;

    static Gson gson;
    MinecraftServer server;

    public static void init() {
        if (gson == null) {
            gson = new Gson();
        }
        if (!CointConfig.wsHubEnabled) {
            inst = null;
            CointCore.LOG.info("[Hub WS] Отключено в cointcore.cfg (wsHubEnabled=false)");
            return;
        }

        URI uri;
        try {
            uri = new URI(CointConfig.wsHubUrl);
        } catch (URISyntaxException e) {
            CointCore.LOG.error("[Hub WS] Некорректный wsHubUrl: {}", e.getMessage());
            inst = null;
            return;
        }

        inst = new ChatWSClient(uri);
        inst.connect();
    }

    public static void send(String sender, String senderFormatted, String text) {
        if (!CointConfig.wsHubEnabled || inst == null) {
            return;
        }
        if (gson == null) {
            gson = new Gson();
        }
        if (!inst.isOpen()) {
            return;
        }
        try {
            String msg = gson.toJson(new WSMessage(sender, senderFormatted, text));
            inst.send(msg);
        } catch (Exception e) {
            CointCore.LOG.warn("[Hub WS] Не удалось отправить сообщение: {}", e.getMessage());
        }
    }

    public ChatWSClient(URI serverUri) {
        super(serverUri);
        server = MinecraftServer.getServer();
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        String greeting = gson.toJson(new WSMessage("server", "server", "greeting"));
        inst.send(greeting);

        CointCore.LOG.info("Hub WS connection established");
    }

    @Override
    public void onMessage(String message) {
        if (!CointConfig.wsHubEnabled || server == null) {
            return;
        }

        WSMessage msg = gson.fromJson(message, WSMessage.class);
        if (msg == null) {
            return;
        }

        IChatComponent c = new ChatComponentText(
            EnumChatFormatting.GREEN + "[" + msg.server + "] " + EnumChatFormatting.RESET).appendText(msg.sender + ": ")
                .appendText(msg.text);

        server.getConfigurationManager()
            .sendChatMsg(c);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        CointCore.LOG.info("Hub WS connection closed{}: {} {}", remote ? " from remote" : "", code, reason);

        new Thread(() -> {
            int attempts = 0;

            while (attempts < 5) {
                try {
                    Thread.sleep(5000);
                    if (!CointConfig.wsHubEnabled) {
                        CointCore.LOG.info("[Hub WS] Повторное подключение отменено (wsHubEnabled=false)");
                        return;
                    }
                    CointCore.LOG.info("Hub ws reconnection attempt {}", attempts + 1);
                    if (inst != null && inst.reconnectBlocking()) {
                        CointCore.LOG.info("Hub ws reconnected");
                        return;
                    }
                } catch (InterruptedException e) {
                    CointCore.LOG.info(e.getMessage());
                    Thread.currentThread()
                        .interrupt();
                    return;
                }
                attempts++;
            }
            CointCore.LOG.info("Hub ws reconnection fail");
        }).start();
    }

    @Override
    public void onError(Exception ex) {
        CointCore.LOG.error(ex.getMessage());
    }

    public static class WSMessage {

        public String server;
        public String sender;
        public String senderFormatted;
        public String text;

        public WSMessage(String sender, String senderFormatted, String text) {
            this.server = CointConfig.thisServer;
            this.sender = sender;
            this.senderFormatted = senderFormatted;
            this.text = text;
        }
    }
}
