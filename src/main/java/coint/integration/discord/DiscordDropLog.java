package coint.integration.discord;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.oredict.OreDictionary;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import coint.CointConfig;
import coint.CointCore;
import coint.util.TimeUtil;
import cpw.mods.fml.common.registry.GameData;

/** Separate, bounded, batched Discord audit of player-thrown items, as in CointCoreGTO. */
public final class DiscordDropLog {

    private static final int LIMIT = 1900;
    private static final LinkedBlockingDeque<String> pending = new LinkedBlockingDeque<>(2000);
    private static final AtomicInteger dropped = new AtomicInteger();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(TimeUtil.getZone());
    private static ScheduledExecutorService worker;
    private static volatile boolean accepting;
    private static long warnedAt;
    private static long retryAt;

    private DiscordDropLog() {}

    public static void start() {
        accepting = true;
        retryAt = 0;
    }

    public static boolean enabled() {
        return accepting && CointConfig.dropLog.enabled
            && !CointConfig.dropLog.webhookUrl.trim()
                .isEmpty();
    }

    private static boolean real(EntityPlayer player) {
        return enabled() && player instanceof EntityPlayerMP
            && !(player instanceof FakePlayer)
            && player.worldObj != null
            && !player.worldObj.isRemote;
    }

    /** Forge has finished the toss and accepted the spawned entity at this point. */
    public static void thrown(EntityPlayer player, EntityItem item) {
        if (!real(player) || item == null || item.isDead || !item.addedToChunk || item.getEntityItem().stackSize <= 0)
            return;
        DropOrigins.thrown(item, player.getCommandSenderName());
        ItemStack stack = item.getEntityItem();
        if (!ignored(stack)) enqueue(line(player, stack, stack.stackSize, "выбросил", null));
    }

    public static final class Pickup {

        final EntityItem item;
        final EntityPlayer player;
        final ItemStack stack;
        final DropOrigins origins;

        Pickup(EntityItem item, EntityPlayer player, DropOrigins origins) {
            this.item = item;
            this.player = player;
            this.origins = origins;
            this.stack = item.getEntityItem()
                .copy();
        }
    }

    public static Pickup beforePickup(EntityItem item, EntityPlayer player) {
        if (!real(player) || item.isDead || !DropOrigins.tracked(item)) return null;
        DropOrigins origins = DropOrigins.read(item);
        return origins.sources.isEmpty() ? null : new Pickup(item, player, origins);
    }

    /** Called only after actual inventory insertion, or a non-cancelled Forge ALLOW pickup. */
    public static void picked(Pickup pickup, int remaining) {
        if (pickup == null) return;
        int taken = pickup.origins.count - Math.max(0, remaining);
        if (taken <= 0) return;
        Map<String, Integer> sources = pickup.origins.take(taken);
        pickup.origins.write(pickup.item);
        if (!ignored(pickup.stack)) for (Map.Entry<String, Integer> source : sources.entrySet())
            enqueue(line(pickup.player, pickup.stack, source.getValue(), "подобрал", source.getKey()));
    }

    public static Object[] beforeMerge(EntityItem a, EntityItem b) {
        if (!enabled() || a.worldObj.isRemote || (!DropOrigins.tracked(a) && !DropOrigins.tracked(b))) return null;
        DropOrigins first = DropOrigins.read(a), second = DropOrigins.read(b);
        return first.sources.isEmpty() && second.sources.isEmpty() ? null : new Object[] { first, second };
    }

    public static void merged(EntityItem a, EntityItem b, Object[] before) {
        if (before != null) DropOrigins.merged(a, b, (DropOrigins) before[0], (DropOrigins) before[1]);
    }

    private static boolean ignored(ItemStack stack) {
        String id = GameData.getItemRegistry()
            .getNameForObject(stack.getItem());
        if (id == null) return true;
        for (String raw : CointConfig.dropLog.ignoredItems) {
            if (raw == null) continue;
            String entry = raw.trim()
                .toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) continue;
            if (entry.startsWith("@")) {
                if (id.startsWith(entry.substring(1) + ":")) return true;
            } else if (entry.startsWith("#")) {
                for (int ore : OreDictionary.getOreIDs(stack)) if (OreDictionary.getOreName(ore)
                    .equalsIgnoreCase(entry.substring(1))) return true;
            } else {
                if (!entry.contains(":")) entry = "minecraft:" + entry;
                if (id.equals(entry) || (id + ":" + stack.getItemDamage()).equals(entry)) return true;
            }
        }
        return false;
    }

    private static String clean(String text, int max) {
        if (text == null) return "";
        String value = text.replaceAll("§.", "")
            .replaceAll("[\\p{Cntrl}\\s]+", " ")
            .trim();
        if (value.length() > max) value = value.substring(0, max) + "…";
        return value.replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("`", "'");
    }

    private static String line(EntityPlayer player, ItemStack stack, int count, String action, String thrower) {
        String id = GameData.getItemRegistry()
            .getNameForObject(stack.getItem());
        String name = id;
        try {
            name = stack.getDisplayName();
        } catch (RuntimeException | LinkageError ignored) {}
        String node = coint.cluster.ClusterService.isRunning() ? coint.cluster.ClusterService.displayName()
            : CointConfig.api.serverTag;
        StringBuilder line = new StringBuilder("`").append(TIME.format(Instant.now()))
            .append("` [")
            .append(clean(node, 64))
            .append("] **")
            .append(clean(player.getCommandSenderName(), 64))
            .append("** ")
            .append(action)
            .append(" **")
            .append(count)
            .append("x ")
            .append(clean(name, 160))
            .append("** (`")
            .append(clean(id, 160))
            .append(':')
            .append(stack.getItemDamage())
            .append("`)");
        if (stack.hasTagCompound()) line.append(" [NBT]");
        if (thrower != null) line.append(
            thrower.equalsIgnoreCase(player.getCommandSenderName()) ? " — своё"
                : " — выбросил **" + clean(thrower, 64) + "**");
        return line.append(" — DIM ")
            .append(player.dimension)
            .append(' ')
            .append(MathHelper.floor_double(player.posX))
            .append(' ')
            .append(MathHelper.floor_double(player.posY))
            .append(' ')
            .append(MathHelper.floor_double(player.posZ))
            .toString();
    }

    private static synchronized void enqueue(String line) {
        if (!enabled()) return;
        if (line.length() > LIMIT) line = line.substring(0, LIMIT - 1) + "…";
        if (!pending.offerLast(line)) dropped.incrementAndGet();
        if (worker == null) {
            worker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "CointCore-DropLog");
                t.setDaemon(true);
                return t;
            });
            worker.scheduleWithFixedDelay(DiscordDropLog::flush, 3, 3, TimeUnit.SECONDS);
        }
    }

    private static void warning(String reason) {
        long now = System.currentTimeMillis();
        if (now - warnedAt > 60000) {
            warnedAt = now;
            CointCore.LOG.warn("[DropLog] {}", reason);
        }
    }

    private static void flush() {
        if (!CointConfig.dropLog.enabled || CointConfig.dropLog.webhookUrl.trim()
            .isEmpty()) {
            pending.clear();
            return;
        }
        if (System.currentTimeMillis() < retryAt) return;
        int lost = dropped.getAndSet(0);
        if (lost > 0) warning("Queue full; skipped records: " + lost);
        for (int message = 0; message < 4; message++) {
            List<String> lines = new ArrayList<>();
            StringBuilder batch = new StringBuilder();
            String next;
            while ((next = pending.peekFirst()) != null
                && batch.length() + next.length() + (batch.length() == 0 ? 0 : 1) <= LIMIT) {
                next = pending.pollFirst();
                if (next == null) break;
                lines.add(next);
                if (batch.length() > 0) batch.append('\n');
                batch.append(next);
            }
            if (lines.isEmpty()) return;
            if (!send(batch.toString())) {
                for (int i = lines.size() - 1; i >= 0; i--) {
                    while (!pending.offerFirst(lines.get(i))) {
                        pending.pollLast();
                        dropped.incrementAndGet();
                    }
                }
                return;
            }
        }
    }

    private static boolean send(String content) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", clean(CointConfig.dropLog.webhookName, 80));
        payload.addProperty("content", content);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", mentions);
        byte[] bytes = payload.toString()
            .getBytes(StandardCharsets.UTF_8);
        if (Thread.currentThread()
            .isInterrupted()) return false;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(CointConfig.dropLog.webhookUrl.trim()).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                retryAt = 0;
                return true;
            }
            long delay = 5000;
            if (status == 429) {
                double seconds = 3;
                try {
                    double value = Double.parseDouble(connection.getHeaderField("Retry-After"));
                    if (Double.isFinite(value)) seconds = value;
                } catch (RuntimeException ignored) {}
                delay = Math.max(250, Math.min(86400000, (long) (seconds * 1000)));
            } else if (status >= 400 && status < 500) delay = 60000;
            retryAt = System.currentTimeMillis() + delay;
            warning("Webhook returned HTTP " + status);
        } catch (Exception e) {
            retryAt = System.currentTimeMillis() + 5000;
            warning(
                "Webhook send failed: " + e.getClass()
                    .getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }
        return false;
    }

    public static void stop() {
        ScheduledExecutorService executor;
        synchronized (DiscordDropLog.class) {
            accepting = false;
            executor = worker;
            worker = null;
        }
        if (executor != null) {
            executor.execute(DiscordDropLog::flush);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread()
                    .interrupt();
            }
        }
        pending.clear();
    }
}
