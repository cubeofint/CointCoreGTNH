package coint.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

import com.mojang.authlib.GameProfile;

import coint.CointConfig;
import coint.CointCore;
import coint.player.PlayerLookup;
import coint.player.PlayerResetData;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

/** SQL journal drives this server-thread state machine; chat is only a status display. */
public final class ClusterPlayerResets {

    private static final Set<String> blocked = new HashSet<>();
    private static final Set<String> appliedThisStart = new HashSet<>();
    private static final Set<String> homeRefreshed = new HashSet<>();
    private static final Map<String, String> failures = new HashMap<>();
    private static List<PlayerResetStore.Job> jobs = new ArrayList<>();
    private static boolean polling, reporting, loaded;
    private static long nextPoll, nextWork, generation;
    private static int workCursor;

    private ClusterPlayerResets() {}

    static void reset() {
        generation++;
        jobs.clear();
        blocked.clear();
        appliedThisStart.clear();
        homeRefreshed.clear();
        failures.clear();
        polling = reporting = loaded = false;
        nextPoll = nextWork = 0;
        workCursor = 0;
    }

    public static boolean blocked(String uuid) {
        return blocked.contains(uuid);
    }

    /** Server-thread delivery fence; the ledger/snapshots retain every grant for delivery after reset. */
    static boolean blocksDonation(String uuid, String name) {
        for (PlayerResetStore.Job job : jobs) {
            if (!job.state.equals("PREPARE") && !job.state.equals("APPLY")
                && !(job.state.equals("DONE") && !job.phase.equals("APPLIED"))) continue;
            // A known UUID wins over a reused name. Name fallback covers purchases awaiting identity resolution.
            if (uuid != null ? job.uuid.equals(uuid) : name != null && job.name.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Bootstrap and the destructive barrier cannot race stale access snapshots or donor callbacks. */
    public static boolean accessPaused() {
        return CointConfig.cluster.enabled && !loaded;
    }

    /** Filter only accounts inside the destructive barrier; unrelated access and donation work keeps running. */
    static void filterAccess(AccessSnapshot snapshot) {
        for (PlayerResetStore.Job job : jobs) {
            if ((!job.state.equals("APPLY") && !(job.state.equals("DONE") && !job.phase.equals("APPLIED")))
                || (!job.mode.equals("all") && !job.mode.equals("data"))) continue;
            snapshot.players.remove(job.uuid);
            snapshot.parentExpiry.remove(job.uuid);
            if (snapshot.temporaryRanks != null) snapshot.temporaryRanks.removeIf(
                e -> e.playerUuid.toString()
                    .equals(job.uuid));
        }
    }

    public static void command(ICommandSender sender, String input, String mode, String successorInput) {
        if (!ClusterService.isRunning()) {
            tell(sender, "§cКластер недоступен; локальный частичный сброс запрещён.");
            return;
        }
        ForgePlayer local = PlayerLookup.exact(input);
        GameProfile profile = local == null ? null : local.getProfile();
        ForgePlayer localSuccessor = successorInput == null ? null : PlayerLookup.exact(successorInput);
        GameProfile successorProfile = localSuccessor == null ? null : localSuccessor.getProfile();
        String node = ClusterService.nodeId();
        String home = CointConfig.cluster.playerResetHomeNodeId.trim();
        String authority = CointConfig.cluster.accessAuthorityNodeId.trim();
        if (mode.equals("all")
            && (home.isEmpty() || home.length() > 64 || home.equals(CointConfig.cluster.hubNodeId))) {
            tell(sender, "§cЗадайте cluster.playerResetHomeNodeId: id сервера Alpha, отличный от хаба.");
            return;
        }
        ClusterService.query(db -> {
            GameProfile target = PlayerResetStore.identity(db.connection(), input, profile);
            String uuid = target.getId()
                .toString();
            if (mode.equals("status")) return PlayerResetStore.status(db.connection(), uuid);
            if (mode.equals("cancel")) {
                PlayerResetStore.cancel(db.connection(), uuid);
                return PlayerResetStore.status(db.connection(), uuid);
            }
            GameProfile successor = successorInput == null ? null
                : PlayerResetStore.identity(db.connection(), successorInput, successorProfile);
            if (successor != null && successor.getId()
                .equals(target.getId())) throw new java.sql.SQLException("Нельзя передать лидерство самому себе");
            PlayerResetStore.Job job = PlayerResetStore
                .request(db.connection(), target, mode, successor, node, home, authority);
            return "Сброс " + job.id
                + " "
                + job.mode
                + " "
                + job.state
                + ". Вход заблокирован до подтверждений всех узлов. /playerreset "
                + uuid
                + " status";
        }, text -> {
            nextPoll = nextWork = 0;
            tell(sender, "§e" + text);
        }, error -> tell(sender, "§cСброс не завершён: " + error));
    }

    static void tick(long now) {
        if (!Universe.loaded()) return;
        String node = ClusterService.nodeId();
        long session = generation;
        if (!polling && !reporting && now >= nextPoll) {
            polling = true;
            nextPoll = now + 1000;
            Set<String> refreshed = new HashSet<>(homeRefreshed);
            ClusterService.query(db -> {
                List<PlayerResetStore.Job> values = PlayerResetStore.poll(db.connection(), node);
                for (PlayerResetStore.Job job : values) {
                    if (job.state.equals("DONE") && job.mode.equals("all") && !refreshed.contains(job.id))
                        job.currentHome = db.home(job.uuid, job.name, null);
                    try {
                        PlayerResetStore.advance(db.connection(), job);
                    } catch (java.sql.SQLException e) {
                        PlayerResetStore.report(db.connection(), job, node, job.phase, null, e.getMessage());
                    }
                }
                return values;
            }, values -> {
                if (session != generation) return;
                polling = false;
                loaded = true;
                jobs = values;
                blocked.clear();
                for (PlayerResetStore.Job job : jobs) {
                    if (!job.state.equals("DONE") || !job.phase.equals("APPLIED")) blocked.add(job.uuid);
                    if (job.currentHome != null && homeRefreshed.add(job.id))
                        ClusterHomes.homeChanged(job.uuid, job.currentHome);
                }
            }, error -> { if (session == generation) polling = false; });
        }
        for (String uuid : blocked) ClusterSessions.quiesce(uuid);
        if (!loaded || polling || reporting || now < nextWork) return;
        nextWork = now + 1000;
        // Ordered history matters on newly added nodes: a later targeted reset does not erase an older full tombstone.
        for (int offset = 0; offset < jobs.size(); offset++) {
            int index = (workCursor + offset) % jobs.size();
            PlayerResetStore.Job job = jobs.get(index);
            boolean preparing = job.state.equals("PREPARE") || (job.state.equals("DONE") && job.phase.equals("WAIT"));
            if (preparing && job.phase.equals("PREPARED")) continue;
            if (!preparing && job.phase.equals("APPLIED")
                && (job.state.equals("DONE") || appliedThisStart.contains(job.id))) continue;
            if (priorPending(jobs, index)) continue;
            if (!ClusterSessions.quiesce(job.uuid)) continue;
            String phase = preparing ? "PREPARED" : "APPLIED";
            String retained = null;
            String error = "";
            try {
                PlayerResetData.validate(UUID.fromString(job.uuid), job.name, job.mode, job.successor);
                if (preparing) retained = PlayerResetData.capturePaidData(UUID.fromString(job.uuid));
                else {
                    PlayerResetData.apply(
                        UUID.fromString(job.uuid),
                        job.name,
                        job.mode,
                        job.successor,
                        job.retained.values(),
                        job.retained.get(job.oldHome),
                        job.retained.get(node),
                        node.equals(job.mode.equals("all") ? job.home : job.oldHome));
                    appliedThisStart.add(job.id);
                }
                failures.remove(job.id);
            } catch (Exception | LinkageError e) {
                phase = job.phase;
                error = String.valueOf(e);
                if (!error.equals(failures.put(job.id, error)))
                    CointCore.LOG.warn("[PlayerReset] {} on {} retained for retry: {}", job.id, node, error);
            }
            final String donePhase = phase, doneRetained = retained, failure = error;
            workCursor = (index + 1) % jobs.size();
            reporting = true;
            ClusterService.query(db -> {
                PlayerResetStore.report(db.connection(), job, node, donePhase, doneRetained, failure);
                return true;
            }, ignored -> {
                if (session == generation) {
                    reporting = false;
                    nextPoll = 0;
                }
            }, ignored -> { if (session == generation) reporting = false; });
            break;
        }
    }

    private static void tell(ICommandSender sender, String message) {
        for (String line : message.split("\n")) sender.addChatMessage(new ChatComponentText(line));
    }

    /** A late node replays this account's reset history in order even while other accounts can make progress. */
    static boolean priorPending(List<PlayerResetStore.Job> history, int index) {
        for (int i = 0; i < index; i++) {
            PlayerResetStore.Job earlier = history.get(i);
            if (earlier.uuid.equals(history.get(index).uuid) && !"APPLIED".equals(earlier.phase)) return true;
        }
        return false;
    }
}
