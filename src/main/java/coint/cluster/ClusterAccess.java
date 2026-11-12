package coint.cluster;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.lib.data.Universe;
import serverutils.ranks.Ranks;

/** One trusted publisher, read-only replicas. Configuration and lifecycle wiring belong to the main integration. */
@EventBusSubscriber
public final class ClusterAccess {

    private static String authority = "";
    private static int pollSeconds = 5;
    private static boolean syncOp;
    private static long generation, nextPoll, revision;
    private static boolean inFlight, initialized, acceptLocalState, missingBaselineLogged, serverReady;
    private static String published;
    private static String lastAppliedLocal;
    private static long nextReplicaExpiry = Long.MAX_VALUE;

    private ClusterAccess() {}

    /** Configure in preInit/serverStarting; call start after ranks and temporary ranks initialize. Server thread. */
    public static void configure(String accessAuthorityNodeId, int accessPollSeconds, boolean accessSyncOp) {
        reset();
        authority = accessAuthorityNodeId == null ? "" : accessAuthorityNodeId.trim();
        if (authority.length() > 64) {
            throw new IllegalArgumentException("accessAuthorityNodeId exceeds 64 characters");
        }
        pollSeconds = Math.max(1, accessPollSeconds);
        syncOp = accessSyncOp;
    }

    /** Call at cluster stop/start. Old queued callbacks cannot mutate the next server session. */
    public static void reset() {
        generation++;
        authority = "";
        nextPoll = revision = 0;
        inFlight = initialized = false;
        acceptLocalState = missingBaselineLogged = false;
        serverReady = false;
        published = null;
        lastAppliedLocal = null;
        nextReplicaExpiry = Long.MAX_VALUE;
    }

    public static boolean enabled() {
        return !authority.isEmpty();
    }

    /** Call at the end of serverStarted, after RanksManager.updateRanks and TempRankManager.restoreAll. */
    public static void start() {
        serverReady = true;
        nextPoll = 0;
    }

    /**
     * Explicit administrator recovery hook for a replacement authority without its baseline file. The caller must
     * authorize this as accepting the entire current local rules/OP state; never call automatically at startup.
     */
    public static void acceptLocalAuthorityState() {
        if (!enabled() || !authority.equals(ClusterService.nodeId())) {
            throw new IllegalStateException("This node is not the access authority");
        }
        acceptLocalState = true;
        nextPoll = 0;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !serverReady
            || !enabled()
            || !ClusterService.isRunning()
            || !Universe.loaded()
            || Ranks.INSTANCE == null
            || Ranks.INSTANCE.universe != Universe.get()
            || !Ranks.isActive()) {
            return;
        }
        // Do not publish defaults before CointCore registers its dynamic epoch definitions.
        if (!Ranks.INSTANCE.ranks.keySet()
            .containsAll(AccessRanks.localEpochs())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (inFlight || now < nextPoll) {
            return;
        }
        nextPoll = now + pollSeconds * 1000L;
        final long session = generation;
        final String trusted = authority;
        final String local = ClusterService.nodeId();
        final boolean publish = local.equals(trusted) && initialized;
        final long expectedRevision = revision;
        final String previousPayload = published;
        final boolean accepted = acceptLocalState;
        final String payload;
        try {
            payload = publish || (accepted && local.equals(trusted))
                ? AccessSnapshot.JSON.toJson(AccessRanks.capture(trusted, syncOp))
                : null;
        } catch (RuntimeException e) {
            CointCore.LOG.error("[ClusterAccess] Cannot capture access state; nothing published", e);
            return;
        }
        inFlight = true;
        boolean queued = ClusterService.query(db -> {
            if (publish && !payload.equals(previousPayload)) {
                if (AccessStore.write(db.connection(), trusted, local, expectedRevision, payload)) {
                    AccessStore.saveBaseline(trusted, payload);
                    return new Result(new AccessStore.Row(expectedRevision + 1, payload), true);
                }
            }
            AccessStore.Row row = AccessStore.read(db.connection(), trusted);
            // Explicit acceptance must persist proof even if the reviewed local state already equals the DB.
            if (accepted && local.equals(trusted) && row != null && row.payload.equals(payload)) {
                AccessStore.saveBaseline(trusted, payload);
            }
            boolean allowed = !local.equals(trusted) || row == null || accepted || AccessStore.hasBaseline(trusted);
            return new Result(row, allowed);
        }, result -> {
            if (session != generation) {
                return;
            }
            inFlight = false;
            try {
                AccessStore.Row row = result.row;
                if (!result.allowed) {
                    if (!missingBaselineLogged) {
                        CointCore.LOG.error(
                            "[ClusterAccess] Existing authority DB snapshot but no local baseline. "
                                + "Publishing blocked. Restore cointcore/access-authority-*.json from this authority's "
                                + "backup or explicitly authorize acceptLocalAuthorityState() after reviewing local rules/OP.");
                        missingBaselineLogged = true;
                    }
                    return;
                }
                if (row == null) {
                    if (local.equals(trusted)) {
                        initialized = true;
                    }
                    return;
                }
                // Authority files remain authoritative, including intentional offline edits and /ranks reload.
                // Check expiry before the local fingerprint: time alone can invalidate an unchanged assignment.
                if (!local.equals(trusted)) {
                    long appliedAt = System.currentTimeMillis();
                    if (replicaNeedsApply(row.revision, appliedAt)) {
                        AccessSnapshot snapshot = AccessSnapshot.JSON.fromJson(row.payload, AccessSnapshot.class);
                        snapshot.validate(trusted);
                        AccessRanks.apply(snapshot, syncOp);
                        // Capture AFTER reconciliation, including any normalization performed by SU/save hooks.
                        lastAppliedLocal = localFingerprint();
                        nextReplicaExpiry = nextExpiry(snapshot, appliedAt);
                    }
                } else {
                    AccessSnapshot snapshot = AccessSnapshot.JSON.fromJson(row.payload, AccessSnapshot.class);
                    snapshot.validate(trusted);
                }
                revision = row.revision;
                published = row.payload;
                initialized = true;
                if (local.equals(trusted) && row.payload.equals(payload)) {
                    acceptLocalState = false;
                }
            } catch (RuntimeException e) {
                CointCore.LOG.error("[ClusterAccess] Rejected access snapshot; will retry", e);
            }
        }, error -> {
            if (session == generation) {
                inFlight = false;
            }
        });
        if (!queued) {
            inFlight = false;
        }
    }

    private static String localFingerprint() {
        return AccessSnapshot.JSON.toJson(AccessRanks.capture(authority, syncOp));
    }

    private static boolean replicaNeedsApply(long incomingRevision, long now) {
        return lastAppliedLocal == null || incomingRevision != revision
            || now >= nextReplicaExpiry
            || !lastAppliedLocal.equals(localFingerprint());
    }

    private static long nextExpiry(AccessSnapshot snapshot, long appliedAt) {
        long next = Long.MAX_VALUE;
        for (java.util.Map<String, Long> deadlines : snapshot.parentExpiry.values()) {
            for (long deadline : deadlines.values()) {
                if (deadline > appliedAt && deadline < next) {
                    next = deadline;
                }
            }
        }
        return next;
    }

    private static final class Result {

        final AccessStore.Row row;
        final boolean allowed;

        Result(AccessStore.Row row, boolean allowed) {
            this.row = row;
            this.allowed = allowed;
        }
    }
}
