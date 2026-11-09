package coint.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * The hub: where players wait while their server restarts. A server about to restart sends its players there and
 * notes in {@code cluster_returns} where each came from. Nobody is sent back by force: the hub tells each player once
 * his server is up again, and he goes back with {@code /server} when he wants.
 */
@EventBusSubscriber
public final class ClusterHub {

    private static final long HUB_LOOKUP_MS = 10_000;
    private static final long RETURN_POLL_MS = 5_000;
    private static final long PRUNE_EVERY_MS = 3_600_000;
    private static final int RETURN_KEEP_HOURS = 24;

    /** Other servers: the hub as last seen, or null when it is off or unknown. */
    private static volatile ClusterDatabase.NodeRow hub;
    private static long nextHubLookupAt;
    private static boolean hubLookupInFlight;

    /** The hub: players already told their server is up again. */
    private static final Set<String> told = new java.util.HashSet<>();
    private static long nextReturnPollAt;
    private static boolean returnPollInFlight;
    private static long nextPruneAt;

    private ClusterHub() {}

    private static String hubId() {
        return CointConfig.cluster.hubNodeId.trim();
    }

    /** Whether this server is the hub. */
    public static boolean isHub() {
        String id = hubId();
        return !id.isEmpty() && id.equalsIgnoreCase(ClusterService.nodeId());
    }

    /**
     * Sends a player to the hub to wait for the restart. False when there is no hub to go to (none set, it is off, or
     * this is the hub): the caller disconnects him instead.
     */
    public static boolean sendToHub(EntityPlayerMP player) {
        ClusterDatabase.NodeRow to = hub;
        if (!ClusterService.isRunning() || isHub() || to == null || !to.online || to.address.isEmpty()) {
            return false;
        }
        if (ClusterTransfers.isLeaving(player.getUniqueID())) {
            return true;
        }
        String uuid = player.getUniqueID()
            .toString();
        String name = player.getCommandSenderName();
        String here = ClusterService.nodeId();
        ClusterService.query(db -> {
            db.setReturn(uuid, name, here);
            return null;
        }, done -> {}, null);
        player.addChatMessage(
            new ChatComponentText(
                "§6Сервер перезапускается. §fПодождите на хабе — когда он поднимется, вернуться: §e/server " + here));
        ClusterTransfers.send(player, to);
        return true;
    }

    /** The restart was called off after the players had left: the hub tells them their server is there again. */
    public static void restartAborted() {
        if (!ClusterService.isRunning() || isHub() || hubId().isEmpty()) {
            return;
        }
        String here = ClusterService.nodeId();
        ClusterService.query(db -> {
            db.releaseReturns(here);
            return null;
        }, done -> CointCore.LOG.info("[Cluster] Restart called off: players waiting on the hub are told"), null);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClusterService.isRunning() || hubId().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!isHub()) {
            if (now >= nextHubLookupAt && !hubLookupInFlight) {
                nextHubLookupAt = now + HUB_LOOKUP_MS;
                lookUpHub();
            }
            return;
        }
        sendWaiting(now);
        if (now >= nextReturnPollAt && !returnPollInFlight) {
            nextReturnPollAt = now + RETURN_POLL_MS;
            pollReturns(now);
        }
    }

    private static void lookUpHub() {
        String id = hubId();
        int timeout = CointConfig.cluster.nodeTimeoutSeconds;
        hubLookupInFlight = ClusterService.query(db -> db.nodes(timeout), nodes -> {
            hubLookupInFlight = false;
            ClusterDatabase.NodeRow found = null;
            for (ClusterDatabase.NodeRow node : nodes) {
                if (node.id.equalsIgnoreCase(id)) {
                    found = node;
                }
            }
            hub = found;
        }, error -> {
            hubLookupInFlight = false;
            hub = null;
        });
    }

    private static void pollReturns(long now) {
        List<String> uuids = new ArrayList<>();
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            String uuid = ((EntityPlayerMP) o).getUniqueID()
                .toString();
            if (!told.contains(uuid)) {
                uuids.add(uuid);
            }
        }
        boolean prune = now >= nextPruneAt;
        if (prune) {
            nextPruneAt = now + PRUNE_EVERY_MS;
        }
        if (uuids.isEmpty() && !prune) {
            return;
        }
        int timeout = CointConfig.cluster.nodeTimeoutSeconds;
        int delay = CointConfig.cluster.hubReturnDelaySeconds;
        returnPollInFlight = ClusterService.query(db -> {
            if (prune) {
                db.pruneReturns(RETURN_KEEP_HOURS);
            }
            return db.returns(uuids, timeout, delay);
        }, rows -> {
            returnPollInFlight = false;
            for (ClusterDatabase.ReturnRow row : rows) {
                EntityPlayerMP player = row.ready ? online(row.uuid) : null;
                if (player != null && told.add(row.uuid)) {
                    player.addChatMessage(
                        new ChatComponentText("§aСервер " + row.nodeName + " снова в сети. §fВернуться: ")
                            .appendSibling(command("/server " + row.nodeId)));
                }
            }
        }, error -> returnPollInFlight = false);
    }

    /** A command shown in chat that runs when clicked. */
    private static IChatComponent command(String text) {
        return new ChatComponentText(text).setChatStyle(
            new ChatStyle().setColor(EnumChatFormatting.YELLOW)
                .setUnderlined(true)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, text)));
    }

    private static EntityPlayerMP online(String uuid) {
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (player.getUniqueID()
                .toString()
                .equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP player) || !ClusterService.isRunning() || hubId().isEmpty()) {
            return;
        }
        String uuid = player.getUniqueID()
            .toString();
        if (!isHub()) {
            // Arrived somewhere other than the hub: whatever he waited for there is over.
            ClusterService.query(db -> {
                db.clearReturn(uuid);
                return null;
            }, done -> {}, null);
            return;
        }
        told.remove(uuid);
        String name = player.getCommandSenderName();
        String here = ClusterService.nodeId();
        int timeout = CointConfig.cluster.nodeTimeoutSeconds;
        int delay = CointConfig.cluster.hubReturnDelaySeconds;
        boolean stays = PermissionAPI.hasPermission(player, ClusterComfort.HUB_BYPASS);
        ClusterService.query(db -> {
            Arrival a = new Arrival();
            a.moved = db.takeMove(uuid, here, MOVE_SECONDS);
            a.home = db.home(uuid, name, null);
            a.nodes = db.nodes(timeout);
            a.returns = db.returns(Collections.singletonList(uuid), timeout, delay);
            return a;
        }, a -> arrived(player, a, stays), null);
    }

    /**
     * Who joins the hub from the launcher goes on to his home server when it is up, and waits here when it is not.
     * Who was sent here (a restart, /server) stays.
     */
    private static void arrived(EntityPlayerMP player, Arrival a, boolean stays) {
        if (player.playerNetServerHandler == null || player.isDead) {
            return;
        }
        boolean waiting = !a.returns.isEmpty() && !a.returns.get(0).ready;
        if (a.moved || stays || !CointConfig.cluster.hubSendsHome) {
            if (waiting) {
                player.addChatMessage(
                    new ChatComponentText(
                        "§6Вы на хабе. §fСервер §e" + a.returns.get(0).nodeName
                            + "§f перезапускается — здесь скажут, когда он поднимется."));
                CointCore.LOG.info(
                    "[Cluster] {} waits on the hub for {}",
                    player.getCommandSenderName(),
                    a.returns.get(0).nodeId);
            }
            return;
        }
        String target = a.home;
        if (target == null) {
            String first = CointConfig.cluster.newPlayerNodeId == null ? ""
                : CointConfig.cluster.newPlayerNodeId.trim();
            target = first.isEmpty() ? null : first;
        }
        if (target == null) {
            player.addChatMessage(
                new ChatComponentText("§6Добро пожаловать на хаб! §fВыберите сервер: клавиша §eM§f или §e/server"));
            return;
        }
        ClusterDatabase.NodeRow node = null;
        for (ClusterDatabase.NodeRow n : a.nodes) {
            if (n.id.equalsIgnoreCase(target)) {
                node = n;
            }
        }
        if (node != null && node.online
            && !node.address.isEmpty()
            && !node.id.equalsIgnoreCase(ClusterService.nodeId())) {
            player
                .addChatMessage(new ChatComponentText("§aВаш сервер " + node.name + " в сети — переносим вас туда..."));
            sendLater.put(player.getUniqueID(), new Later(node, System.currentTimeMillis() + SEND_DELAY_MS));
            return;
        }
        String shown = node == null ? target : node.name;
        player.addChatMessage(
            new ChatComponentText(
                "§6Ваш сервер §e" + shown
                    + "§6 сейчас выключен. §fПодождите на хабе — когда он поднимется, здесь скажут."));
        if (a.home != null) {
            String uuid = player.getUniqueID()
                .toString();
            String name = player.getCommandSenderName(), home = a.home;
            ClusterService.query(db -> {
                db.setReturn(uuid, name, home);
                return null;
            }, done -> {}, null);
        }
    }

    /** Players to send home a moment after joining the hub (so the client has settled in). */
    private static void sendWaiting(long now) {
        if (sendLater.isEmpty()) {
            return;
        }
        for (java.util.Iterator<java.util.Map.Entry<java.util.UUID, Later>> it = sendLater.entrySet()
            .iterator(); it.hasNext();) {
            java.util.Map.Entry<java.util.UUID, Later> e = it.next();
            if (e.getValue().at > now) {
                continue;
            }
            it.remove();
            EntityPlayerMP player = online(
                e.getKey()
                    .toString());
            if (player != null && !ClusterTransfers.isLeaving(player.getUniqueID())) {
                ClusterTransfers.send(player, e.getValue().node);
            }
        }
    }

    /** A sent player's mark is used within this long of being sent. */
    private static final int MOVE_SECONDS = 120;
    private static final long SEND_DELAY_MS = 2000;
    private static final java.util.Map<java.util.UUID, Later> sendLater = new java.util.HashMap<>();

    private static final class Later {

        final ClusterDatabase.NodeRow node;
        final long at;

        Later(ClusterDatabase.NodeRow node, long at) {
            this.node = node;
            this.at = at;
        }
    }

    private static final class Arrival {

        boolean moved;
        String home;
        List<ClusterDatabase.NodeRow> nodes;
        List<ClusterDatabase.ReturnRow> returns;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        told.remove(
            event.player.getUniqueID()
                .toString());
    }

    /** Forgets the hub when the cluster stops. */
    static void reset() {
        hub = null;
        told.clear();
        sendLater.clear();
        hubLookupInFlight = returnPollInFlight = false;
        nextHubLookupAt = nextReturnPollAt = nextPruneAt = 0;
    }
}
