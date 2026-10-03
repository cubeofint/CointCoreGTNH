package coint.integration.discord;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.entity.player.EntityPlayerMP;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import coint.CointConfig;
import coint.CointCore;

public final class DiscordModerationWebhook {

    private static final String API_BASE = "https://discord.com/api/v10/channels/";
    private static ExecutorService executor;

    private DiscordModerationWebhook() {}

    public static void logGlobal(EntityPlayerMP sender, String text) {
        if (!CointConfig.discord.enabled || !CointConfig.discord.sendGlobalChat) return;

        String message = sender.getCommandSenderName() + ": " + stripFormatting(text);
        sendTo(mainChannelId(), message);
    }

    public static void logLocal(EntityPlayerMP sender, String text) {
        if (!CointConfig.discord.enabled || !CointConfig.discord.sendLocalChat) return;

        String message = sender.getCommandSenderName() + " (dim:" + sender.dimension + "): " + stripFormatting(text);
        sendTo(CointConfig.discord.logChannelId, message);
    }

    public static void logDm(String sender, String target, String text) {
        if (!CointConfig.discord.enabled || !CointConfig.discord.sendPrivateChat) return;

        String message = stripFormatting(sender) + " -> " + stripFormatting(target) + ": " + stripFormatting(text);
        sendTo(CointConfig.discord.logChannelId, message);
    }

    public static synchronized void shutdown() {
        DiscordGlobalBridge.shutdown();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    static String mainChannelId() {
        String channelId = trim(CointConfig.discord.channelId);
        if (!channelId.isEmpty()) return channelId;
        return trim(CointConfig.discord.globalChannelId);
    }

    private static void sendTo(String configuredChannelId, String content) {
        String token = trim(CointConfig.discord.botToken);
        String channelId = trim(configuredChannelId);
        if (token.isEmpty() || channelId.isEmpty()) return;
        if (!channelId.matches("\\d{15,25}")) {
            CointCore.LOG.warn("[Discord] Invalid Discord channel ID: {}", channelId);
            return;
        }

        executor().execute(() -> post(token, channelId, content));
    }

    private static synchronized ExecutorService executor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "CointCore-DiscordSend");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    private static void post(String token, String channelId, String content) {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", content);

        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", allowedMentions);

        byte[] data = payload.toString()
            .getBytes(StandardCharsets.UTF_8);
        String endpoint = API_BASE + channelId + "/messages";

        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Authorization", "Bot " + token);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("User-Agent", "CointCoreGTNH");
                connection.setFixedLengthStreamingMode(data.length);

                try (OutputStream output = connection.getOutputStream()) {
                    output.write(data);
                }

                int response = connection.getResponseCode();
                if (response >= 200 && response < 300) return;

                if (response == 429 && attempt < 2) {
                    sleepRetry(connection.getHeaderField("Retry-After"));
                    continue;
                }

                if (response >= 500 && response < 600 && attempt < 2) {
                    sleepRetry(null);
                    continue;
                }

                CointCore.LOG.warn("[Discord] Discord API returned HTTP {} for channel {}", response, channelId);
                return;
            } catch (Exception e) {
                if (attempt >= 2) {
                    CointCore.LOG.warn("[Discord] Failed to send message to channel {}: {}", channelId, e.getMessage());
                    return;
                }
                sleepRetry(null);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }

    private static void sleepRetry(String retryAfter) {
        long millis = 1000L;
        if (retryAfter != null && !retryAfter.trim()
            .isEmpty()) {
            try {
                millis = Math.max(250L, Math.min(10000L, (long) (Double.parseDouble(retryAfter.trim()) * 1000.0D)));
            } catch (NumberFormatException ignored) {}
        }

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static String stripFormatting(String text) {
        if (text == null || text.isEmpty()) return "";

        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A7' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
                    || (code >= 'k' && code <= 'o')
                    || code == 'r') {
                    i++;
                    continue;
                }
            }
            clean.append(c);
        }
        return clean.toString();
    }
}
