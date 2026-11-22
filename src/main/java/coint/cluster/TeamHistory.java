package coint.cluster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import coint.epochsync.EpochEntry;
import coint.migration.MoveRecord;
import coint.teamhistory.TeamHistoryData;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

/** Server-thread snapshots; all persistence/query work uses the existing asynchronous cluster worker. */
@EventBusSubscriber
public final class TeamHistory {

    private static final Map<String, String> publishedMembers = new HashMap<>();
    private static final Map<String, String> publishedTitles = new HashMap<>();
    private static final Map<String, String> homeView = new HashMap<>();
    private static final Map<UUID, Long> requested = new HashMap<>();
    private static final Set<UUID> reading = new HashSet<>();
    private static long generation, nextFlush;
    private static boolean writing;

    private TeamHistory() {}

    public static void reset() {
        generation++;
        nextFlush = 0;
        writing = false;
        publishedMembers.clear();
        publishedTitles.clear();
        homeView.clear();
        requested.clear();
        reading.clear();
    }

    public static String prepare(ForgeTeam team) {
        return valid(team) ? TeamHistoryData.of(team)
            .ensureId(team) : null;
    }

    /** Joining an advanced team or replaying an old quest is not a new team milestone. Call before rank assignment. */
    public static boolean advancesTeam(UUID playerId, EpochEntry epoch) {
        if (!ClusterService.isRunning() || !Universe.loaded() || ClusterHub.isHub()) return false;
        ForgePlayer player = Universe.get()
            .getPlayer(playerId);
        if (player == null || !player.hasTeam() || !valid(player.team)) return false;
        if (!ClusterService.nodeId()
            .equals(ClusterHomes.homeOf(playerId))) return false;
        for (ForgePlayer member : player.team.getMembers()) {
            EpochEntry previous = coint.integration.serverutilities.RanksManager.get()
                .getPlayerEpoch(member.getId());
            if (previous != null && previous.priority >= epoch.priority) return false;
        }
        return true;
    }

    public static void epoch(UUID playerId, EpochEntry epoch) {
        if (!ClusterService.isRunning() || !Universe.loaded() || ClusterHub.isHub()) return;
        ForgePlayer player = Universe.get()
            .getPlayer(playerId);
        if (player == null || !player.hasTeam() || !valid(player.team)) return;
        if (!ClusterService.nodeId()
            .equals(ClusterHomes.homeOf(playerId))) return;
        TeamHistoryData.of(player.team)
            .record(
                player.team,
                "epoch:" + epoch.rankName,
                "epoch",
                epoch.displayName == null ? epoch.rankName : epoch.displayName,
                player.getName(),
                ClusterService.nodeId(),
                "",
                System.currentTimeMillis());
    }

    /** Only the successfully receiving node records a completed team migration. */
    public static void moved(MoveRecord move) {
        if (move.historyId == null || move.joining()
            || !ClusterService.nodeId()
                .equals(move.target)
            || !Universe.loaded()) return;
        ForgeTeam team = Universe.get()
            .getTeam(move.team);
        if (!valid(team)) return;
        if (!move.historyId.equals(
            TeamHistoryData.of(team)
                .existingId())) {
            CointCore.LOG.warn("[TeamHistory] History identity did not survive move {}", move.id);
            return;
        }
        TeamHistoryData.of(team)
            .record(
                team,
                "move:" + move.id,
                "move",
                "",
                move.actor,
                move.source,
                move.target,
                System.currentTimeMillis());
    }

    private static boolean valid(ForgeTeam team) {
        return team != null && team.isValid() && team.type.isPlayer && TeamHistoryData.of(team) != null;
    }

    private static TeamHistoryStore.Batch capture() {
        TeamHistoryStore.Batch batch = new TeamHistoryStore.Batch();
        String node = ClusterService.nodeId();
        Set<String> histories = new HashSet<>();
        // Include offline members: removing an offline player must also remove their old history access.
        if (!ClusterHub.isHub()) for (ForgePlayer player : Universe.get().players.values()) {
            String uuid = player.getId()
                .toString();
            String home = homeView.getOrDefault(uuid, ClusterHomes.homeOf(player.getId()));
            if (!node.equals(home)) continue;
            String history = player.hasTeam() && valid(player.team) ? TeamHistoryData.of(player.team)
                .ensureId(player.team) : null;
            TeamHistoryStore.Member member = new TeamHistoryStore.Member(uuid, node, history);
            if (!member.signature()
                .equals(publishedMembers.get(uuid))) batch.members.add(member);
            if (history != null) histories.add(history);
        }
        for (ForgeTeam team : Universe.get()
            .getTeams()) {
            if (!valid(team)) continue;
            TeamHistoryData data = TeamHistoryData.of(team);
            if (data.existingId()
                .isEmpty()) continue;
            TeamHistoryStore.Team snapshot = new TeamHistoryStore.Team();
            snapshot.history = data.existingId();
            snapshot.events = data.pending();
            snapshot.title = coint.teamhistory.HistoryEvent.plain(
                team.getTitle()
                    .getUnformattedText(),
                256);
            boolean owns = histories.contains(snapshot.history);
            if (snapshot.events.isEmpty() && (!owns || snapshot.title.equals(publishedTitles.get(snapshot.history))))
                continue;
            snapshot.since = data.since();
            snapshot.local = team;
            batch.teams.add(snapshot);
        }
        return batch;
    }

    private static void acknowledge(TeamHistoryStore.Batch batch) {
        for (TeamHistoryStore.Team team : batch.teams) {
            TeamHistoryData data = TeamHistoryData.of(team.local);
            if (data != null) data.acknowledged(team.local, team.history, team.events);
            publishedTitles.put(team.history, team.title);
        }
        for (TeamHistoryStore.Member member : batch.members) {
            if (batch.savedMembers.contains(member.uuid)) publishedMembers.put(member.uuid, member.signature());
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClusterService.isRunning()
            || !Universe.loaded()
            || writing
            || System.currentTimeMillis() < nextFlush) return;
        nextFlush = System.currentTimeMillis() + 10000;
        TeamHistoryStore.Batch batch = capture();
        writing = true;
        long session = generation;
        ClusterService.query(db -> {
            TeamHistoryStore.save(db.connection(), batch);
            return TeamHistoryStore.homes(db.connection());
        }, homes -> {
            if (generation != session) return;
            writing = false;
            homeView.clear();
            homeView.putAll(homes);
            acknowledge(batch);
        }, error -> {
            if (generation != session) return;
            writing = false; // Outbox stays in SU data and is retried, including after restart/migration.
        });
    }

    public static void request(EntityPlayerMP player, long before, int requestId, boolean open) {
        UUID uuid = player.getUniqueID();
        long now = System.currentTimeMillis();
        if (reading.contains(uuid) || now - requested.getOrDefault(uuid, 0L) < 500) return;
        requested.put(uuid, now);
        if (!ClusterService.isRunning() || !Universe.loaded()) {
            PacketTeamHistory error = new PacketTeamHistory();
            error.error = "cointcore.history.unavailable";
            reply(player, error, requestId, open);
            return;
        }
        TeamHistoryStore.Batch batch = capture();
        long session = generation;
        reading.add(uuid);
        ClusterService.query(db -> {
            TeamHistoryStore.save(db.connection(), batch);
            return TeamHistoryStore.page(db.connection(), uuid.toString(), Math.max(0, before));
        }, page -> {
            if (session != generation) return;
            reading.remove(uuid);
            acknowledge(batch);
            reply(player, page, requestId, open);
        }, error -> {
            if (session != generation) return;
            reading.remove(uuid);
            PacketTeamHistory page = new PacketTeamHistory();
            page.error = "cointcore.history.unavailable";
            reply(player, page, requestId, open);
        });
    }

    private static void reply(EntityPlayerMP player, PacketTeamHistory page, int requestId, boolean open) {
        if (player.playerNetServerHandler == null || player.playerNetServerHandler.netManager == null
            || !player.playerNetServerHandler.netManager.isChannelOpen()) return;
        page.request = requestId;
        page.open = open;
        if (TeamHistoryNetwork.supported(player.playerNetServerHandler.netManager)) {
            TeamHistoryNetwork.CHANNEL.sendTo(page, player);
        } else {
            player.addChatMessage(new ChatComponentText("§eИстория команды: обновите клиент для просмотра окна."));
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        requested.remove(event.player.getUniqueID());
        // Leave an in-flight read fenced until its callback, even if this UUID reconnects meanwhile.
    }
}
