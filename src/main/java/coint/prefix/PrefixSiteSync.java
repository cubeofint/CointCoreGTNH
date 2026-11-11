package coint.prefix;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.ServerUtilitiesPermissions;
import serverutils.ranks.Rank;
import serverutils.ranks.Ranks;

/**
 * The prefix choice in sync with the website, in the protocol the site's prefix pages already speak with the ATM10
 * server (through the AzLink API of this server's token):
 * <ul>
 * <li>{@code POST /api/azlink/prefixes/catalog}: every rank prefix there is (at start).</li>
 * <li>{@code POST /api/azlink/prefixes/report}: the prefixes a player has and the one he wears (on join, on a change,
 * and for everyone online every few minutes, which covers new ranks).</li>
 * <li>{@code GET /api/azlink/prefixes/operations}: choices made on the site, applied here, then
 * {@code POST /api/azlink/prefixes/ack}. A player who is offline gets it when he joins.</li>
 * </ul>
 * HTTP runs on its own thread; the game state is only touched on the server thread.
 */
@EventBusSubscriber
public final class PrefixSiteSync {

    private static final File PENDING_FILE = new File("cointcore", "prefix-pending.json");
    private static final String AUTO = "";
    private static final int REPORT_ALL_TICKS = 20 * 60 * 5;
    private static final long ERROR_LOG_EVERY_MS = 60_000L;

    private static ExecutorService worker;
    private static final Queue<Runnable> results = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean polling = new AtomicBoolean();
    private static int pollTicks, reportTicks;
    private static boolean catalogSent;
    private static long lastErrorLogAt;
    /** Choices made on the site for players who were offline: uuid → rank ("" for the highest). */
    private static Map<String, String> pending;

    private PrefixSiteSync() {}

    static boolean enabled() {
        CointConfig.PrefixSite c = CointConfig.prefixSite;
        return c.enabled && !c.siteUrl.isEmpty() && !c.siteKey.isEmpty();
    }

    public static synchronized void start() {
        if (!enabled() || worker != null) {
            return;
        }
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CointCore-PrefixSite");
            t.setDaemon(true);
            return t;
        });
        catalogSent = false;
        CointCore.LOG.info("[PrefixSite] Prefix sync with {} started", CointConfig.prefixSite.siteUrl);
    }

    public static synchronized void stop() {
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        results.clear();
        polling.set(false);
        pending = null;
    }

    // ---------------------------------------------------------------- server thread

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || worker == null) {
            return;
        }
        Runnable r;
        while ((r = results.poll()) != null) {
            r.run();
        }
        if (!catalogSent) {
            catalogSent = true;
            sendCatalog();
        }
        if (++pollTicks >= CointConfig.prefixSite.pollSeconds * 20) {
            pollTicks = 0;
            poll();
        }
        if (++reportTicks >= REPORT_ALL_TICKS) {
            reportTicks = 0;
            List<EntityPlayerMP> online = new ArrayList<>();
            for (Object o : MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList) {
                online.add((EntityPlayerMP) o);
            }
            report(online);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (worker == null || !(event.player instanceof EntityPlayerMP player)) {
            return;
        }
        String key = player.getUniqueID()
            .toString();
        String waiting = pending().remove(key);
        if (waiting != null) {
            savePending();
            PrefixService.select(player, waiting.isEmpty() ? null : waiting);
        }
        List<EntityPlayerMP> one = new ArrayList<>();
        one.add(player);
        report(one);
    }

    /** A player picked a prefix in the game: the site hears of it. */
    static void changed(EntityPlayerMP player) {
        if (worker == null) {
            return;
        }
        List<EntityPlayerMP> one = new ArrayList<>();
        one.add(player);
        report(one);
    }

    private static void report(List<EntityPlayerMP> players) {
        if (players.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        for (EntityPlayerMP player : players) {
            JsonObject row = new JsonObject();
            row.addProperty(
                "uuid",
                player.getUniqueID()
                    .toString());
            row.addProperty("name", player.getCommandSenderName());
            JsonArray available = new JsonArray();
            for (PrefixService.Option option : PrefixService.available(player)) {
                JsonObject o = new JsonObject();
                o.addProperty("group", option.rank);
                o.addProperty("prefix_raw", raw(option.prefix()));
                o.addProperty("category", "quest");
                available.add(o);
            }
            row.add("available", available);
            String selected = PrefixService.selected(player);
            row.add("selected", selected == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(selected));
            row.addProperty("locked_staff", false);
            array.add(row);
        }
        JsonObject body = new JsonObject();
        body.add("players", array);
        String json = body.toString();
        submit(() -> request("POST", "/azlink/prefixes/report", json));
    }

    private static void sendCatalog() {
        if (!Ranks.isActive()) {
            catalogSent = false;
            return;
        }
        JsonArray groups = new JsonArray();
        List<Rank> ranks = new ArrayList<>();
        for (String id : Ranks.INSTANCE.getRankNames(false)) {
            Rank rank = Ranks.INSTANCE.getRank(id);
            if (rank != null) {
                ranks.add(rank);
            }
        }
        ranks.sort(null);
        int index = 0;
        for (Rank rank : ranks) {
            String format = rank.getLocalPermission(ServerUtilitiesPermissions.CHAT_NAME_FORMAT);
            if (format == null || !format.contains("{name}")) {
                continue;
            }
            String prefix = PrefixService.prefixOf(format);
            if (prefix.isEmpty()) {
                continue;
            }
            JsonObject row = new JsonObject();
            row.addProperty("group", rank.getId());
            row.addProperty("prefix_raw", raw(prefix));
            row.addProperty("priority", rank.getPriority());
            row.addProperty("track_index", index++);
            row.addProperty(
                "display_name",
                prefix.replaceAll("§.", "")
                    .replaceAll("[\\[\\]]", "")
                    .trim());
            row.addProperty("category", "quest");
            groups.add(row);
        }
        JsonObject body = new JsonObject();
        body.addProperty("schema", 1);
        body.addProperty("hash", sha256(groups.toString()));
        body.addProperty("full", true);
        body.add("groups", groups);
        String json = body.toString();
        submit(() -> request("POST", "/azlink/prefixes/catalog", json));
    }

    private static void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        boolean queued = submit(() -> {
            try {
                String json = request("GET", "/azlink/prefixes/operations?limit=50", null);
                List<String[]> ops = parse(json);
                if (!ops.isEmpty()) {
                    results.add(() -> apply(ops));
                }
            } finally {
                polling.set(false);
            }
        });
        if (!queued) {
            polling.set(false);
        }
    }

    private static void apply(List<String[]> ops) {
        for (String[] op : ops) {
            String id = op[0], uuid = op[1], group = op[2];
            EntityPlayerMP player = online(uuid);
            if (player == null) {
                // Offline: kept until he joins; the site is told it is done.
                pending().put(uuid, group == null ? AUTO : group);
                savePending();
                ack(id, "applied", "offline_pending", group);
                continue;
            }
            if (group == null) {
                PrefixService.select(player, null);
                ack(id, "applied", "auto", null);
            } else if (PrefixService.select(player, group)) {
                ack(id, "applied", "applied", group);
            } else {
                ack(id, "rejected", "unavailable", null);
            }
            changed(player);
            CointCore.LOG.info("[PrefixSite] Site picked prefix {} for {}", group, player.getCommandSenderName());
        }
    }

    private static void ack(String id, String status, String reason, String selected) {
        JsonObject body = new JsonObject();
        body.addProperty("op_id", id);
        body.addProperty("status", status);
        body.addProperty("reason", reason);
        body.add("selected", selected == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(selected));
        String json = body.toString();
        submit(() -> request("POST", "/azlink/prefixes/ack", json));
    }

    private static EntityPlayerMP online(String uuid) {
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (p.getUniqueID()
                .toString()
                .equalsIgnoreCase(uuid)) {
                return p;
            }
        }
        return null;
    }

    /** {op_id, uuid, group or null} of each prefix_select operation. */
    private static List<String[]> parse(String json) {
        List<String[]> result = new ArrayList<>();
        if (json == null || json.trim()
            .isEmpty()) {
            return result;
        }
        JsonElement root;
        try {
            root = new JsonParser().parse(json);
        } catch (RuntimeException e) {
            return result;
        }
        JsonArray array;
        if (root.isJsonArray()) {
            array = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject()
            .has("operations")) {
                array = root.getAsJsonObject()
                    .getAsJsonArray("operations");
            } else {
                return result;
            }
        for (JsonElement e : array) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            String type = str(o, "type");
            if (type != null && !type.isEmpty() && !"prefix_select".equalsIgnoreCase(type)) {
                continue;
            }
            String id = first(o, "op_id", "id");
            if (id == null || !id.matches("[A-Za-z0-9_-]{8,64}")) {
                continue;
            }
            UUID uuid = uuid(first(o, "uuid", "game_id"));
            if (uuid == null) {
                ack(id, "rejected", "malformed_operation", null);
                continue;
            }
            String group = first(o, "group", "selected");
            if (group != null && (group.trim()
                .isEmpty() || "auto".equalsIgnoreCase(group))) {
                group = null;
            }
            result.add(new String[] { id, uuid.toString(), group == null ? null : group.toLowerCase(Locale.ROOT) });
        }
        return result;
    }

    private static UUID uuid(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.length() == 32) {
            v = v.substring(0, 8) + "-"
                + v.substring(8, 12)
                + "-"
                + v.substring(12, 16)
                + "-"
                + v.substring(16, 20)
                + "-"
                + v.substring(20);
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String first(JsonObject o, String... keys) {
        for (String key : keys) {
            String v = str(o, key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key)
            .isJsonNull() ? o.get(key)
                .getAsString() : null;
    }

    /** The site writes colors with {@code &}. */
    private static String raw(String prefix) {
        return prefix.replace('§', '&');
    }

    // ---------------------------------------------------------------- worker

    private static boolean submit(Runnable task) {
        ExecutorService w = worker;
        if (w == null) {
            return false;
        }
        try {
            w.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** Worker thread: one call of the site's AzLink API; the answer, or null when it failed. */
    private static String request(String method, String path, String json) {
        CointConfig.PrefixSite c = CointConfig.prefixSite;
        String base = c.siteUrl.endsWith("/") ? c.siteUrl.substring(0, c.siteUrl.length() - 1) : c.siteUrl;
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(base + "/api" + path).openConnection();
            http.setRequestMethod(method);
            http.setConnectTimeout(5000);
            http.setReadTimeout(15000);
            http.setRequestProperty("Azuriom-Link-Token", c.siteKey);
            http.setRequestProperty("Accept", "application/json");
            http.setRequestProperty("User-Agent", "CointCore-GTNH");
            if (json != null) {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = http.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = http.getResponseCode();
            InputStream in = code >= 400 ? http.getErrorStream() : http.getInputStream();
            String answer = in == null ? "" : read(in);
            if (code >= 400) {
                logError(method + " " + path + " -> HTTP " + code);
                return null;
            }
            return answer;
        } catch (IOException | RuntimeException e) {
            logError(method + " " + path + ": " + e);
            return null;
        } finally {
            if (http != null) {
                http.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream input = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = input.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void logError(String what) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogAt >= ERROR_LOG_EVERY_MS) {
            lastErrorLogAt = now;
            CointCore.LOG.warn("[PrefixSite] Site request failed: {}", what);
        }
    }

    private static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : d) {
                s.append(String.format("%02x", b));
            }
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- pending choices

    private static Map<String, String> pending() {
        if (pending == null) {
            pending = new HashMap<>();
            if (PENDING_FILE.isFile()) {
                try (Reader r = Files.newBufferedReader(PENDING_FILE.toPath(), StandardCharsets.UTF_8)) {
                    Map<String, String> read = new Gson()
                        .fromJson(r, new TypeToken<Map<String, String>>() {}.getType());
                    if (read != null) {
                        pending.putAll(read);
                    }
                } catch (IOException | RuntimeException e) {
                    CointCore.LOG.warn("[PrefixSite] Could not read {}: {}", PENDING_FILE, e.toString());
                }
            }
        }
        return pending;
    }

    private static void savePending() {
        File dir = PENDING_FILE.getParentFile();
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        File tmp = new File(PENDING_FILE.getPath() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
            new Gson().toJson(pending(), w);
        } catch (IOException e) {
            CointCore.LOG.warn("[PrefixSite] Could not save {}: {}", PENDING_FILE, e.toString());
            return;
        }
        try {
            Files.move(tmp.toPath(), PENDING_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CointCore.LOG.warn("[PrefixSite] Could not save {}: {}", PENDING_FILE, e.toString());
        }
    }
}
