package coint.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.google.gson.Gson;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;
import com.mojang.authlib.GameProfile;

import coint.CointConfig;
import coint.integration.serverutilities.CointSUPermissions;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.lib.util.permission.PermissionAPI;

/** Target-owned FIFO. All decisions and reservations run on the target server thread. */
@EventBusSubscriber
public final class ClusterEntryQueue {

    static final String KIND = "entryqueue";
    private static final Gson JSON = new Gson();
    private static final Map<UUID, Outgoing> outgoing = new HashMap<>();
    private static final Map<UUID, Waiting> waiting = new LinkedHashMap<>();
    private static final Map<String, Long> cancelled = new HashMap<>();
    private static long nextTick;

    private ClusterEntryQueue() {}

    static void reset() {
        outgoing.clear();
        waiting.clear();
        cancelled.clear();
        nextTick = 0;
    }

    static void request(EntityPlayerMP player, ClusterDatabase.NodeRow node) {
        UUID uuid = player.getUniqueID();
        if (ClusterTransfers.isLeaving(uuid)) return;
        Outgoing old = outgoing.get(uuid);
        if (old != null && old.node.id.equals(node.id)) {
            notice(player, "§eВы уже ожидаете входа: " + node.name + ". Отмена: /server cancel");
            return;
        }
        cancel(uuid);
        Outgoing entry = new Outgoing(player, node);
        outgoing.put(uuid, entry);
        notice(player, "§eПроверяем свободные места на " + node.name + ". Отмена: /server cancel");
        send(node.id, entry.message("request"));
    }

    public static void cancel(UUID uuid) {
        Outgoing entry = outgoing.remove(uuid);
        if (entry != null) send(entry.node.id, entry.message("cancel"));
    }

    static int position(UUID uuid, String node) {
        Outgoing entry = outgoing.get(uuid);
        return entry != null && entry.node.id.equals(node) ? entry.position : -1;
    }

    /** Protect granted slots from direct logins as well as other transfers. */
    public static boolean hasRoom(GameProfile profile, int online, int maximum, int reserved, boolean bypass) {
        long now = System.currentTimeMillis();
        int held = 0;
        boolean mine = false, queued = false;
        for (Map.Entry<UUID, Waiting> e : waiting.entrySet()) {
            Waiting w = e.getValue();
            if (w.deadline <= now || local(e.getKey()) != null) continue;
            if (w.granted) {
                if (e.getKey()
                    .equals(profile.getId())) mine = true;
                else held++;
            } else queued = true;
        }
        // A reserved-slot player may arrive before an ordinary player whose slot was already granted.
        // Honour that existing grant; other admissions still count it against total capacity.
        long limit = (long) maximum + (bypass || mine ? reserved : 0);
        return online + (long) held < limit && (mine || bypass || !queued);
    }

    static void onMessage(ClusterDatabase.ChatRow row) {
        if (!ClusterService.nodeId()
            .equals(row.target)) return;
        final Message m;
        final UUID uuid;
        try {
            m = JSON.fromJson(row.text, Message.class);
            uuid = UUID.fromString(m.uuid);
            UUID.fromString(m.token);
            if (m.name == null || !m.name.matches("[A-Za-z0-9_.]{1,16}")) return;
        } catch (RuntimeException e) {
            return;
        }
        long now = System.currentTimeMillis();
        if ("request".equals(m.action)) {
            if (cancelled.getOrDefault(row.nodeId + ":" + m.token, 0L) > now) return;
            Waiting w = waiting.get(uuid);
            if (w == null) {
                waiting.put(uuid, new Waiting(row.nodeId, m, now));
            } else if (w.source.equals(row.nodeId) && w.message.token.equals(m.token) && !w.granted) {
                w.deadline = now + 15_000;
            }
        } else if ("cancel".equals(m.action)) {
            cancelled.put(row.nodeId + ":" + m.token, now + 120_000);
            Waiting w = waiting.get(uuid);
            if (w != null && w.source.equals(row.nodeId) && w.message.token.equals(m.token)) waiting.remove(uuid);
        } else {
            Outgoing entry = outgoing.get(uuid);
            if (entry == null || !entry.node.id.equals(row.nodeId) || !entry.token.equals(m.token)) return;
            entry.lastReply = now;
            if ("ready".equals(m.action) && m.deadline > now + 5000) {
                outgoing.remove(uuid);
                if (local(uuid) == entry.player) ClusterTransfers.sendGranted(entry.player, entry.node);
                else send(row.nodeId, entry.message("cancel"));
            } else if ("waiting".equals(m.action)) {
                if (entry.position != m.position) {
                    entry.position = m.position;
                    notice(
                        entry.player,
                        "§eОчередь на " + entry.node.name
                            + ": №"
                            + m.position
                            + ". При освобождении места переход начнётся автоматически. Отмена: /server cancel");
                }
            }
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.currentTimeMillis();
        if (now < nextTick) return;
        nextTick = now + 3000;
        cancelled.values()
            .removeIf(deadline -> deadline <= now);
        if (!ClusterService.isRunning()) {
            for (Outgoing e : outgoing.values()) notice(e.player, "§cОчередь отменена: сеть недоступна.");
            outgoing.clear();
            waiting.clear();
            return;
        }
        for (Map.Entry<UUID, Outgoing> e : new ArrayList<>(outgoing.entrySet())) {
            Outgoing o = e.getValue();
            if (local(e.getKey()) != o.player || now - o.lastReply > 25_000) {
                cancel(e.getKey());
                if (local(e.getKey()) == o.player) notice(o.player, "§cОчередь отменена: целевой сервер не отвечает.");
            } else send(o.node.id, o.message("request"));
        }
        waiting.entrySet()
            .removeIf(e -> e.getValue().deadline <= now || local(e.getKey()) != null);
        MinecraftServer server = MinecraftServer.getServer();
        int occupied = server.getCurrentPlayerCount();
        for (Waiting w : waiting.values()) if (w.granted) occupied++;
        int position = 0;
        for (Waiting w : waiting.values()) {
            if (!w.granted) {
                GameProfile profile = new GameProfile(UUID.fromString(w.message.uuid), w.message.name);
                boolean bypass = PermissionAPI.hasPermission(profile, CointSUPermissions.RESERVED_SLOT, null);
                long limit = (long) server.getMaxPlayers()
                    + (bypass ? Math.max(0, CointConfig.general.reservedSlots) : 0);
                if (occupied < limit) {
                    w.granted = true;
                    w.deadline = now + 120_000;
                    occupied++;
                }
            }
            w.message.action = w.granted ? "ready" : "waiting";
            w.message.position = w.granted ? 0 : ++position;
            w.message.deadline = w.deadline;
            send(w.source, w.message);
        }
    }

    private static EntityPlayerMP local(UUID uuid) {
        for (Object value : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) value;
            if (uuid.equals(player.getUniqueID())) return player;
        }
        return null;
    }

    private static void notice(EntityPlayerMP p, String text) {
        if (p.playerNetServerHandler != null) p.addChatMessage(new ChatComponentText(text));
    }

    private static void send(String target, Message message) {
        ClusterService.publishTo(KIND, message.name, message.name, target, JSON.toJson(message));
    }

    private static final class Message {

        String action, uuid, name, token;
        int position;
        long deadline;
    }

    private static final class Outgoing {

        final EntityPlayerMP player;
        final ClusterDatabase.NodeRow node;
        final String token = UUID.randomUUID()
            .toString();
        long lastReply = System.currentTimeMillis();
        int position;

        Outgoing(EntityPlayerMP player, ClusterDatabase.NodeRow node) {
            this.player = player;
            this.node = node;
        }

        Message message(String action) {
            Message m = new Message();
            m.action = action;
            m.uuid = player.getUniqueID()
                .toString();
            m.name = player.getCommandSenderName();
            m.token = token;
            return m;
        }
    }

    private static final class Waiting {

        final String source;
        final Message message;
        long deadline;
        boolean granted;

        Waiting(String source, Message message, long now) {
            this.source = source;
            this.message = message;
            this.deadline = now + 15_000;
        }
    }
}
