package coint.commands.temprank;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import coint.CointConfig;
import coint.integration.serverutilities.RanksManager;
import serverutils.ranks.Rank;
import serverutils.ranks.Ranks;

/**
 * Manages temporary rank assignments: persistence, granting, revoking and
 * expiry checking.
 *
 * <p>
 * Data is stored in {@code cointcore/temp_ranks.json} (server root) so it
 * survives server restarts. SU's own {@code players.txt} holds the actual
 * parent-rank relation; our JSON file holds only the metadata (expiry, issuer).
 *
 * <h3>Lifecycle</h3>
 * <ol>
 * <li>{@code serverStarted} → {@link #restoreAll()} loads the JSON and
 * removes any entries that expired while the server was offline.</li>
 * <li>{@code TempRankTask} calls {@link #checkExpired()} every minute to
 * revoke ranks that have just expired.</li>
 * </ol>
 */
public class TempRankManager {

    private static final Logger LOG = LogManager.getLogger(TempRankManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();

    private static TempRankManager INSTANCE;

    /** All currently tracked entries (expired + active). Mutated only on server thread. */
    private final List<TempRankEntry> entries = new ArrayList<>();
    private List<TempRankEntry> replicaEntries = new ArrayList<>();

    public static boolean replica() {
        return CointConfig.cluster.enabled && !CointConfig.cluster.accessAuthorityNodeId.trim()
            .isEmpty() && !CointConfig.cluster.nodeId.equals(CointConfig.cluster.accessAuthorityNodeId.trim());
    }

    public static void requireAuthority() {
        if (replica()) throw new IllegalStateException(
            "Выдавайте временные ранги на ноде " + CointConfig.cluster.accessAuthorityNodeId);
    }

    public void receiveReplica(List<TempRankEntry> incoming) {
        replicaEntries = incoming == null ? new ArrayList<>() : new ArrayList<>(incoming);
    }

    public static com.mojang.authlib.GameProfile identity(String name) {
        serverutils.lib.data.ForgePlayer p = coint.player.PlayerLookup.exact(name);
        if (p != null) return p.getProfile();
        for (TempRankEntry entry : get().getAllEntries()) if (entry.playerName.equalsIgnoreCase(name))
            return new com.mojang.authlib.GameProfile(entry.playerUuid, entry.playerName);
        if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Некорректный ник");
        if (!coint.cluster.DonationDelivery.enabled())
            throw new IllegalArgumentException("Игрок ещё не заходил на сервер");
        return new com.mojang.authlib.GameProfile(pendingId(name), name);
    }

    private static UUID pendingId(String name) {
        return UUID.nameUUIDFromBytes(
            ("cointcore-pending:" + name.toLowerCase(java.util.Locale.ROOT))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void bindIdentity(String name, UUID uuid) {
        requireAuthority();
        boolean changed = false;
        for (TempRankEntry entry : new ArrayList<>(entries))
            if (entry.pendingIdentity && entry.playerName.equalsIgnoreCase(name)) {
                TempRankEntry existing = entries.stream()
                    .filter(
                        e -> e != entry && !e.pendingIdentity
                            && e.playerUuid.equals(uuid)
                            && e.rankId.equals(entry.rankId))
                    .findFirst()
                    .orElse(null);
                if (existing != null) {
                    long now = System.currentTimeMillis();
                    long a = existing.pausedMillis != 0 ? existing.pausedMillis
                        : existing.expiresAt < 0 ? -1 : Math.max(0, existing.expiresAt - now);
                    long b = entry.pausedMillis != 0 ? entry.pausedMillis
                        : entry.expiresAt < 0 ? -1 : Math.max(0, entry.expiresAt - now);
                    long remaining = a < 0 || b < 0 ? -1 : Math.addExact(a, b);
                    if (existing.pausedMillis != 0) existing.pausedMillis = remaining;
                    else existing.expiresAt = remaining < 0 ? -1 : Math.addExact(now, remaining);
                    entries.remove(entry);
                } else {
                    entry.playerUuid = uuid;
                    entry.pendingIdentity = false;
                }
                changed = true;
            }
        if (changed) {
            reconcile();
            save();
        }
    }

    private void reconcile() {
        long now = System.currentTimeMillis();
        List<TempRankEntry> before = new ArrayList<>(entries);
        java.util.Set<UUID> players = new java.util.HashSet<>();
        for (TempRankEntry e : entries) players.add(e.playerUuid);
        for (UUID id : players) DonorQueue.settle(entries, id, now);
        entries.removeIf(e -> e.isExpired());
        // Persist queue transitions before changing SU or publishing them to other nodes.
        save();
        for (TempRankEntry e : before) {
            if (e.pendingIdentity) continue;
            if (!entries.contains(e) || e.pausedMillis != 0) RanksManager.get()
                .removeParentRank(e.playerUuid, e.rankId);
        }
        for (TempRankEntry e : entries) {
            if (e.pendingIdentity || e.pausedMillis != 0) continue;
            serverutils.ranks.PlayerRank rank = Ranks.INSTANCE
                .getPlayerRank(new com.mojang.authlib.GameProfile(e.playerUuid, e.playerName));
            boolean assigned = java.util.Arrays.stream(
                rank.getLocalPermission(Rank.NODE_PARENT)
                    .split(","))
                .anyMatch(
                    s -> s.trim()
                        .equals(e.rankId));
            if (!assigned) try {
                RanksManager.get()
                    .addParentRank(e.playerUuid, e.rankId);
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot apply temporary rank " + e.rankId, ex);
            }
        }
    }

    private TempRankManager() {}

    public static TempRankManager get() {
        if (INSTANCE == null) {
            INSTANCE = new TempRankManager();
        }
        return INSTANCE;
    }

    /**
     * Called at the beginning of each {@code serverStarted} before {@link #restoreAll()}.
     * Discards any stale in-memory state left over from a previous server session in the
     * same JVM (e.g. integrated server, /reload, test environments).
     */
    public static void reset() {
        INSTANCE = null;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Grant {@code rankId} to {@code playerUuid} for {@code durationMs} milliseconds.
     *
     * @param durationMs positive = timed; {@code -1} = permanent (tracked but never auto-removed)
     * @throws IllegalArgumentException if the rank does not exist in SU
     * @throws Exception                if the SU rank manipulation fails
     */
    public void grant(UUID playerUuid, String playerName, String rankId, long durationMs, String issuer)
        throws Exception {

        requireAuthority();
        validateRankExists(rankId);
        reconcile();

        if (durationMs != -1 && durationMs <= 0)
            throw new IllegalArgumentException("Duration must be positive or permanent");
        long expiresAt = durationMs < 0 ? -1L : Math.addExact(System.currentTimeMillis(), durationMs);
        TempRankEntry entry = new TempRankEntry(playerUuid, playerName, rankId, expiresAt, issuer);
        entry.pendingIdentity = playerUuid.equals(pendingId(playerName));

        // Prevent duplicates: remove any existing entry for the same player+rank.
        entries.removeIf(e -> e.playerUuid.equals(playerUuid) && e.rankId.equals(rankId));

        entries.add(entry);
        reconcile();
        save();

        LOG.info(
            "[TempRank] Granted '{}' to {} ({}) for {}, by {}",
            rankId,
            playerName,
            playerUuid,
            durationMs < 0 ? "permanent" : durationMs + " ms",
            issuer);
    }

    /**
     * Увеличивает срок действия существующей **активной** записи (не истёкшей, не «навсегда»):
     * новая дата окончания = текущая {@code expiresAt} + {@code durationMs}.
     * Ранг в ServerUtilities не трогается — он уже выдан.
     *
     * @param durationMs строго положительная длительность в миллисекундах
     * @throws IllegalArgumentException если записи нет, ранг бессрочный или длительность неверна
     */
    public void extend(UUID playerUuid, String playerName, String rankId, long durationMs, String issuer) {
        requireAuthority();
        reconcile();
        if (durationMs <= 0) {
            throw new IllegalArgumentException("Длительность продления должна быть положительной");
        }
        validateRankExists(rankId);

        TempRankEntry found = null;
        for (TempRankEntry e : entries) {
            if (e.playerUuid.equals(playerUuid) && e.rankId.equals(rankId) && !e.isExpired()) {
                found = e;
                break;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException(
                "У игрока нет активной временной записи для ранга «" + rankId + "» (или срок уже истёк)");
        }
        if (found.expiresAt < 0 || found.pausedMillis < 0) {
            throw new IllegalArgumentException("Бессрочную запись нельзя продлить по времени");
        }

        long newExpiresAt = found.pausedMillis == 0 ? Math.addExact(found.expiresAt, durationMs) : 0;
        String name = playerName != null && !playerName.isEmpty() ? playerName : found.playerName;
        entries.removeIf(e -> e.playerUuid.equals(playerUuid) && e.rankId.equals(rankId));
        TempRankEntry extended = new TempRankEntry(playerUuid, name, rankId, newExpiresAt, issuer);
        extended.pausedMillis = found.pausedMillis > 0 ? Math.addExact(found.pausedMillis, durationMs) : 0;
        extended.pendingIdentity = found.pendingIdentity;
        entries.add(extended);
        reconcile();
        save();

        LOG.info(
            "[TempRank] Extended '{}' for {} ({}): +{} ms, new expiry epoch {}, by {}",
            rankId,
            name,
            playerUuid,
            durationMs,
            newExpiresAt,
            issuer);
    }

    /**
     * Manually revoke {@code rankId} from {@code playerUuid}.
     * Silently does nothing if the entry is not tracked.
     */
    public void revoke(UUID playerUuid, String rankId) {
        requireAuthority();
        boolean removed = entries.removeIf(e -> e.playerUuid.equals(playerUuid) && e.rankId.equals(rankId));
        if (removed) {
            try {
                RanksManager.get()
                    .removeParentRank(playerUuid, rankId);
            } catch (Exception e) {
                LOG.warn(
                    "[TempRank] Could not remove rank '{}' from SU for {}: {}",
                    rankId,
                    playerUuid,
                    e.getMessage());
            }
            reconcile();
            save();
            LOG.info("[TempRank] Revoked '{}' from {}", rankId, playerUuid);
        }
    }

    /**
     * Called every minute by {@link TempRankTask}: removes all expired entries.
     */
    public void checkExpired() {
        if (!replica()) reconcile();
    }

    /**
     * Called on {@code serverStarted}: loads the JSON, evicts entries that
     * expired during downtime, keeps the rest in memory.
     * <p>
     * On the authority, reconciles SU parents with the active rank and pauses lower donor timers.
     */
    public void restoreAll() {
        entries.clear();
        load();
        if (replica()) return;
        reconcile();
        LOG.info("[TempRank] Restored {} active/queued temp-rank entries", entries.size());
    }

    /**
     * Активная запись с **конечным** сроком (не истекла, не «навсегда») — такую можно продлить через
     * {@link #extend(UUID, String, String, long, String)}.
     *
     * @return запись или {@code null}, если продлевать нечего (нет записи, истекла, или {@code expiresAt == -1})
     */
    public TempRankEntry findActiveTimedEntry(UUID playerUuid, String rankId) {
        for (TempRankEntry e : entries) {
            if (e.playerUuid.equals(playerUuid) && e.rankId.equals(rankId)
                && !e.isExpired()
                && (e.expiresAt > 0 || e.pausedMillis > 0)) {
                return e;
            }
        }
        return null;
    }

    public int clearPlayer(UUID playerUuid) {
        int before = entries.size();
        entries.removeIf(entry -> entry.playerUuid.equals(playerUuid));
        int removed = before - entries.size();
        if (removed > 0) {
            save();
        }
        return removed;
    }

    /** Returns all active entries for a specific player. */
    public List<TempRankEntry> getEntries(UUID playerUuid) {
        return getAllEntries().stream()
            .filter(e -> e.playerUuid.equals(playerUuid) && !e.isExpired())
            .collect(Collectors.toList());
    }

    /** Returns an unmodifiable view of all entries (for admin listing). */
    public List<TempRankEntry> getAllEntries() {
        return Collections.unmodifiableList(replica() ? replicaEntries : entries);
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private File getStorageFile() {
        File dir = new File("cointcore");
        // noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "temp_ranks.json");
    }

    private void save() {
        JsonArray array = new JsonArray();
        for (TempRankEntry e : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("playerUuid", e.playerUuid.toString());
            obj.addProperty("playerName", e.playerName);
            obj.addProperty("rankId", e.rankId);
            obj.addProperty("expiresAt", e.expiresAt);
            obj.addProperty("issuer", e.issuer);
            obj.addProperty("pausedMillis", e.pausedMillis);
            obj.addProperty("pendingIdentity", e.pendingIdentity);
            array.add(obj);
        }
        File file = getStorageFile();
        try {
            java.nio.file.Path target = file.toPath(),
                backup = target.resolveSibling("temp_ranks.before-donor-queue.json");
            if (file.isFile() && !java.nio.file.Files.exists(backup)) java.nio.file.Files.copy(target, backup);
            java.nio.file.Path temp = target.resolveSibling("temp_ranks.json.tmp");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(temp.toFile())) {
                output.write(
                    GSON.toJson(array)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.getFD()
                    .sync();
            }
            java.nio.file.Files.move(
                temp,
                target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot persist temporary rank purchase", ex);
        }
    }

    private void load() {
        File file = getStorageFile();
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonArray()) throw new IllegalArgumentException("Expected temporary rank array");
            for (JsonElement el : root.getAsJsonArray()) {
                JsonObject obj = el.getAsJsonObject();
                UUID uuid = UUID.fromString(
                    obj.get("playerUuid")
                        .getAsString());
                String name = obj.has("playerName") ? obj.get("playerName")
                    .getAsString() : uuid.toString();
                String rankId = obj.get("rankId")
                    .getAsString();
                long expiresAt = obj.get("expiresAt")
                    .getAsLong();
                String issuer = obj.has("issuer") ? obj.get("issuer")
                    .getAsString() : "unknown";
                TempRankEntry entry = new TempRankEntry(uuid, name, rankId, expiresAt, issuer);
                entry.pausedMillis = obj.has("pausedMillis") ? obj.get("pausedMillis")
                    .getAsLong() : 0;
                entry.pendingIdentity = obj.has("pendingIdentity") && obj.get("pendingIdentity")
                    .getAsBoolean();
                entries.add(entry);
            }
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Cannot read temporary ranks; refusing to overwrite existing purchases",
                ex);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void validateRankExists(String rankId) {
        Ranks ranks = Ranks.INSTANCE;
        if (ranks == null) throw new IllegalStateException("SU Ranks not initialized");
        Rank rank = ranks.getRank(rankId);
        if (rank == null) throw new IllegalArgumentException("Rank '" + rankId + "' does not exist in ServerUtilities");
    }
}
