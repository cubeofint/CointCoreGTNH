package coint.integration.discord;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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

        String prefix = coint.cluster.ClusterService.isRunning()
            ? "[" + stripFormatting(coint.cluster.ClusterService.displayName()) + "] "
            : "";
        String message = prefix + sender.getCommandSenderName() + ": " + stripFormatting(text);
        sendTo(mainChannelId(), message);
    }

    public static void logAnnouncement(String node, String text) {
        if (!CointConfig.discord.enabled || !CointConfig.discord.sendGlobalChat) return;
        sendTo(mainChannelId(), "[" + stripFormatting(node) + "] " + stripFormatting(text));
    }

    /** Global chat of another server of the network, relayed by the server that runs the Discord bridge. */
    public static void logRemoteGlobal(String serverName, String sender, String text) {
        if (!CointConfig.discord.enabled || !CointConfig.discord.sendGlobalChat) return;

        String message = "[" + stripFormatting(
            serverName) + "] " + stripFormatting(sender) + ": " + stripFormatting(text);
        sendTo(mainChannelId(), message);
    }

    public static void logLocal(EntityPlayerMP sender, String text) {
        if (!CointConfig.discord.enabled || !CointConfig.discord.sendLocalChat) return;

        String message = stripFormatting(coint.cluster.ClusterSpy.label(sender.getCommandSenderName())) + " (dim:"
            + sender.dimension
            + "): "
            + stripFormatting(text);
        sendTo(CointConfig.discord.logChannelId, message);
    }

    public static void logDm(String sender, String target, String text) {
        if (!CointConfig.discord.enabled || !CointConfig.discord.sendPrivateChat) return;

        String message = stripFormatting(sender) + " -> " + stripFormatting(target) + ": " + stripFormatting(text);
        sendTo(CointConfig.discord.logChannelId, message);
    }

    /** A finished player trade, to the trade log channel (as is: the text carries no chat colours). */
    public static void logTrade(String text) {
        logTrade(text, null);
    }

    /** Full report is attached, while a short spoiler keeps the channel readable. */
    public static void logTrade(String text, String report) {
        if (!CointConfig.discord.enabled) return;
        boolean attach = report != null && !report.isEmpty() || text.length() > 1900;
        if (!attach) {
            sendTo(CointConfig.trade.discordChannelId, text);
            return;
        }
        String message = text.length() > 1000 ? text.substring(0, 1000) + "\n…" : text;
        if (report != null && !report.isEmpty()) {
            // The report begins with the full trade summary; preview only the inventory sections.
            int start = report.indexOf("Содержимое хранилищ,");
            String preview = start < 0 ? report : report.substring(start);
            String[] previewLines = preview.split("\n", 11);
            if (previewLines.length > 10)
                preview = String.join("\n", java.util.Arrays.copyOf(previewLines, 10)) + "\n…";
            preview = preview.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replace("`", "'")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("[", "\\[");
            if (preview.length() > 650) preview = preview.substring(0, 650) + "\n…";
            if (preview.endsWith("\\")) preview += " ";
            message += "\n**Содержимое (нажмите, чтобы раскрыть):**\n> ||" + preview.replace("\n", "\n> ") + "||";
        }
        message += "\nПолный отчёт — в приложенном файле.";
        sendTo(
            CointConfig.trade.discordChannelId,
            message,
            "trade-details.txt",
            (report == null || report.isEmpty() ? text : report).getBytes(StandardCharsets.UTF_8));
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
        sendTo(configuredChannelId, content, null, null);
    }

    private static void sendTo(String configuredChannelId, String content, String fileName, byte[] file) {
        String token = trim(CointConfig.discord.botToken);
        String channelId = trim(configuredChannelId);
        if (token.isEmpty() || channelId.isEmpty()) return;
        if (!channelId.matches("\\d{15,25}")) {
            CointCore.LOG.warn("[Discord] Invalid Discord channel ID: {}", channelId);
            return;
        }

        executor().execute(() -> post(token, channelId, content, fileName, file));
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

    private static void post(String token, String channelId, String content, String fileName, byte[] file) {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", content);
        if (file != null) {
            // Reuse the same nonce on retries after a lost HTTP response; do not duplicate the attachment log.
            payload.addProperty(
                "nonce",
                Long.toUnsignedString(
                    java.util.concurrent.ThreadLocalRandom.current()
                        .nextLong()));
            payload.addProperty("enforce_nonce", true);
        }

        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", allowedMentions);

        String boundary = "CointCore-" + UUID.randomUUID();
        byte[] data;
        if (file == null) {
            data = payload.toString()
                .getBytes(StandardCharsets.UTF_8);
        } else {
            try (ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                body.write(
                    ("--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                        + "Content-Type: application/json\r\n\r\n"
                        + payload
                        + "\r\n--"
                        + boundary
                        + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\""
                        + fileName
                        + "\"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(file);
                body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                data = body.toByteArray();
            } catch (java.io.IOException e) {
                CointCore.LOG.warn("[Discord] Could not prepare trade attachment", e);
                return;
            }
        }
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
                connection.setRequestProperty(
                    "Content-Type",
                    file == null ? "application/json; charset=UTF-8" : "multipart/form-data; boundary=" + boundary);
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
