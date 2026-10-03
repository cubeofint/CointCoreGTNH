package coint.integration.discord;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.util.ChatUtil;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

@EventBusSubscriber
public final class DiscordGlobalBridge {

    private static final String API_BASE = "https://discord.com/api/v10/channels/";
    private static final long DISCORD_EPOCH = 1420070400000L;
    private static final ConcurrentLinkedQueue<InboundMessage> INBOUND = new ConcurrentLinkedQueue<>();

    private static ScheduledExecutorService poller;
    private static volatile String lastMessageId;
    private static volatile String activeConfigKey = "";
    private static int stateCheckTicks;

    private DiscordGlobalBridge() {}

    public static synchronized void start() {
        ensureState();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (++stateCheckTicks >= 20) {
            stateCheckTicks = 0;
            ensureState();
        }

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;

        int processed = 0;
        InboundMessage message;
        while (processed < 50 && (message = INBOUND.poll()) != null) {
            String sender = "§9[Discord]§r " + message.author;
            ChatComponentText component = ChatUtil.getChatMessage(sender, message.text, "G");
            server.getConfigurationManager()
                .sendChatMsg(component);
            CointCore.LOG.info("[GLOBAL/DISCORD] {}: {}", message.author, message.text);
            processed++;
        }
    }

    public static synchronized void shutdown() {
        stopPoller();
        INBOUND.clear();
        activeConfigKey = "";
        stateCheckTicks = 0;
    }

    private static synchronized void ensureState() {
        String token = DiscordModerationWebhook.trim(CointConfig.discord.botToken);
        String channelId = DiscordModerationWebhook.mainChannelId();
        int interval = Math.max(1, CointConfig.discord.pollIntervalSeconds);

        if (!CointConfig.discord.enabled || token.isEmpty() || channelId.isEmpty()) {
            stopPoller();
            activeConfigKey = "";
            return;
        }

        if (!channelId.matches("\\d{15,25}")) {
            stopPoller();
            activeConfigKey = "";
            return;
        }

        String key = token + "\n" + channelId + "\n" + interval;
        if (poller != null && !poller.isShutdown() && key.equals(activeConfigKey)) {
            return;
        }

        stopPoller();
        activeConfigKey = key;
        lastMessageId = snowflakeForNow();
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "CointCore-DiscordChatPoll");
            thread.setDaemon(true);
            return thread;
        });
        poller.scheduleWithFixedDelay(DiscordGlobalBridge::pollSafe, 1L, interval, TimeUnit.SECONDS);
        CointCore.LOG.info("[Discord] Chat bridge started for channel {}", channelId);
    }

    private static void stopPoller() {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
        lastMessageId = null;
    }

    private static void pollSafe() {
        try {
            poll();
        } catch (Throwable t) {
            CointCore.LOG.warn("[Discord] Poll failed: {}", t.getMessage());
        }
    }

    private static void poll() throws Exception {
        if (!CointConfig.discord.enabled) return;

        String token = DiscordModerationWebhook.trim(CointConfig.discord.botToken);
        String channelId = DiscordModerationWebhook.mainChannelId();
        String cursor = lastMessageId;
        if (token.isEmpty() || channelId.isEmpty() || cursor == null) return;

        String endpoint = API_BASE + channelId + "/messages?limit=100&after=" + cursor;
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Authorization", "Bot " + token);
            connection.setRequestProperty("User-Agent", "CointCoreGTNH");

            int response = connection.getResponseCode();
            if (response == 429) return;
            if (response < 200 || response >= 300) {
                CointCore.LOG
                    .warn("[Discord] Discord API returned HTTP {} while reading channel {}", response, channelId);
                return;
            }

            JsonArray array;
            try (InputStream input = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                JsonElement parsed = new JsonParser().parse(reader);
                if (!parsed.isJsonArray()) return;
                array = parsed.getAsJsonArray();
            }

            if (array.size() == 0) return;

            List<JsonObject> messages = new ArrayList<>();
            for (JsonElement element : array) {
                if (element.isJsonObject()) messages.add(element.getAsJsonObject());
            }
            messages.sort(
                Comparator.comparing(
                    o -> o.get("id")
                        .getAsString(),
                    DiscordGlobalBridge::compareSnowflakes));

            String newest = cursor;
            for (JsonObject message : messages) {
                if (!message.has("id")) continue;
                String id = message.get("id")
                    .getAsString();
                if (compareSnowflakes(id, newest) > 0) newest = id;

                JsonObject author = message.has("author") && message.get("author")
                    .isJsonObject() ? message.getAsJsonObject("author") : null;
                if (author == null) continue;
                if (author.has("bot") && author.get("bot")
                    .getAsBoolean()) continue;
                if (message.has("webhook_id") && !message.get("webhook_id")
                    .isJsonNull()) continue;

                String text = message.has("content") && !message.get("content")
                    .isJsonNull() ? message.get("content")
                        .getAsString() : "";
                text = normalize(text);
                if (text.isEmpty()) continue;

                String authorName = displayName(message, author);
                INBOUND.offer(new InboundMessage(authorName, text));
            }
            lastMessageId = newest;
        } finally {
            connection.disconnect();
        }
    }

    private static String displayName(JsonObject message, JsonObject author) {
        if (message.has("member") && message.get("member")
            .isJsonObject()) {
            JsonObject member = message.getAsJsonObject("member");
            if (member.has("nick") && !member.get("nick")
                .isJsonNull()) {
                String nick = normalize(
                    member.get("nick")
                        .getAsString());
                if (!nick.isEmpty()) return nick;
            }
        }
        if (author.has("global_name") && !author.get("global_name")
            .isJsonNull()) {
            String globalName = normalize(
                author.get("global_name")
                    .getAsString());
            if (!globalName.isEmpty()) return globalName;
        }
        if (author.has("username")) {
            String username = normalize(
                author.get("username")
                    .getAsString());
            if (!username.isEmpty()) return username;
        }
        return "Discord";
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String text = value.replace('\r', ' ')
            .replace('\n', ' ')
            .trim();
        text = text.replace('§', '?');
        text = stripDiscordEmoji(text);
        text = text.replaceAll("\\s{2,}", " ")
            .trim();
        if (text.length() > 500) text = text.substring(0, 500);
        return text;
    }

    private static String stripDiscordEmoji(String text) {
        text = text.replaceAll("<a?:[A-Za-z0-9_~]+:[0-9]{15,25}>", "");
        text = text.replaceAll("!?\\[[^\\]]*\\]\\(https?://(?:cdn\\.)?discordapp\\.com/emojis/[^\\s)]+\\)", "");
        text = text.replaceAll("https?://(?:cdn\\.)?discordapp\\.com/emojis/[^\\s]+", "");

        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            if (codePoint == 0x200D || codePoint == 0xFE0F
                || codePoint >= 0x1F000 && codePoint <= 0x1FAFF
                || codePoint >= 0x2600 && codePoint <= 0x27BF
                || codePoint >= 0x1F3FB && codePoint <= 0x1F3FF) {
                continue;
            }
            out.appendCodePoint(codePoint);
        }
        return out.toString();
    }

    private static String snowflakeForNow() {
        long millis = Math.max(0L, System.currentTimeMillis() - DISCORD_EPOCH);
        return Long.toUnsignedString(millis << 22);
    }

    private static int compareSnowflakes(String a, String b) {
        try {
            return Long.compareUnsigned(Long.parseUnsignedLong(a), Long.parseUnsignedLong(b));
        } catch (NumberFormatException ignored) {
            return a.compareTo(b);
        }
    }

    private static final class InboundMessage {

        private final String author;
        private final String text;

        private InboundMessage(String author, String text) {
            this.author = author;
            this.text = text;
        }
    }
}
