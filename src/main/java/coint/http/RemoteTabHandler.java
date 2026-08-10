package coint.http;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S38PacketPlayerListItem;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.lib.data.Universe;

@EventBusSubscriber
public class RemoteTabHandler {

    @EventBusSubscriber.Condition
    public static boolean isEnabled() {
        return false;
    }

    private static final int MAX_NAME_LENGTH = 16;
    private static final long STALE_TIMEOUT_MS = 20_000;

    private static final Map<String, Map<String, String>> listByServer = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastSeenByServer = new ConcurrentHashMap<>();

    private static int ticks = 0;

    public static void onServerInfo(String serverTag, List<WebSocketMessage.Player> players) {
        if (serverTag == null || serverTag.isEmpty() || serverTag.equals(CointConfig.api.serverTag)) {
            return;
        }

        lastSeenByServer.put(serverTag, System.currentTimeMillis());
        Map<String, String> current = listByServer.computeIfAbsent(serverTag, k -> new HashMap<>());
        Set<String> stillOnline = new HashSet<>();

        for (WebSocketMessage.Player player : players) {
            stillOnline.add(player.name);
            String tabName = buildTabName(serverTag, player.name);
            current.put(player.name, tabName);
            broadcast(new S38PacketPlayerListItem(tabName, true, 0));
        }

        current.entrySet()
            .removeIf(e -> {
                if (stillOnline.contains(e.getKey())) return false;
                broadcast(new S38PacketPlayerListItem(e.getValue(), false, 0));
                return true;
            });
    }

    public static void syncTo(EntityPlayerMP player) {
        for (Map<String, String> roster : listByServer.values()) {
            for (String tabName : roster.values()) {
                player.playerNetServerHandler.sendPacket(new S38PacketPlayerListItem(tabName, true, 0));
            }
        }
    }

    public static void clearAll() {
        for (Map<String, String> roster : listByServer.values()) {
            for (String tabName : roster.values()) {
                broadcast(new S38PacketPlayerListItem(tabName, false, 0));
            }
        }
        listByServer.clear();
        lastSeenByServer.clear();
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++ticks < 100) return;
        ticks = 0;

        long now = System.currentTimeMillis();
        lastSeenByServer.entrySet()
            .removeIf(e -> {
                if (now - e.getValue() < STALE_TIMEOUT_MS) return false;
                purgeServer(e.getKey());
                return true;
            });
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP playerMP) {
            syncTo(playerMP);
        }
    }

    private static void purgeServer(String serverTag) {
        Map<String, String> roster = listByServer.remove(serverTag);
        if (roster == null) return;
        for (String tabName : roster.values()) {
            broadcast(new S38PacketPlayerListItem(tabName, false, 0));
        }
    }

    private static String buildTabName(String serverTag, String playerName) {
        String colored = "§7[" + serverTag + "]§r " + playerName;
        if (colored.length() <= MAX_NAME_LENGTH) return colored;

        String plain = "[" + serverTag + "] " + playerName;
        if (plain.length() <= MAX_NAME_LENGTH) return plain;

        String prefix = "[" + serverTag + "] ";
        int room = MAX_NAME_LENGTH - prefix.length();
        if (room <= 0) {
            return playerName.length() > MAX_NAME_LENGTH ? playerName.substring(0, MAX_NAME_LENGTH) : playerName;
        }
        return prefix + playerName.substring(0, Math.min(room, playerName.length()));
    }

    private static void broadcast(S38PacketPlayerListItem packet) {
        for (var player : Universe.get()
            .getOnlinePlayers()) {
            player.getPlayer().playerNetServerHandler.sendPacket(packet);
        }
    }
}
