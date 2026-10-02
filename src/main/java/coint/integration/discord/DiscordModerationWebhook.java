package coint.integration.discord;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import coint.CointConfig;
import coint.CointCore;

public final class DiscordModerationWebhook {

    private static final String API_BASE = "https://discord.com/api/v10/channels/";
    private static ExecutorService executor;

    private DiscordModerationWebhook() {}

    public static void logLocal(EntityPlayerMP sender, String text) {
        if (!CointConfig.discord.enabled || !CointConfig.discord.sendLocalChat) return;

        String cleanText = stripFormatting(text);
        String message = "[" + CointConfig.api.serverTag
            + "] [LOCAL] "
            + sender.getCommandSenderName()
            + " (dim:"
            + sender.dimension
            + " x:"
            + MathHelper.floor_double(sender.posX)
            + " y:"
            + MathHelper.floor_double(sender.posY)
            + " z:"
            + MathHelper.floor_double(sender.posZ)
            + "): "
            + cleanText;
        send(message);
    }

    public static void logDm(String sender, String target, String text) {
        if (!CointConfig.discord.enabled || !CointConfig.discord.sendPrivateChat) return;

        String message = "[" + CointConfig.api.serverTag
            + "] [DM] "
            + stripFormatting(sender)
            + " -> "
            + stripFormatting(target)
            + ": "
            + stripFormatting(text);
        send(message);
    }

    public static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    private static void send(String content) {
        String token = trim(CointConfig.discord.botToken);
        String channelId = trim(CointConfig.discord.logChannelId);
        if (token.isEmpty() || channelId.isEmpty()) return;
        if (!channelId.matches("\\d{15,25}")) {
            CointCore.LOG.warn("[DiscordModeration] Invalid Discord channel ID");
            return;
        }

        executor().execute(() -> post(token, channelId, content));
    }

    private static synchronized ExecutorService executor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "CointCore-DiscordModeration");
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

                CointCore.LOG.warn("[DiscordModeration] Discord API returned HTTP {}", response);
                return;
            } catch (Exception e) {
                if (attempt >= 2) {
                    CointCore.LOG.warn("[DiscordModeration] Failed to send message: {}", e.getMessage());
                    return;
                }
                sleepRetry(null);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
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

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripFormatting(String text) {
        if (text == null) return "";
        String clean = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        return clean == null ? "" : clean;
    }
}
