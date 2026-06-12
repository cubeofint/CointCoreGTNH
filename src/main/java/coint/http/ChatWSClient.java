package coint.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import net.minecraft.server.MinecraftServer;

import com.google.gson.Gson;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;

import coint.CointConfig;
import coint.CointCore;

public class ChatWSClient {

    public static WebSocket ws;

    static Gson gson;
    static MinecraftServer server;

    public static void init() {
        gson = new Gson();
        server = MinecraftServer.getServer();

        URI uri = null;
        try {
            uri = CointConfig.api.getChatWs();
        } catch (URISyntaxException e) {
            CointCore.LOG.error(e.getMessage());
        }

        WebSocketFactory factory = new WebSocketFactory();
        try {
            ws = factory.createSocket(uri);
            ws.connect();
            ws.addListener(new HubWSAdapter());
        } catch (IOException | WebSocketException e) {
            throw new RuntimeException(e);
        }
    }

    public static void send(String sender, String senderFormatted, String text) {
        String msg = gson.toJson(new WSMessage(sender, senderFormatted, text));
        ws.sendText(msg);
    }

    public static void close() {
        ws.sendClose();
    }

    public static class WSMessage {

        public String server;
        public String sender;
        public String senderFormatted;
        public String text;

        public WSMessage(String sender, String senderFormatted, String text) {
            this.server = CointConfig.api.serverTag;
            this.sender = sender;
            this.senderFormatted = senderFormatted;
            this.text = text;
        }
    }
}
