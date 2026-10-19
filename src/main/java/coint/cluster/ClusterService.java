package coint.cluster;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.integration.discord.DiscordModerationWebhook;
import coint.util.ChatUtil;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Links this server with the other servers of the network through the shared database: says it is alive with its
 * players, carries global chat both ways, and keeps the players of the other servers in the tab list.
 *
 * <p>
 * The database is only touched on one worker thread. The server thread hands it snapshots and takes back what came,
 * so a slow or lost database never holds a tick.
 */
@EventBusSubscriber
public final class ClusterService {

    static final String KIND_PLAYER = "player";
    static final String KIND_DISCORD = "discord";
    private static final int CHAT_BATCH = 100;
    /** How far below its last id a read starts again, for lines committed late. */
    private static final int CHAT_REREAD = 100;
    private static final int CHAT_KEEP_MINUTES = 10;
    private static final long PRUNE_EVERY_MS = 5 * 60_000L;
    private static final long ERROR_LOG_EVERY_MS = 60_000L;

    private static volatile boolean running;
    private static ExecutorService worker;
    private static ClusterDatabase database;
    private static String nodeId = "", displayName = "";

    /** What the worker brought back, to be applied on the server thread. */
    private static final Queue<Runnable> results = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean heartbeatInFlight = new AtomicBoolean();
    private static final AtomicBoolean pollInFlight = new AtomicBoolean();

    // Worker thread only.
    private static long chatReadFrom = -1;
    /** Lines up to this id were there before this server started. */
    private static long chatFloor;
    private static final Set<Long> shownChat = new LinkedHashSet<>();
    private static long nextPruneAt;
    private static long lastErrorLogAt;
    private static boolean connectedLogged;
    /** This start was written to the database (worker thread only). */
    private static boolean startMarked;

    // Server thread only.
    private static long nextHeartbeatAt, nextPollAt;
    /** The players of the other servers in the tab list: name, and the name of their server. */
    private static Map<String, String> remoteTab = Collections.emptyMap();

    private ClusterService() {}

    /** A call on the cluster database, run on the worker thread. */
    interface DbCall<T> {

        T call(ClusterDatabase db) throws SQLException;
    }

    /**
     * Runs {@code call} on the worker, then {@code done} with its result on the server thread, or {@code failed} with
     * the error. False when the cluster is off: neither runs.
     */
    static <T> boolean query(DbCall<T> call, java.util.function.Consumer<T> done,
        java.util.function.Consumer<String> failed) {
        if (!running) {
            return false;
        }
        ClusterDatabase db = database;
        submit(() -> {
            try {
                T value = call.call(db);
                results.add(() -> done.accept(value));
            } catch (SQLException e) {
                logError("query", e);
                if (failed != null) {
                    results.add(() -> failed.accept(e.getMessage()));
                }
            }
        });
        return true;
    }

    /** The id of this server in the network. */
    public static String nodeId() {
        return nodeId;
    }

    public static String displayName() {
        return displayName;
    }

    public static boolean isRunning() {
        return running;
    }

    public static synchronized void start() {
        stopWorker();
        CointConfig.Cluster config = CointConfig.cluster;
        if (!config.enabled) {
            return;
        }
        nodeId = config.nodeId.trim();
        displayName = config.displayName.trim()
            .isEmpty() ? nodeId : config.displayName.trim();
        if (nodeId.isEmpty()) {
            CointCore.LOG.error("[Cluster] cluster.nodeId is empty: the cluster stays off");
            return;
        }
        database = new ClusterDatabase(config.jdbcUrl, config.username, config.password);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "CointCore-Cluster");
            thread.setDaemon(true);
            return thread;
        });
        chatReadFrom = -1;
        shownChat.clear();
        startMarked = false;
        connectedLogged = false;
        nextHeartbeatAt = nextPollAt = 0;
        running = true;
        CointCore.LOG.info("[Cluster] Starting as node '{}' ({})", nodeId, displayName);
    }

    /** Tells the others this server is going down, waiting a moment for it, and stops. */
    public static synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        ClusterDatabase db = database;
        String id = nodeId;
        worker.execute(() -> {
            try {
                db.stopped(id);
            } catch (SQLException e) {
                CointCore.LOG.warn("[Cluster] Could not mark this server stopped: {}", e.getMessage());
            }
        });
        stopWorker();
        clearRemoteTab();
    }

    private static void stopWorker() {
        if (worker != null) {
            worker.shutdown();
            try {
                worker.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
            worker.shutdownNow();
            worker = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
        results.clear();
    }

    /** Global chat of a player here, for the other servers. */
    public static void publishPlayerChat(EntityPlayerMP sender, String senderFormatted, String text) {
        if (!running || !CointConfig.cluster.chatEnabled) {
            return;
        }
        publish(KIND_PLAYER, sender.getCommandSenderName(), senderFormatted, text);
    }

    /** A Discord message this server received, for the other servers. */
    public static void publishDiscordChat(String author, String text) {
        if (!running || !CointConfig.cluster.chatEnabled) {
            return;
        }
        publish(KIND_DISCORD, author, author, text);
    }

    private static void publish(String kind, String sender, String senderFormatted, String text) {
        ClusterDatabase db = database;
        String id = nodeId;
        submit(() -> {
            try {
                db.publishChat(id, kind, sender, senderFormatted, text);
            } catch (SQLException e) {
                logError("send chat", e);
            }
        });
    }

    /** The players of the other servers to show in the tab list: name, and the name of their server. */
    public static Map<String, String> remoteTab() {
        return remoteTab;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Runnable result;
        while ((result = results.poll()) != null) {
            result.run();
        }
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        CointConfig.Cluster config = CointConfig.cluster;
        if (now >= nextHeartbeatAt && heartbeatInFlight.compareAndSet(false, true)) {
            nextHeartbeatAt = now + config.heartbeatSeconds * 1000L;
            sendHeartbeat(config);
        }
        if (config.chatEnabled && now >= nextPollAt && pollInFlight.compareAndSet(false, true)) {
            nextPollAt = now + config.chatPollMillis;
            pollChat();
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            for (String name : remoteTab.keySet()) {
                if (!name.equals(player.getCommandSenderName())) {
                    player.playerNetServerHandler.sendPacket(new S38PacketPlayerListItem(name, true, 0));
                }
            }
        }
    }

    private static void sendHeartbeat(CointConfig.Cluster config) {
        MinecraftServer server = MinecraftServer.getServer();
        List<ClusterDatabase.PlayerRow> players = new ArrayList<>();
        for (Object o : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            players.add(
                new ClusterDatabase.PlayerRow(
                    player.getUniqueID()
                        .toString(),
                    player.getCommandSenderName()));
        }
        double tps = tps(server);
        ClusterDatabase db = database;
        String id = nodeId, name = displayName, address = config.address;
        int timeout = config.nodeTimeoutSeconds;
        boolean tab = config.tabEnabled;
        submit(() -> {
            try {
                db.heartbeat(id, name, address, tps, players, !startMarked);
                startMarked = true;
                List<ClusterDatabase.RemotePlayer> remote = tab ? db.remotePlayers(id, timeout)
                    : Collections.emptyList();
                if (!connectedLogged) {
                    connectedLogged = true;
                    CointCore.LOG.info("[Cluster] Connected to the cluster database");
                }
                results.add(() -> applyRemoteTab(remote));
            } catch (SQLException e) {
                logError("heartbeat", e);
                // Unknown who is where: better nobody than ghosts.
                results.add(() -> applyRemoteTab(Collections.emptyList()));
            } finally {
                heartbeatInFlight.set(false);
            }
        });
    }

    private static void pollChat() {
        ClusterDatabase db = database;
        String id = nodeId;
        submit(() -> {
            try {
                if (chatReadFrom < 0) {
                    // Started now: what was said before is not shown again.
                    chatReadFrom = chatFloor = db.lastChatId();
                }
                List<ClusterDatabase.ChatRow> rows = db
                    .chatSince(id, Math.max(0, chatReadFrom - CHAT_REREAD), CHAT_BATCH + CHAT_REREAD);
                List<ClusterDatabase.ChatRow> fresh = new ArrayList<>();
                for (ClusterDatabase.ChatRow row : rows) {
                    if (row.id <= chatFloor || !shownChat.add(row.id)) {
                        continue;
                    }
                    chatReadFrom = Math.max(chatReadFrom, row.id);
                    if (!row.own) {
                        fresh.add(row);
                    }
                }
                trimShown();
                if (!fresh.isEmpty()) {
                    results.add(() -> showChat(fresh));
                }
                long now = System.currentTimeMillis();
                if (now >= nextPruneAt) {
                    nextPruneAt = now + PRUNE_EVERY_MS;
                    db.pruneChat(CHAT_KEEP_MINUTES);
                }
            } catch (SQLException e) {
                logError("read chat", e);
            } finally {
                pollInFlight.set(false);
            }
        });
    }

    /** Runs on the worker; dropped when the cluster is stopping. */
    private static void submit(Runnable task) {
        ExecutorService w = worker;
        if (w == null) {
            heartbeatInFlight.set(false);
            pollInFlight.set(false);
            return;
        }
        try {
            w.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            heartbeatInFlight.set(false);
            pollInFlight.set(false);
        }
    }

    private static void trimShown() {
        Iterator<Long> it = shownChat.iterator();
        while (shownChat.size() > 2000 && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static void showChat(List<ClusterDatabase.ChatRow> rows) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        for (ClusterDatabase.ChatRow row : rows) {
            String name;
            if (KIND_DISCORD.equals(row.kind)) {
                name = "§9[Discord]§r " + row.sender;
            } else {
                name = "§8[" + row.nodeName + "]§r " + row.senderFormatted;
                // Only players' chat goes to Discord: Discord's own messages came from there.
                DiscordModerationWebhook.logRemoteGlobal(row.nodeName, row.sender, row.text);
            }
            ChatComponentText component = ChatUtil.getChatMessage(name, row.text, "G");
            server.getConfigurationManager()
                .sendChatMsg(component);
            CointCore.LOG.info("[GLOBAL/{}] {}: {}", row.nodeName, row.sender, row.text);
        }
    }

    private static void applyRemoteTab(List<ClusterDatabase.RemotePlayer> players) {
        Map<String, String> next = new HashMap<>();
        if (running) {
            for (ClusterDatabase.RemotePlayer player : players) {
                next.put(player.name, player.nodeName);
            }
        }
        Map<String, String> previous = remoteTab;
        if (next.equals(previous)) {
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        Set<String> localNames = new java.util.HashSet<>();
        for (Object o : server.getConfigurationManager().playerEntityList) {
            localNames.add(((EntityPlayerMP) o).getCommandSenderName());
        }
        for (String name : previous.keySet()) {
            // A player who has come here keeps the line of his own.
            if (!next.containsKey(name) && !localNames.contains(name)) {
                broadcast(new S38PacketPlayerListItem(name, false, 0));
            }
        }
        for (String name : next.keySet()) {
            if (!previous.containsKey(name) && !localNames.contains(name)) {
                broadcast(new S38PacketPlayerListItem(name, true, 0));
            }
        }
        remoteTab = Collections.unmodifiableMap(next);
    }

    private static void clearRemoteTab() {
        applyRemoteTab(Collections.emptyList());
        remoteTab = Collections.emptyMap();
    }

    private static void broadcast(S38PacketPlayerListItem packet) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        server.getConfigurationManager()
            .sendPacketToAllPlayers(packet);
    }

    private static double tps(MinecraftServer server) {
        long[] times = server.tickTimeArray;
        if (times == null || times.length == 0) {
            return 20;
        }
        long sum = 0;
        for (long time : times) {
            sum += time;
        }
        double ms = sum / (double) times.length / 1_000_000.0;
        return ms <= 50 ? 20 : 1000.0 / ms;
    }

    private static void logError(String what, SQLException e) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogAt >= ERROR_LOG_EVERY_MS) {
            lastErrorLogAt = now;
            CointCore.LOG.warn("[Cluster] Database error ({}): {}", what, e.getMessage());
        }
        connectedLogged = false;
        if (database != null) {
            database.close();
        }
    }
}
