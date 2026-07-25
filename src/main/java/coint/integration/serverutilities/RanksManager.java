package coint.integration.serverutilities;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import betterquesting.api.questing.party.IParty;
import coint.epochsync.EpochEntry;
import coint.epochsync.EpochRegistry;
import coint.util.ChatUtil;
import serverutils.ServerUtilitiesPermissions;
import serverutils.ranks.PlayerRank;
import serverutils.ranks.Rank;
import serverutils.ranks.Ranks;

/**
 * Manager for ServerUtilities ranks integration.
 */
public class RanksManager {

    private static final Logger LOG = LogManager.getLogger(RanksManager.class);
    private final Map<String, Rank> epochRanks = new HashMap<>();

    private static RanksManager INSTANCE;

    public RanksManager() {
        reload();
    }

    /**
     * Get the singleton instance
     */
    public static RanksManager get() {
        if (INSTANCE == null) {
            INSTANCE = new RanksManager();
        }
        return INSTANCE;
    }

    /**
     * Initialize epoch permissions mapping.
     * Safe to call even when `Ranks.INSTANCE` is null — epoch Rank objects will be
     * (re)created during the next updateRanks() call once Ranks is ready.
     */
    public void reload() {
        epochRanks.clear();
        if (Ranks.INSTANCE == null) {
            LOG.warn(
                "[EpochSync] Ranks.INSTANCE is null during reload — epoch ranks will be registered on updateRanks()");
            return;
        }
        for (EpochEntry entry : EpochRegistry.INST.epochs.values()) {
            epochRanks.put(entry.rankName, createEpoch(entry));
        }
        LOG.debug("[EpochSync] Loaded {} epoch rank definitions", epochRanks.size());
    }

    private static Rank createEpoch(EpochEntry entry) {
        Rank epoch = new Rank(Ranks.INSTANCE, entry.rankName);

        String name = "<" + entry.displayName + " {name}>";
        epoch.setPermission(Rank.NODE_PRIORITY, entry.priority);
        epoch.setPermission(ServerUtilitiesPermissions.CHAT_NAME_FORMAT, name);
        epoch.setPermission(ServerUtilitiesPermissions.CLAIMS_MAX_CHUNKS, entry.chunks);
        epoch.setPermission(ServerUtilitiesPermissions.CHUNKLOADER_MAX_CHUNKS, entry.forcedChunks);
        epoch.setPermission(ServerUtilitiesPermissions.HOMES_MAX, entry.homes);

        return epoch;
    }

    public void updateRanks() {
        Ranks ranks = Ranks.INSTANCE;
        if (ranks == null) {
            LOG.error("[EpochSync] updateRanks() called but Ranks.INSTANCE is null — skipping");
            return;
        }
        // If reload() was called before Ranks was ready, epoch Rank objects were not built yet.
        // Rebuild them now.
        if (epochRanks.isEmpty() && !EpochRegistry.INST.epochs.isEmpty()) {
            LOG.info("[EpochSync] epochs map is empty, rebuilding from EpochRegistry before updateRanks");
            for (EpochEntry entry : EpochRegistry.INST.epochs.values()) {
                epochRanks.put(entry.rankName, createEpoch(entry));
            }
        }
        ranks.ranks.putAll(epochRanks);
        ranks.clearCache();
        ranks.save();
        LOG.info("[EpochSync] Registered {} epoch ranks into ServerUtilities", epochRanks.size());
    }

    /**
     * Add a rank as a parent of a player's rank without touching any existing parents.
     *
     * @throws Exception if SU Ranks is not initialized, rank not found, or player never joined
     */
    public void addParentRank(UUID playerId, String rankId) throws Exception {
        Ranks ranks = Ranks.INSTANCE;
        if (ranks == null) throw new Exception("ServerUtilities Ranks not initialized");

        Rank rankToAdd = ranks.getRank(rankId);
        if (rankToAdd == null) throw new Exception("Rank '" + rankId + "' not found in ServerUtilities");

        PlayerRank playerRank = ranks.playerRanks.get(playerId);
        if (playerRank == null) {
            var forgePlayer = ranks.universe.getPlayer(playerId);
            if (forgePlayer != null) {
                playerRank = ranks.getPlayerRank(forgePlayer.getProfile());
            } else {
                throw new Exception("Player " + playerId + " has never joined the server");
            }
        }

        playerRank.addParent(rankToAdd);
        ranks.save();
        ranks.clearCache();
        LOG.info("[TempRank] Added parent rank '{}' to player {}", rankId, playerId);
    }

    /**
     * Remove a specific parent rank from a player's rank without touching others.
     * Silently does nothing if the player has no such parent.
     */
    public void removeParentRank(UUID playerId, String rankId) {
        Ranks ranks = Ranks.INSTANCE;
        if (ranks == null) {
            LOG.warn("[TempRank] Ranks not initialized, cannot remove '{}' from {}", rankId, playerId);
            return;
        }

        Rank rankToRemove = ranks.getRank(rankId);
        if (rankToRemove == null) {
            LOG.warn("[TempRank] Rank '{}' not found in SU, nothing to remove", rankId);
            return;
        }

        PlayerRank playerRank = ranks.playerRanks.get(playerId);
        if (playerRank == null) {
            LOG.debug("[TempRank] Player {} not in playerRanks, nothing to remove", playerId);
            return;
        }

        if (playerRank.removeParent(rankToRemove)) {
            ranks.save();
            ranks.clearCache();
            LOG.info("[TempRank] Removed parent rank '{}' from player {}", rankId, playerId);
        }
    }

    /**
     * Set a player's epoch rank
     */
    public void setRank(UUID playerId, String rank) throws Exception {
        Ranks ranks = Ranks.INSTANCE;
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
            if (EpochRegistry.INST.getEpoch(parent.getId()) != null) {
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
        if (epochRank == null) {
            // Epoch ranks may not have been registered yet (e.g. updateRanks() was not called at startup).
            // Attempt a lazy registration now and retry.
            LOG.warn(
                "[EpochSync] Epoch rank '{}' not found in ServerUtilities ranks — attempting lazy updateRanks()",
                rank);
            updateRanks();
            epochRank = ranks.getRank(rank);
        }
        if (epochRank != null) {
            playerRank.addParent(epochRank);
            ranks.save();
            ranks.clearCache();

            var p = ranks.universe.getPlayer(playerId);
            if (p != null) ChatUtil.refreshRFN(p.getPlayer());
            LOG.info("Set rank {} for player {}", rank, playerId);
        } else {
            LOG.error(
                "[EpochSync] Epoch rank '{}' still not found after updateRanks() — check epochs.json and /coint_reload",
                rank);
        }

        // TODO: rank notify
    }

    /**
     * Get the current epoch rank of a player.
     *
     * @param playerId The player's UUID
     * @return The epoch rank name, or null if player has no epoch rank
     */
    public EpochEntry getPlayerEpoch(UUID playerId) {
        Ranks ranks = Ranks.INSTANCE;
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
        EpochEntry highestEpoch = null;
        int highestPriority = -1;

        for (Rank parent : playerRank.getActualParents()) {
            EpochEntry epoch = EpochRegistry.INST.getEpoch(parent.getId());
            if (epoch != null) {
                if (epoch.priority > highestPriority) {
                    highestPriority = epoch.priority;
                    highestEpoch = epoch;
                }
            }
        }

        return highestEpoch;
    }

    /**
     * Check if a player needs to be upgraded to the given epoch rank.
     * Returns true if the player has no epoch rank, or their current rank is lower than the target.
     *
     * @param playerId The player's UUID
     * @param epoch    The target epoch to compare against
     * @return true if player does NOT yet have this epoch or higher (i.e. upgrade is needed)
     */
    public boolean needsEpochUpgrade(UUID playerId, EpochEntry epoch) {
        EpochEntry playerEpoch = getPlayerEpoch(playerId);
        if (playerEpoch == null) {
            return true;
        }
        return playerEpoch.priority < epoch.priority;
    }

    /**
     * Get the highest epoch rank among all members of a party.
     * Iterates all members and returns the EpochEntry with the highest priority.
     *
     * @param party The BetterQuesting party to scan
     * @return The highest epoch rank found, or null if no member has any epoch
     */
    public EpochEntry getHighestPartyEpoch(IParty party) {
        if (party == null) {
            return null;
        }

        EpochEntry highestEpoch = null;

        for (UUID memberUUID : party.getMembers()) {
            EpochEntry memberEpoch = getPlayerEpoch(memberUUID);
            if (memberEpoch != null) {
                if (highestEpoch == null || memberEpoch.priority > highestEpoch.priority) {
                    highestEpoch = memberEpoch;
                }
            }
        }

        return highestEpoch;
    }
}
