package coint.cluster;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import coint.CointConfig;
import coint.CointCore;
import serverutils.lib.math.TeleporterDimPos;

/**
 * /tpa to a player on another server of the network. The request goes to his server; when he accepts there, his
 * server notes where he stands for the asker and answers, and the asker's server sends the asker over. On joining
 * that server he is put at that spot.
 *
 * <p>
 * Server thread only.
 */
public final class ClusterTpa {

    private static final long REQUEST_MS = 30_000;
    /** The answer and the move take a moment longer than the request lives. */
    private static final long OUTGOING_MS = REQUEST_MS + 15_000;
    private static final int ARRIVAL_SECONDS = 120;

    /** Asked here, waiting for an answer from another server: asker → whom he asked. */
    private static final Map<UUID, Outgoing> outgoing = new HashMap<>();
    /** Asked of players here from another server: lower-case name of the asked → lower-case asker → request. */
    private static final Map<String, Map<String, Incoming>> incoming = new HashMap<>();

    private ClusterTpa() {}

    /** /tpa {@code name} when he is not here: true when he is on another server and was asked. */
    public static boolean request(EntityPlayerMP player, String name) {
        String target = ClusterService.remotePlayerName(name);
        if (target == null || target.equalsIgnoreCase(player.getCommandSenderName())) {
            return false;
        }
        String server = ClusterService.remotePlayerServer(target);
        if (!ClusterService.publishTo(
            ClusterService.KIND_TPA,
            player.getCommandSenderName(),
            player.getCommandSenderName(),
            target,
            player.getUniqueID()
                .toString())) {
            return false;
        }
        outgoing.put(player.getUniqueID(), new Outgoing(target, System.currentTimeMillis() + OUTGOING_MS));
        player.addChatMessage(
            new ChatComponentText(
                "§eЗапрос на телепорт к §b" + target
                    + "§e (сервер "
                    + server
                    + ") отправлен. Он действует 30 секунд."));
        return true;
    }

    /** /tpaccept {@code name} when the request came from another server: true when there was one. */
    public static boolean accept(EntityPlayerMP player, String name) {
        Map<String, Incoming> mine = incoming.get(key(player.getCommandSenderName()));
        Incoming request = mine == null ? null : mine.remove(key(name));
        if (request == null || request.expiresAt < System.currentTimeMillis()) {
            return false;
        }
        String node = ClusterService.nodeId();
        int dim = player.dimension;
        double x = player.posX, y = player.posY, z = player.posZ;
        String asker = request.name, askerUuid = request.uuid, target = player.getCommandSenderName();
        boolean started = ClusterService.query(db -> {
            db.setArrival(askerUuid, node, dim, x, y, z);
            return Boolean.TRUE;
        }, ok -> {
            ClusterService.publishTo(ClusterService.KIND_TPA_OK, target, target, asker, node);
            if (player.playerNetServerHandler != null) {
                player.addChatMessage(
                    new ChatComponentText(
                        "§aЗапрос принят: §b" + asker + "§a переходит с сервера " + request.server + " к вам."));
            }
        }, error -> player.addChatMessage(new ChatComponentText("§cСеть серверов сейчас недоступна.")));
        if (!started) {
            player.addChatMessage(new ChatComponentText("§cСеть серверов сейчас недоступна."));
        }
        return true;
    }

    static void onMessage(ClusterDatabase.ChatRow row) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || row.target == null) {
            return;
        }
        EntityPlayerMP player = server.getConfigurationManager()
            .func_152612_a(row.target);
        if (player == null) {
            return;
        }
        if (ClusterService.KIND_TPA.equals(row.kind)) {
            onRequest(player, row);
        } else {
            onAccepted(player, row);
        }
    }

    private static void onRequest(EntityPlayerMP player, ClusterDatabase.ChatRow row) {
        incoming.computeIfAbsent(key(player.getCommandSenderName()), k -> new HashMap<>())
            .put(
                key(row.sender),
                new Incoming(row.sender, row.text, row.nodeName, System.currentTimeMillis() + REQUEST_MS));
        ChatComponentText accept = new ChatComponentText("[Принять]");
        accept.setChatStyle(
            new ChatStyle().setColor(EnumChatFormatting.GOLD)
                .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaccept " + row.sender))
                .setChatHoverEvent(
                    new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText("/tpaccept " + row.sender))));
        ChatComponentText message = new ChatComponentText(
            "§b" + row.sender + "§e с сервера " + row.nodeName + " хочет телепортироваться к вам. ");
        message.appendSibling(accept);
        player.addChatMessage(message);
    }

    private static void onAccepted(EntityPlayerMP player, ClusterDatabase.ChatRow row) {
        Outgoing asked = outgoing.get(player.getUniqueID());
        if (asked == null || !asked.target.equalsIgnoreCase(row.sender)
            || asked.expiresAt < System.currentTimeMillis()) {
            return;
        }
        outgoing.remove(player.getUniqueID());
        String nodeId = row.text;
        int timeout = CointConfig.cluster.nodeTimeoutSeconds;
        ClusterService.query(db -> db.nodes(timeout), nodes -> {
            for (ClusterDatabase.NodeRow node : nodes) {
                if (node.id.equals(nodeId) && node.online && player.playerNetServerHandler != null) {
                    player.addChatMessage(new ChatComponentText("§a" + row.sender + " принял ваш запрос на телепорт."));
                    ClusterTransfers.send(player, node);
                    return;
                }
            }
            player.addChatMessage(new ChatComponentText("§cСервер игрока " + row.sender + " сейчас недоступен."));
        }, error -> player.addChatMessage(new ChatComponentText("§cСеть серверов сейчас недоступна.")));
    }

    /** A player joined: if a /tpa from another server brought him, he goes to the spot it was accepted at. */
    static void onLogin(EntityPlayerMP player) {
        String uuid = player.getUniqueID()
            .toString();
        String node = ClusterService.nodeId();
        ClusterService.query(db -> db.takeArrival(uuid, node, ARRIVAL_SECONDS), place -> {
            if (place == null || player.playerNetServerHandler == null || player.isDead) {
                return;
            }
            if (!net.minecraftforge.common.DimensionManager.isDimensionRegistered((int) place[0])) {
                player.addChatMessage(new ChatComponentText("§cИзмерения " + (int) place[0] + " здесь нет."));
                CointCore.LOG.warn(
                    "[Cluster] Rejected arrival for {}: unknown DIM {}",
                    player.getCommandSenderName(),
                    (int) place[0]);
                return;
            }
            CointCore.LOG.info(
                "[Cluster] {} arrived by /tpa: DIM {} {} {} {}",
                player.getCommandSenderName(),
                (int) place[0],
                place[1],
                place[2],
                place[3]);
            TeleporterDimPos.of(place[1], place[2], place[3], (int) place[0])
                .teleport(player);
        }, null);
    }

    /** Drops what has run out. */
    static void prune() {
        long now = System.currentTimeMillis();
        outgoing.values()
            .removeIf(o -> o.expiresAt < now);
        Iterator<Map<String, Incoming>> it = incoming.values()
            .iterator();
        while (it.hasNext()) {
            Map<String, Incoming> requests = it.next();
            requests.values()
                .removeIf(r -> r.expiresAt < now);
            if (requests.isEmpty()) {
                it.remove();
            }
        }
    }

    static void reset() {
        outgoing.clear();
        incoming.clear();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static final class Outgoing {

        final String target;
        final long expiresAt;

        Outgoing(String target, long expiresAt) {
            this.target = target;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Incoming {

        final String name, uuid, server;
        final long expiresAt;

        Incoming(String name, String uuid, String server, long expiresAt) {
            this.name = name;
            this.uuid = uuid;
            this.server = server;
            this.expiresAt = expiresAt;
        }
    }
}
