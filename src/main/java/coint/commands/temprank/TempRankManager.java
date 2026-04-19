package coint.commands.temprank;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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

        validateRankExists(rankId);

        long expiresAt = durationMs < 0 ? -1L : System.currentTimeMillis() + durationMs;
        TempRankEntry entry = new TempRankEntry(playerUuid, playerName, rankId, expiresAt, issuer);

        // Prevent duplicates: remove any existing entry for the same player+rank.
        entries.removeIf(e -> e.playerUuid.equals(playerUuid) && e.rankId.equals(rankId));

        RanksManager.get()
            .addParentRank(playerUuid, rankId);
        entries.add(entry);
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
        if (found.expiresAt < 0) {
            throw new IllegalArgumentException("Бессрочную запись нельзя продлить по времени");
        }

        long newExpiresAt = found.expiresAt + durationMs;
        String name = playerName != null && !playerName.isEmpty() ? playerName : found.playerName;
        entries.removeIf(e -> e.playerUuid.equals(playerUuid) && e.rankId.equals(rankId));
        entries.add(new TempRankEntry(playerUuid, name, rankId, newExpiresAt, issuer));
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
            save();
            LOG.info("[TempRank] Revoked '{}' from {}", rankId, playerUuid);
        }
    }

    /**
     * Called every minute by {@link TempRankTask}: removes all expired entries.
     */
    public void checkExpired() {
        boolean changed = false;
        Iterator<TempRankEntry> it = entries.iterator();
        while (it.hasNext()) {
            TempRankEntry entry = it.next();
            if (entry.isExpired()) {
                it.remove();
                changed = true;
                try {
                    RanksManager.get()
                        .removeParentRank(entry.playerUuid, entry.rankId);
                    LOG.info("[TempRank] Expired '{}' for {} ({})", entry.rankId, entry.playerName, entry.playerUuid);
                } catch (Exception e) {
                    LOG.warn(
                        "[TempRank] Failed to remove expired rank '{}' from SU for {}: {}",
                        entry.rankId,
                        entry.playerUuid,
                        e.getMessage());
                }
            }
        }
        if (changed) save();
    }

    /**
     * Called on {@code serverStarted}: loads the JSON, evicts entries that
     * expired during downtime, keeps the rest in memory.
     * <p>
     * Does NOT re-add ranks to SU: they are already in {@code players.txt}.
     */
    public void restoreAll() {
        entries.clear();
        load();

        boolean changed = false;
        Iterator<TempRankEntry> it = entries.iterator();
        while (it.hasNext()) {
            TempRankEntry entry = it.next();
            if (entry.isExpired()) {
                it.remove();
                changed = true;
                // Rank may still be in players.txt — remove it.
                try {
                    RanksManager.get()
                        .removeParentRank(entry.playerUuid, entry.rankId);
                    LOG.info("[TempRank] Removed expired (offline) '{}' for {}", entry.rankId, entry.playerName);
                } catch (Exception e) {
                    LOG.warn(
                        "[TempRank] Could not remove offline-expired rank '{}' for {}: {}",
                        entry.rankId,
                        entry.playerUuid,
                        e.getMessage());
                }
            }
        }
        if (changed) save();
        LOG.info("[TempRank] Restored {} active temp-rank entries", entries.size());
    }

    /**
     * Активная запись с **конечным** сроком (не истекла, не «навсегда») — такую можно продлить через
     * {@link #extend(UUID, String, String, long, String)}.
     *
     * @return запись или {@code null}, если продлевать нечего (нет записи, истекла, или {@code expiresAt == -1})
     */
    public TempRankEntry findActiveTimedEntry(UUID playerUuid, String rankId) {
        for (TempRankEntry e : entries) {
            if (e.playerUuid.equals(playerUuid) && e.rankId.equals(rankId) && !e.isExpired() && e.expiresAt > 0) {
                return e;
            }
        }
        return null;
    }

    /** Returns all active entries for a specific player. */
    public List<TempRankEntry> getEntries(UUID playerUuid) {
        return entries.stream()
            .filter(e -> e.playerUuid.equals(playerUuid) && !e.isExpired())
            .collect(Collectors.toList());
    }

    /** Returns an unmodifiable view of all entries (for admin listing). */
    public List<TempRankEntry> getAllEntries() {
        return Collections.unmodifiableList(entries);
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
            array.add(obj);
        }
        File file = getStorageFile();
        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(array, writer);
        } catch (IOException ex) {
            LOG.error("[TempRank] Failed to save {}: {}", file, ex.getMessage());
        }
    }

    private void load() {
        File file = getStorageFile();
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonArray()) return;
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
                entries.add(new TempRankEntry(uuid, name, rankId, expiresAt, issuer));
            }
        } catch (Exception ex) {
            LOG.error("[TempRank] Failed to load {}: {}", file, ex.getMessage());
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
