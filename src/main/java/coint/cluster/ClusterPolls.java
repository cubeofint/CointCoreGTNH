package coint.cluster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.epochsync.EpochEntry;
import coint.epochsync.EpochRegistry;
import coint.integration.serverutilities.RanksManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

@EventBusSubscriber
public final class ClusterPolls {

    private static final long REFRESH_MS = 5000;
    private static final Map<NetworkManager, String> offered = new HashMap<>();
    private static final Set<UUID> writing = new HashSet<>();
    private static ClusterPollStore.Poll current;
    private static long nextRefresh;
    private static boolean refreshing;

    private ClusterPolls() {}

    public static void reset() {
        offered.clear();
        writing.clear();
        current = null;
        nextRefresh = 0;
        refreshing = false;
        PollNetwork.clearConnections();
    }

    static void refreshSoon() {
        nextRefresh = 0;
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        refreshSoon();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClusterService.isRunning() || refreshing) return;
        long now = System.currentTimeMillis();
        if (now < nextRefresh) return;
        nextRefresh = now + REFRESH_MS;

        Set<UUID> online = new HashSet<>();
        for (EntityPlayerMP player : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            if (PollNetwork.supports(player)) online.add(player.getUniqueID());
        }
        if (online.isEmpty()) {
            current = null;
            offered.clear();
            return;
        }
        refreshing = ClusterService.query(db -> ClusterPollStore.snapshot(db.connection(), online), snapshot -> {
            refreshing = false;
            if (ClusterService.isRunning()) apply(snapshot, online);
        }, error -> refreshing = false);
    }

    private static void apply(ClusterPollStore.Snapshot snapshot, Set<UUID> checked) {
        current = snapshot.poll;
        PollNetwork.Prompt prompt = current == null ? null
            : new PollNetwork.Prompt(current.definition, current.timeLeft);
        Map<NetworkManager, String> next = new HashMap<>();
        for (EntityPlayerMP player : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            if (!PollNetwork.supports(player)) continue;
            UUID id = player.getUniqueID();
            NetworkManager connection = PollNetwork.connection(player);
            String previous = offered.get(connection);
            if (prompt != null && checked.contains(id)
                && !snapshot.voted.contains(id)
                && eligible(player, current.epoch)) {
                PollNetwork.prompt(player, prompt);
                next.put(connection, current.definition.id);
            } else if (previous != null) {
                PollNetwork.reply(player, previous, PollNetwork.Outcome.CLOSED);
            }
        }
        offered.clear();
        offered.putAll(next);
    }

    static boolean eligible(EntityPlayerMP player, String epoch) {
        if (EpochRegistry.INST == null) return false;
        EpochEntry minimum = EpochRegistry.INST.getEpoch(epoch);
        if (minimum == null) return false;
        EpochEntry actual = RanksManager.get()
            .getPlayerEpoch(player.getUniqueID());
        return actual != null && actual.priority >= minimum.priority;
    }

    static void vote(EntityPlayerMP player, String id, int choice) {
        if (!PollNetwork.supports(player)) return;
        NetworkManager connection = PollNetwork.connection(player);
        if (!ClusterService.isRunning() || current == null
            || !id.equals(current.definition.id)
            || !id.equals(offered.get(connection))
            || !eligible(player, current.epoch)) {
            PollNetwork.reply(player, id, PollNetwork.Outcome.CLOSED);
            return;
        }
        if (choice < 0 || choice >= current.definition.answers.size()) {
            PollNetwork.reply(player, id, PollNetwork.Outcome.RETRY);
            return;
        }
        UUID playerId = player.getUniqueID();
        if (!writing.add(playerId)) return;
        if (!ClusterService.query(
            db -> ClusterPollStore.vote(db.connection(), id, playerId, choice),
            saved -> finishVote(player, connection, id, saved ? PollNetwork.Outcome.SAVED : PollNetwork.Outcome.CLOSED),
            error -> finishVote(player, connection, id, PollNetwork.Outcome.RETRY))) {
            finishVote(player, connection, id, PollNetwork.Outcome.RETRY);
        }
    }

    private static void finishVote(EntityPlayerMP player, NetworkManager connection, String id,
        PollNetwork.Outcome outcome) {
        writing.remove(player.getUniqueID());
        if (outcome != PollNetwork.Outcome.RETRY) offered.remove(connection, id);
        if (PollNetwork.connection(player) == connection && PollNetwork.supports(player)) {
            PollNetwork.reply(player, id, outcome);
        }
    }
}
