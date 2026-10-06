package coint.integration.discord;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

@EventBusSubscriber
public final class DiscordServerStatus {

    private static final String API_BASE = "https://discord.com/api/v10/channels/";
    private static final String STATUS_TITLE = "Статус серверов";
    private static final String STATUS_MARKER = "CointCoreGTNH status";
    private static final Pattern ONLINE_PATTERN = Pattern.compile("Игроков:\\s*(\\d+)");
    private static final AtomicBoolean UPDATE_QUEUED = new AtomicBoolean();

    private static ExecutorService executor;
    private static volatile String statusMessageId;
    private static volatile String activeConfigKey = "";
    private static volatile String lastSnapshotKey = "";
    private static volatile long lastUpdateMillis;
    private static volatile long lastWarningMillis;
    private static volatile boolean started;
    private static int tickCounter;

    private DiscordServerStatus() {}

    public static synchronized void start() {
        if (started) return;
        started = true;
        tickCounter = 0;
        lastUpdateMillis = 0L;
        lastSnapshotKey = "";

        ConfigState config = config();
        if (!config.valid) return;

        activeConfigKey = config.key;
        Snapshot snapshot = snapshot(MinecraftServer.getServer(), true, config);
        queue(snapshot, config, true);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = MinecraftServer.getServer();
        if (!started || server == null) return;
        if (++tickCounter < 20) return;
        tickCounter = 0;

        ConfigState config = config();
        if (!config.valid) return;

        if (!config.key.equals(activeConfigKey)) {
            activeConfigKey = config.key;
            statusMessageId = null;
            lastUpdateMillis = 0L;
            lastSnapshotKey = "";
        }

        Snapshot snapshot = snapshot(server, true, config);
        long now = System.currentTimeMillis();
        long intervalMillis = Math.max(5000L, config.updateSeconds * 1000L);
        if (now - lastUpdateMillis >= intervalMillis || !snapshot.key.equals(lastSnapshotKey)) {
            queue(snapshot, config, false);
        }
    }

    public static synchronized void shutdown() {
        if (!started) {
            shutdownExecutor();
            return;
        }
        started = false;

        ConfigState config = config();
        if (config.valid) {
            Snapshot snapshot = snapshot(null, false, config);
            try {
                Future<?> future = executor().submit(() -> {
                    updateStatus(snapshot, config);
                    if (config.announceLifecycle) {
                        postPlainMessage(config, lifecycleText(config, false));
                    }
                });
                future.get(8L, TimeUnit.SECONDS);
            } catch (Exception e) {
                warnRateLimited("[DiscordStatus] Failed to publish shutdown state: {}", e.getMessage());
            }
        }

        shutdownExecutor();
        statusMessageId = null;
        activeConfigKey = "";
        lastSnapshotKey = "";
        lastUpdateMillis = 0L;
        tickCounter = 0;
        UPDATE_QUEUED.set(false);
    }

    private static void queue(Snapshot snapshot, ConfigState config, boolean announceStart) {
        if (!UPDATE_QUEUED.compareAndSet(false, true)) return;

        lastUpdateMillis = System.currentTimeMillis();
        lastSnapshotKey = snapshot.key;
        executor().execute(() -> {
            try {
                updateStatus(snapshot, config);
                if (announceStart && config.announceLifecycle) {
                    postPlainMessage(config, lifecycleText(config, true));
                }
            } catch (Throwable t) {
                warnRateLimited("[DiscordStatus] Update failed: {}", t.getMessage());
            } finally {
                UPDATE_QUEUED.set(false);
            }
        });
    }

    private static Snapshot snapshot(MinecraftServer server, boolean running, ConfigState config) {
        List<String> players = new ArrayList<>();
        if (running && server != null && server.getConfigurationManager() != null) {
            for (Object value : server.getConfigurationManager().playerEntityList) {
                if (value instanceof EntityPlayerMP) {
                    String name = ((EntityPlayerMP) value).getCommandSenderName();
                    if (name != null && !name.trim()
                        .isEmpty()) players.add(name.trim());
                }
            }
        }
        Collections.sort(players, String.CASE_INSENSITIVE_ORDER);
        return new Snapshot(config.serverName, running, players);
    }

    private static void updateStatus(Snapshot snapshot, ConfigState config) {
        try {
            StatusMessage current = resolveStatusMessage(config);
            JsonObject payload = buildStatusPayload(current == null ? null : current.message, snapshot, config);

            if (current == null) {
                warnRateLimited(
                    "[DiscordStatus] Existing status message was not found; no new message will be created",
                    new Object[0]);
                return;
            }

            HttpResult edited = requestWithRetry(
                "PATCH",
                API_BASE + config.channelId + "/messages/" + current.id,
                config.token,
                payload);
            if (edited.success()) {
                statusMessageId = current.id;
                return;
            }

            if (edited.code == 403 || edited.code == 404) statusMessageId = null;
            warnRateLimited("[DiscordStatus] Discord API returned HTTP {} while editing status", edited.code);
        } catch (Exception e) {
            warnRateLimited("[DiscordStatus] Failed to update status: {}", e.getMessage());
        }
    }

    private static StatusMessage resolveStatusMessage(ConfigState config) throws IOException {
        String cached = statusMessageId;
        if (cached != null && !cached.isEmpty()) {
            HttpResult response = requestWithRetry(
                "GET",
                API_BASE + config.channelId + "/messages/" + cached,
                config.token,
                null);
            if (response.success()) {
                JsonObject message = parseObject(response.body);
                if (message != null) return new StatusMessage(cached, message);
            }
            if (response.code == 403 || response.code == 404) statusMessageId = null;
        }

        StatusMessage pinned = resolvePinnedStatusMessage(config);
        if (pinned != null) return pinned;

        HttpResult response = requestWithRetry(
            "GET",
            API_BASE + config.channelId + "/messages?limit=100",
            config.token,
            null);
        if (!response.success()) return null;

        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(response.body);
        } catch (RuntimeException ignored) {
            return null;
        }
        if (!parsed.isJsonArray()) return null;

        JsonObject pinnedFallback = null;
        JsonObject fallback = null;
        for (JsonElement element : parsed.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject message = element.getAsJsonObject();
            if (!isBotMessage(message)) continue;
            JsonObject embed = firstEmbed(message);
            if (embed == null || !embed.has("title")
                || !STATUS_TITLE.equals(
                    embed.get("title")
                        .getAsString()))
                continue;

            if (hasMarker(embed)) {
                String id = message.get("id")
                    .getAsString();
                statusMessageId = id;
                return new StatusMessage(id, message);
            }
            if (pinnedFallback == null && message.has("pinned")
                && message.get("pinned")
                    .getAsBoolean()) {
                pinnedFallback = message;
            }
            if (fallback == null) fallback = message;
        }

        JsonObject selected = pinnedFallback == null ? fallback : pinnedFallback;
        if (selected != null && selected.has("id")) {
            String id = selected.get("id")
                .getAsString();
            statusMessageId = id;
            return new StatusMessage(id, selected);
        }
        return null;
    }

    private static StatusMessage resolvePinnedStatusMessage(ConfigState config) throws IOException {
        HttpResult response = requestWithRetry(
            "GET",
            API_BASE + config.channelId + "/messages/pins?limit=50",
            config.token,
            null);
        if (!response.success()) return null;

        JsonObject root = parseObject(response.body);
        if (root == null || !root.has("items")
            || !root.get("items")
                .isJsonArray())
            return null;

        JsonObject fallback = null;
        for (JsonElement element : root.getAsJsonArray("items")) {
            if (!element.isJsonObject()) continue;
            JsonObject pin = element.getAsJsonObject();
            if (!pin.has("message") || !pin.get("message")
                .isJsonObject()) continue;

            JsonObject message = pin.getAsJsonObject("message");
            if (!isBotMessage(message)) continue;
            JsonObject embed = firstEmbed(message);
            if (embed == null || !embed.has("title")
                || !STATUS_TITLE.equals(
                    embed.get("title")
                        .getAsString()))
                continue;

            if (hasMarker(embed) && message.has("id")) {
                String id = message.get("id")
                    .getAsString();
                statusMessageId = id;
                return new StatusMessage(id, message);
            }
            if (fallback == null && message.has("id")) fallback = message;
        }

        if (fallback != null) {
            String id = fallback.get("id")
                .getAsString();
            statusMessageId = id;
            return new StatusMessage(id, fallback);
        }
        return null;
    }

    private static JsonObject buildStatusPayload(JsonObject current, Snapshot snapshot, ConfigState config) {
        JsonArray fields = new JsonArray();
        boolean replaced = false;

        JsonObject oldEmbed = firstEmbed(current);
        if (oldEmbed != null && oldEmbed.has("fields")
            && oldEmbed.get("fields")
                .isJsonArray()) {
            for (JsonElement element : oldEmbed.getAsJsonArray("fields")) {
                if (!element.isJsonObject()) continue;
                JsonObject field = element.getAsJsonObject();
                String name = field.has("name") ? field.get("name")
                    .getAsString() : "";
                if (matchesServerField(name, snapshot.serverName)) {
                    if (!replaced) {
                        fields.add(buildServerField(snapshot));
                        replaced = true;
                    }
                } else if (fields.size() < 24) {
                    fields.add(field);
                }
            }
        }

        if (!replaced && fields.size() < 25) fields.add(buildServerField(snapshot));

        int totalOnline = 0;
        for (JsonElement element : fields) {
            if (!element.isJsonObject()) continue;
            JsonObject field = element.getAsJsonObject();
            if (!field.has("value")) continue;
            totalOnline += parseOnline(
                field.get("value")
                    .getAsString());
        }

        JsonObject embed = new JsonObject();
        embed.addProperty("title", STATUS_TITLE);
        embed.addProperty(
            "description",
            "Общий онлайн игроков: " + totalOnline
                + ".\nИнформация обновляется раз "
                + config.updateSeconds
                + " сек.\nСлоты на серверах измеряются в командах (SU Teams).");
        embed.add("fields", fields);

        JsonObject footer = new JsonObject();
        footer.addProperty("text", STATUS_MARKER);
        embed.add("footer", footer);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject payload = new JsonObject();
        payload.add("embeds", embeds);
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", allowedMentions);
        return payload;
    }

    private static JsonObject buildServerField(Snapshot snapshot) {
        JsonObject field = new JsonObject();
        field.addProperty("name", snapshot.serverName + " — " + (snapshot.running ? "включен" : "выключен"));
        field.addProperty("value", serverFieldValue(snapshot));
        field.addProperty("inline", false);
        return field;
    }

    private static String serverFieldValue(Snapshot snapshot) {
        if (!snapshot.running) return "Игроков: 0\nСервер выключен";
        if (snapshot.players.isEmpty()) return "Игроков: 0\nЗдесь пусто(";

        StringBuilder value = new StringBuilder();
        value.append("Игроков: ")
            .append(snapshot.players.size())
            .append('\n');
        for (String player : snapshot.players) {
            if (value.length() > 950) {
                value.append("…");
                break;
            }
            if (value.charAt(value.length() - 1) != '\n') value.append(", ");
            value.append(player);
        }
        return value.toString();
    }

    private static int parseOnline(String value) {
        if (value == null) return 0;
        Matcher matcher = ONLINE_PATTERN.matcher(value);
        if (!matcher.find()) return 0;
        try {
            return Math.max(0, Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean matchesServerField(String fieldName, String serverName) {
        if (fieldName == null || serverName == null) return false;
        if (fieldName.equalsIgnoreCase(serverName)) return true;
        String prefix = serverName + " — ";
        return fieldName.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isBotMessage(JsonObject message) {
        if (message == null || !message.has("author")
            || !message.get("author")
                .isJsonObject())
            return false;
        JsonObject author = message.getAsJsonObject("author");
        return author.has("bot") && author.get("bot")
            .getAsBoolean();
    }

    private static boolean hasMarker(JsonObject embed) {
        if (!embed.has("footer") || !embed.get("footer")
            .isJsonObject()) return false;
        JsonObject footer = embed.getAsJsonObject("footer");
        return footer.has("text") && STATUS_MARKER.equals(
            footer.get("text")
                .getAsString());
    }

    private static JsonObject firstEmbed(JsonObject message) {
        if (message == null || !message.has("embeds")
            || !message.get("embeds")
                .isJsonArray())
            return null;
        JsonArray embeds = message.getAsJsonArray("embeds");
        if (embeds.size() == 0 || !embeds.get(0)
            .isJsonObject()) return null;
        return embeds.get(0)
            .getAsJsonObject();
    }

    private static void postPlainMessage(ConfigState config, String text) {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", text);
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", allowedMentions);

        try {
            HttpResult response = requestWithRetry(
                "POST",
                API_BASE + config.channelId + "/messages",
                config.token,
                payload);
            if (!response.success()) {
                warnRateLimited(
                    "[DiscordStatus] Discord API returned HTTP {} while sending lifecycle message",
                    response.code);
            }
        } catch (IOException e) {
            warnRateLimited("[DiscordStatus] Failed to send lifecycle message: {}", e.getMessage());
        }
    }

    private static String lifecycleText(ConfigState config, boolean online) {
        String label = config.tag.isEmpty() ? config.serverName : config.tag;
        return "[" + label + "] сервер " + (online ? "включился!" : "выключился");
    }

    private static ConfigState config() {
        String token = trim(CointConfig.discord.botToken);
        String channelId = trim(CointConfig.discord.statusChannelId);
        if (channelId.isEmpty()) channelId = trim(CointConfig.discord.channelId);
        if (channelId.isEmpty()) channelId = trim(CointConfig.discord.globalChannelId);

        String tag = cleanLabel(CointConfig.api.serverTag);
        String serverName = cleanLabel(CointConfig.discord.statusServerName);
        if (serverName.isEmpty()) serverName = defaultServerName(tag);
        if (serverName.isEmpty()) serverName = "Server";

        int updateSeconds = Math.max(5, CointConfig.discord.statusUpdateSeconds);
        boolean valid = CointConfig.discord.enabled && !token.isEmpty() && channelId.matches("\\d{15,25}");
        String key = Integer.toHexString(
            token.hashCode()) + "\n" + channelId + "\n" + serverName + "\n" + tag + "\n" + updateSeconds;
        return new ConfigState(
            token,
            channelId,
            serverName,
            tag,
            updateSeconds,
            CointConfig.discord.announceServerLifecycle,
            key,
            valid);
    }

    private static String defaultServerName(String tag) {
        if (tag == null) return "";
        if (tag.equalsIgnoreCase("A")) return "Alpha";
        if (tag.equalsIgnoreCase("E") || tag.equalsIgnoreCase("END")) return "End";
        return tag;
    }

    private static String cleanLabel(String value) {
        String clean = trim(value).replace('§', '?')
            .replace('\r', ' ')
            .replace('\n', ' ');
        clean = clean.replaceAll("\\s{2,}", " ")
            .trim();
        if (clean.length() > 80) clean = clean.substring(0, 80);
        return clean;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static synchronized ExecutorService executor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "CointCore-DiscordStatus");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    private static synchronized void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static HttpResult requestWithRetry(String method, String endpoint, String token, JsonObject payload)
        throws IOException {
        HttpResult last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            last = request(method, endpoint, token, payload);
            if (last.code != 429 && last.code < 500) return last;
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
                return last;
            }
        }
        return last == null ? new HttpResult(599, "") : last;
    }

    private static HttpResult request(String method, String endpoint, String token, JsonObject payload)
        throws IOException {
        byte[] data = payload == null ? null
            : payload.toString()
                .getBytes(StandardCharsets.UTF_8);
        if ("PATCH".equals(method)) return patch(endpoint, token, data == null ? new byte[0] : data);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Authorization", "Bot " + token);
            connection.setRequestProperty("User-Agent", "CointCoreGTNH");
            connection.setRequestProperty("Accept", "application/json");

            if (data != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(data);
                }
            }

            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readAll(input);
            return new HttpResult(code, body);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpResult patch(String endpoint, String token, byte[] data) throws IOException {
        URL url = new URL(endpoint);
        int port = url.getPort() > 0 ? url.getPort() : 443;
        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault()
            .createSocket(url.getHost(), port);
        try {
            socket.setSoTimeout(7000);
            socket.setTcpNoDelay(true);
            socket.startHandshake();

            String path = url.getFile();
            if (path == null || path.isEmpty()) path = "/";
            String host = port == 443 ? url.getHost() : url.getHost() + ":" + port;
            String headers = "PATCH " + path
                + " HTTP/1.1\r\n"
                + "Host: "
                + host
                + "\r\n"
                + "Authorization: Bot "
                + token
                + "\r\n"
                + "User-Agent: CointCoreGTNH\r\n"
                + "Accept: application/json\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n"
                + "Content-Length: "
                + data.length
                + "\r\n"
                + "Connection: close\r\n\r\n";

            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(data);
            output.flush();

            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String statusLine = readAsciiLine(input);
            if (statusLine == null || statusLine.isEmpty()) return new HttpResult(599, "");

            String[] parts = statusLine.split(" ", 3);
            int code;
            try {
                code = parts.length >= 2 ? Integer.parseInt(parts[1]) : 599;
            } catch (NumberFormatException ignored) {
                code = 599;
            }

            String line;
            while ((line = readAsciiLine(input)) != null && !line.isEmpty()) {}
            return new HttpResult(code, "");
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) >= 0) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        boolean readAny = false;
        while ((value = input.read()) >= 0) {
            readAny = true;
            if (value == '\n') break;
            if (value != '\r') line.append((char) value);
        }
        if (!readAny && value < 0) return null;
        return line.toString();
    }

    private static JsonObject parseObject(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonElement parsed = new JsonParser().parse(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void warnRateLimited(String message, Object... args) {
        long now = System.currentTimeMillis();
        if (now - lastWarningMillis < 60000L) return;
        lastWarningMillis = now;
        CointCore.LOG.warn(message, args);
    }

    private static final class Snapshot {

        private final String serverName;
        private final boolean running;
        private final List<String> players;
        private final String key;

        private Snapshot(String serverName, boolean running, List<String> players) {
            this.serverName = serverName;
            this.running = running;
            this.players = players;
            this.key = serverName + "\n" + running + "\n" + players.toString();
        }
    }

    private static final class ConfigState {

        private final String token;
        private final String channelId;
        private final String serverName;
        private final String tag;
        private final int updateSeconds;
        private final boolean announceLifecycle;
        private final String key;
        private final boolean valid;

        private ConfigState(String token, String channelId, String serverName, String tag, int updateSeconds,
            boolean announceLifecycle, String key, boolean valid) {
            this.token = token;
            this.channelId = channelId;
            this.serverName = serverName;
            this.tag = tag;
            this.updateSeconds = updateSeconds;
            this.announceLifecycle = announceLifecycle;
            this.key = key;
            this.valid = valid;
        }
    }

    private static final class StatusMessage {

        private final String id;
        private final JsonObject message;

        private StatusMessage(String id, JsonObject message) {
            this.id = id;
            this.message = message;
        }
    }

    private static final class HttpResult {

        private final int code;
        private final String body;

        private HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        private boolean success() {
            return code >= 200 && code < 300;
        }
    }
}
