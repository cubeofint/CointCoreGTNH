package coint.integration.betterquesting;

import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import betterquesting.api.events.QuestEvent;
import betterquesting.api.questing.party.IParty;
import betterquesting.api2.storage.DBEntry;
import betterquesting.questing.party.PartyManager;
import coint.config.CointConfig;
import coint.integration.serverutilities.SURanksManager;
import coint.module.epochsync.EpochRegistry;
import coint.module.epochsync.EpochEntry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Event listener for BetterQuesting quest completion events.
 * Handles automatic rank assignment for all party members.
 */
public class BQEventListener {

    private static final Logger LOG = LogManager.getLogger(BQEventListener.class);

    @SideOnly(Side.SERVER)
    @SubscribeEvent
    public void onQuestComplete(QuestEvent event) {
        if (event.getType() != QuestEvent.Type.COMPLETED || event.getQuestIDs()
            .isEmpty()) {
            return;
        }

        if (!CointConfig.autoSyncOnQuestComplete) {
            LOG.debug("Auto sync on quest complete is disabled");
            return;
        }

        UUID playerId = event.getPlayerID();
        LOG.info("Quest completion event for player {}, quest IDs: {}", playerId, event.getQuestIDs());

        for (UUID questID : event.getQuestIDs()) {
            processQuestCompletion(playerId, questID);
        }
    }

    /**
     * Process a single quest completion, checking for rank rewards.
     */
    private void processQuestCompletion(UUID playerId, UUID questID) {
        LOG.debug("Processing quest completion: {}", questID);

        EpochEntry epoch = EpochRegistry.INST.getEpoch(questID);

        if (epoch == null) {
            LOG.debug(
                "Quest {} is not an epoch quest (no mapping{} )",
                questID,
                CointConfig.autoParseRewardCommands ? " and no rank command found" : "");
            return;
        }

        LOG.info("Quest {} triggers rank: {}", questID, epoch.rankName);
        assignRankToPlayerAndParty(playerId, epoch);
    }

    /**
     * Assign rank to a player and all their party members.
     */
    private void assignRankToPlayerAndParty(UUID playerId, EpochEntry epoch) {
        SURanksManager ranksManager = SURanksManager.getInstance();
        if (ranksManager == null) {
            LOG.warn("SURanksManager not initialized, cannot set rank");
            return;
        }

        // Get player's party
        IParty party = getPlayerParty(playerId);

        if (party != null && CointConfig.partySyncEnabled) {
            // Assign rank to all party members
            List<UUID> members = party.getMembers();
            LOG.info("Assigning rank {} to {} party members", epoch, members.size());

            for (UUID memberUUID : members) {
                assignRankToPlayer(ranksManager, memberUUID, epoch);
            }
        } else {
            // No party or party sync disabled - assign only to the player
            assignRankToPlayer(ranksManager, playerId, epoch);
        }
    }

    /**
     * Assign rank to a single player.
     */
    private void assignRankToPlayer(SURanksManager ranksManager, UUID playerId, EpochEntry epoch) {
        try {
            ranksManager.setRank(playerId, epoch.rankName);
            LOG.info("Successfully set rank {} for player {}", epoch.rankName, playerId);
        } catch (Exception e) {
            LOG.error("Error setting rank {} for player {}: {}", epoch.rankName, playerId, e.getMessage(), e);
        }
    }

    /**
     * Get the party for a player.
     *
     * @return The party, or null if player is not in a party
     */
    private IParty getPlayerParty(UUID playerId) {
        try {
            DBEntry<IParty> entry = PartyManager.INSTANCE.getParty(playerId);
            return entry != null ? entry.getValue() : null;
        } catch (Exception e) {
            LOG.debug("Could not get party for player {}: {}", playerId, e.getMessage());
            return null;
        }
    }

    /**
     * Get the highest epoch rank from a party.
     * Useful for syncing new members to the party's progress.
     *
     * @param party The party to check
     * @return The highest epoch rank, or null if none found
     */
    public EpochEntry getHighestPartyEpoch(IParty party) {
        if (party == null) {
            return null;
        }

        EpochEntry highestEpoch = null;
        int highestPriority = -1;

        SURanksManager ranksManager = SURanksManager.getInstance();
        if (ranksManager == null) {
            return null;
        }

        for (UUID memberUUID : party.getMembers()) {
            EpochEntry memberEpoch = getPlayerCurrentEpoch(memberUUID);
            if (memberEpoch != null) {
                if (memberEpoch.priority > highestPriority) {
                    highestPriority = memberEpoch.priority;
                    highestEpoch = memberEpoch;
                }
            }
        }

        return highestEpoch;
    }

    /**
     * Get the current epoch rank of a player.
     */
    private EpochEntry getPlayerCurrentEpoch(UUID playerId) {
        SURanksManager ranksManager = SURanksManager.getInstance();
        if (ranksManager == null) {
            return null;
        }

        try {
            return ranksManager.getPlayerEpoch(playerId);
        } catch (Exception e) {
            LOG.debug("Could not get epoch for player {}: {}", playerId, e.getMessage());
            return null;
        }
    }

    /**
     * Sync a player to their party's highest epoch.
     * Called when a player joins a party.
     */
    public void syncPlayerToParty(UUID playerId) {
        IParty party = getPlayerParty(playerId);
        if (party == null) {
            LOG.debug("Player {} is not in a party, nothing to sync", playerId);
            return;
        }

        EpochEntry partyEpoch = getHighestPartyEpoch(party);
        if (partyEpoch == null) {
            LOG.debug("Party has no epoch rank, nothing to sync for player {}", playerId);
            return;
        }

        EpochEntry playerEpoch = getPlayerCurrentEpoch(playerId);

        // Only upgrade, never downgrade
        if (playerEpoch == null || partyEpoch.priority > playerEpoch.priority) {
            LOG.info("Syncing player {} to party epoch: {}", playerId, partyEpoch);
            assignRankToPlayer(SURanksManager.getInstance(), playerId, partyEpoch);
        } else {
            LOG.debug("Player {} already has equal or higher epoch: {}", playerId, playerEpoch);
        }
    }
}
