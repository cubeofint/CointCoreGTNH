package coint.integration.betterquesting;

import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import betterquesting.api.events.QuestEvent;
import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.party.IParty;
import betterquesting.api.questing.rewards.IReward;
import betterquesting.api2.storage.DBEntry;
import betterquesting.questing.QuestDatabase;
import betterquesting.questing.party.PartyManager;
import bq_standard.rewards.RewardCommand;
import coint.config.CointConfig;
import coint.integration.serverutilities.SURanksManager;
import coint.module.epochsync.EpochRegistry;
import coint.util.CommandParser;
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

        // First, try explicit mapping from EpochRegistry
        String epoch = EpochRegistry.getEpochForQuest(questID);

        // If no explicit mapping, optionally parse from quest rewards
        if (epoch == null && CointConfig.autoParseRewardCommands) {
            epoch = parseRankFromQuestRewards(questID);
        }

        if (epoch == null) {
            LOG.debug(
                "Quest {} is not an epoch quest (no mapping{} )",
                questID,
                CointConfig.autoParseRewardCommands ? " and no rank command found" : "");
            return;
        }

        LOG.info("Quest {} triggers rank: {}", questID, epoch);
        assignRankToPlayerAndParty(playerId, epoch);
    }

    /**
     * Parse rank from quest reward commands.
     * Looks for RewardCommand with /ranks add pattern.
     */
    private String parseRankFromQuestRewards(UUID questID) {
        try {
            IQuest quest = QuestDatabase.INSTANCE.get(questID);
            if (quest == null) {
                LOG.debug("Quest {} not found in database", questID);
                return null;
            }

            for (DBEntry<IReward> rewardEntry : quest.getRewards()
                .getEntries()) {
                IReward reward = rewardEntry.getValue();

                if (reward instanceof RewardCommand) {
                    RewardCommand cmdReward = (RewardCommand) reward;
                    String command = cmdReward.command;

                    if (CommandParser.isRanksAddCommand(command)) {
                        String rank = CommandParser.parseRankFromCommand(command);

                        // Validate that it's a known epoch rank
                        if (rank != null && EpochRegistry.isEpoch(rank)) {
                            LOG.debug("Found epoch rank '{}' in quest reward command: {}", rank, command);
                            return rank;
                        } else if (rank != null) {
                            LOG.debug("Rank '{}' from command is not a registered epoch, skipping", rank);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error parsing quest rewards for {}: {}", questID, e.getMessage(), e);
        }

        return null;
    }

    /**
     * Assign rank to a player and all their party members.
     */
    private void assignRankToPlayerAndParty(UUID playerId, String rank) {
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
            LOG.info("Assigning rank {} to {} party members", rank, members.size());

            for (UUID memberUUID : members) {
                assignRankToPlayer(ranksManager, memberUUID, rank);
            }
        } else {
            // No party or party sync disabled - assign only to the player
            assignRankToPlayer(ranksManager, playerId, rank);
        }
    }

    /**
     * Assign rank to a single player.
     */
    private void assignRankToPlayer(SURanksManager ranksManager, UUID playerId, String rank) {
        try {
            ranksManager.setRank(playerId, rank);
            LOG.info("Successfully set rank {} for player {}", rank, playerId);
        } catch (Exception e) {
            LOG.error("Error setting rank {} for player {}: {}", rank, playerId, e.getMessage(), e);
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
    public String getHighestPartyEpoch(IParty party) {
        if (party == null) {
            return null;
        }

        String highestEpoch = null;
        int highestPriority = -1;

        SURanksManager ranksManager = SURanksManager.getInstance();
        if (ranksManager == null) {
            return null;
        }

        for (UUID memberUUID : party.getMembers()) {
            String memberEpoch = getPlayerCurrentEpoch(memberUUID);
            if (memberEpoch != null) {
                int priority = EpochRegistry.getEpochPriority(memberEpoch);
                if (priority > highestPriority) {
                    highestPriority = priority;
                    highestEpoch = memberEpoch;
                }
            }
        }

        return highestEpoch;
    }

    /**
     * Get the current epoch rank of a player.
     */
    private String getPlayerCurrentEpoch(UUID playerId) {
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

        String partyEpoch = getHighestPartyEpoch(party);
        if (partyEpoch == null) {
            LOG.debug("Party has no epoch rank, nothing to sync for player {}", playerId);
            return;
        }

        String playerEpoch = getPlayerCurrentEpoch(playerId);

        // Only upgrade, never downgrade
        if (playerEpoch == null
            || EpochRegistry.getEpochPriority(partyEpoch) > EpochRegistry.getEpochPriority(playerEpoch)) {
            LOG.info("Syncing player {} to party epoch: {}", playerId, partyEpoch);
            assignRankToPlayer(SURanksManager.getInstance(), playerId, partyEpoch);
        } else {
            LOG.debug("Player {} already has equal or higher epoch: {}", playerId, playerEpoch);
        }
    }
}
