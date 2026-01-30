package coint.integration.serverutilities;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import coint.config.CointConfig;
import coint.module.epochsync.EpochRegistry;
import coint.util.HttpUtil;
import serverutils.ServerUtilitiesPermissions;
import serverutils.ranks.PlayerRank;
import serverutils.ranks.Rank;
import serverutils.ranks.Rank.Entry;
import serverutils.ranks.Ranks;

/**
 * Manager for ServerUtilities ranks integration.
 */
public class SURanksManager {

    private static final Logger LOG = LogManager.getLogger(SURanksManager.class);

    private static SURanksManager INSTANCE;

    private Ranks ranksInst;
    private final Map<String, List<Entry>> epochPermissions = new HashMap<>();

    private SURanksManager() {
        initEpochPermissions();
    }

    /**
     * Initialize the singleton instance
     */
    public static void init() {
        if (INSTANCE == null) {
            INSTANCE = new SURanksManager();
            LOG.info("SURanksManager initialized");
        }
    }

    /**
     * Get the singleton instance
     */
    public static SURanksManager getInstance() {
        return INSTANCE;
    }

    /**
     * Get Ranks instance (lazy initialization)
     * Ranks.INSTANCE is only available after server starts
     */
    private Ranks getRanksInstance() {
        if (ranksInst == null) {
            ranksInst = Ranks.INSTANCE;
            if (ranksInst == null) {
                LOG.error("Ranks.INSTANCE is null! ServerUtilities ranks not initialized yet.");
            } else {
                LOG.debug("Ranks instance obtained successfully");
            }
        }
        return ranksInst;
    }

    /**
     * Initialize epoch permissions mapping
     */
    private void initEpochPermissions() {
        epochPermissions.put(EpochRegistry.RANK_START, createEpochPerms(1, 25, 10, 1));
        epochPermissions.put(EpochRegistry.RANK_STONE, createEpochPerms(2, 35, 15, 2));
        epochPermissions.put(EpochRegistry.RANK_STEAM, createEpochPerms(3, 45, 20, 3));
        epochPermissions.put(EpochRegistry.RANK_LV, createEpochPerms(4, 55, 25, 4));
        epochPermissions.put(EpochRegistry.RANK_MV, createEpochPerms(5, 65, 30, 5));
        epochPermissions.put(EpochRegistry.RANK_HV, createEpochPerms(6, 75, 35, 6));
        epochPermissions.put(EpochRegistry.RANK_EV, createEpochPerms(7, 85, 40, 7));
        epochPermissions.put(EpochRegistry.RANK_IV, createEpochPerms(8, 95, 45, 8));
        epochPermissions.put(EpochRegistry.RANK_LUV, createEpochPerms(9, 105, 50, 9));
        epochPermissions.put(EpochRegistry.RANK_ZPM, createEpochPerms(10, 115, 55, 10));
        epochPermissions.put(EpochRegistry.RANK_UV, createEpochPerms(11, 125, 60, 11));
        epochPermissions.put(EpochRegistry.RANK_UHV, createEpochPerms(12, 135, 65, 12));
        epochPermissions.put(EpochRegistry.RANK_UEV, createEpochPerms(13, 145, 70, 13));
        epochPermissions.put(EpochRegistry.RANK_UIV, createEpochPerms(14, 155, 75, 14));
        epochPermissions.put(EpochRegistry.RANK_UMV, createEpochPerms(15, 165, 80, 15));
        epochPermissions.put(EpochRegistry.RANK_UXV, createEpochPerms(16, 175, 85, 16));
        epochPermissions.put(EpochRegistry.RANK_STARGATEOWNER, createEpochPerms(17, 200, 100, 20));
    }

    private static List<Entry> createEpochPerms(int priority, int chunks, int forcedChunks, int homes) {
        Entry p = new Entry(Rank.NODE_PRIORITY);
        p.value = String.valueOf(priority);
        Entry c = new Entry(ServerUtilitiesPermissions.CLAIMS_MAX_CHUNKS);
        c.value = String.valueOf(chunks);
        Entry f = new Entry(ServerUtilitiesPermissions.CHUNKLOADER_MAX_CHUNKS);
        f.value = String.valueOf(forcedChunks);
        Entry h = new Entry(ServerUtilitiesPermissions.HOMES_MAX);
        h.value = String.valueOf(homes);

        return Arrays.asList(p, c, f, h);
    }

    /**
     * Get all ranks
     */
    public Map<String, Rank> getRanks() {
        Ranks ranks = getRanksInstance();
        return ranks != null ? ranks.ranks : null;
    }

    /**
     * Set a player's epoch rank
     */
    public void setRank(UUID playerId, String rank) throws Exception {
        Ranks ranks = getRanksInstance();
        if (ranks == null) {
            throw new Exception("ServerUtilities Ranks not initialized yet");
        }

        // Get player rank directly from playerRanks map
        PlayerRank playerRank = ranks.playerRanks.get(playerId);
        if (playerRank == null) {
            LOG.warn("Player {} not found in playerRanks, creating new entry", playerId);
            // Try to get or create via GameProfile if player exists in universe
            var forgePlayer = ranks.universe.getPlayer(playerId);
            if (forgePlayer != null) {
                playerRank = ranks.getPlayerRank(forgePlayer.getProfile());
            } else {
                throw new Exception("Player rank not found for " + playerId + " and player not in universe");
            }
        }

        // Collect epoch ranks to remove first (avoid ConcurrentModificationException)
        java.util.List<Rank> epochsToRemove = new java.util.ArrayList<>();
        for (Rank parent : playerRank.getParents()) {
            if (EpochRegistry.isEpoch(parent.getId())) {
                epochsToRemove.add(parent);
            }
        }

        // Remove collected epoch ranks
        for (Rank epochToRemove : epochsToRemove) {
            playerRank.removeParent(epochToRemove);
            LOG.debug("Removed epoch rank {} from player {}", epochToRemove.getId(), playerId);
        }

        // Add new epoch rank
        Rank epochRank = ranks.getRank(rank);
        if (epochRank != null) {
            playerRank.addParent(epochRank);
            ranks.save();
            LOG.info("Set rank {} for player {}", rank, playerId);
        } else {
            LOG.warn("Epoch rank {} not found in ServerUtilities", rank);
        }

        // Notify external API
        notifyApiRankChange(playerId, rank);
    }

    /**
     * Notify external API about rank change
     */
    private void notifyApiRankChange(UUID playerId, String rank) {
        String apiUrl = CointConfig.getEffectiveApiUrl();
        if (apiUrl == null || apiUrl.isEmpty()) {
            LOG.debug("API URL not configured, skipping notification");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("player_id", playerId.toString());
        data.addProperty("rank", rank);

        HttpUtil.postJsonAsync(apiUrl + "/api/coint-connector/roles/gtnh", data.toString())
            .thenAccept(code -> LOG.debug("API notification sent, response: {}", code))
            .exceptionally(e -> {
                LOG.error("API notification failed: {}", e.getMessage());
                return null;
            });
    }

    /**
     * Sync all ranks with external API
     */
    public void syncRanks(boolean onlyRoles) {
        Ranks ranks = getRanksInstance();
        if (ranks == null) {
            LOG.error("Cannot sync ranks: ServerUtilities Ranks not initialized");
            return;
        }

        String apiUrl = CointConfig.getEffectiveApiUrl();
        if (apiUrl == null || apiUrl.isEmpty()) {
            LOG.warn("API URL not configured, cannot sync ranks");
            return;
        }

        JsonObject data = new JsonObject();

        // Add ranks
        JsonArray ranksArray = new JsonArray();
        for (Rank r : ranks.ranks.values()) {
            JsonObject rankObj = new JsonObject();
            rankObj.addProperty("name", r.getId());
            rankObj.addProperty("power", r.getPriority());
            ranksArray.add(rankObj);
        }
        data.add("ranks", ranksArray);

        // Add players if requested
        if (!onlyRoles) {
            JsonArray playersArray = new JsonArray();
            for (PlayerRank p : ranks.playerRanks.values()) {
                JsonObject playerObj = new JsonObject();
                playerObj.addProperty("player_id", p.uuid.toString());

                boolean hasEpoch = false;
                for (Rank par : p.getActualParents()) {
                    if (EpochRegistry.isEpoch(par.getId())) {
                        playerObj.addProperty("rank", par.getId());
                        hasEpoch = true;
                        break;
                    }
                }

                if (hasEpoch) {
                    playersArray.add(playerObj);
                }
            }
            data.add("players", playersArray);
        }

        HttpUtil.postJsonAsync(apiUrl + "/api/coint-connector/roles/sync", data.toString())
            .thenAccept(code -> LOG.info("Rank sync completed, response: {}", code))
            .exceptionally(e -> {
                LOG.error("Rank sync failed: {}", e.getMessage());
                return null;
            });
    }

    /**
     * Get epoch permissions for a rank
     */
    public List<Entry> getEpochPermissions(String epoch) {
        return epochPermissions.get(epoch);
    }

    /**
     * Check if ranks system is ready (Ranks.INSTANCE initialized)
     */
    public boolean isReady() {
        return getRanksInstance() != null;
    }

    /**
     * Get the universe for player lookups (null if not ready)
     */
    public serverutils.lib.data.Universe getUniverse() {
        Ranks ranks = getRanksInstance();
        return ranks != null ? ranks.universe : null;
    }

    /**
     * Get the current epoch rank of a player.
     *
     * @param playerId The player's UUID
     * @return The epoch rank name, or null if player has no epoch rank
     */
    public String getPlayerEpoch(UUID playerId) {
        Ranks ranks = getRanksInstance();
        if (ranks == null) {
            LOG.debug("Cannot get player epoch: Ranks not initialized");
            return null;
        }

        PlayerRank playerRank = ranks.playerRanks.get(playerId);
        if (playerRank == null) {
            LOG.debug("Player {} not found in playerRanks", playerId);
            return null;
        }

        // Find the highest priority epoch rank among player's parents
        String highestEpoch = null;
        int highestPriority = -1;

        for (Rank parent : playerRank.getActualParents()) {
            String rankId = parent.getId();
            if (EpochRegistry.isEpoch(rankId)) {
                int priority = EpochRegistry.getEpochPriority(rankId);
                if (priority > highestPriority) {
                    highestPriority = priority;
                    highestEpoch = rankId;
                }
            }
        }

        return highestEpoch;
    }

    /**
     * Check if a player has a specific epoch rank or higher.
     *
     * @param playerId The player's UUID
     * @param epoch    The epoch to check
     * @return true if player has this epoch or a higher one
     */
    public boolean hasEpochOrHigher(UUID playerId, String epoch) {
        String playerEpoch = getPlayerEpoch(playerId);
        if (playerEpoch == null) {
            return false;
        }
        return EpochRegistry.getEpochPriority(playerEpoch) >= EpochRegistry.getEpochPriority(epoch);
    }
}
