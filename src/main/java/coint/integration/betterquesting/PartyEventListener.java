package coint.integration.betterquesting;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import betterquesting.api.api.QuestingAPI;
import betterquesting.api.questing.party.IParty;
import betterquesting.api2.storage.DBEntry;
import betterquesting.questing.party.PartyManager;
import coint.config.CointConfig;
import coint.integration.serverutilities.SURanksManager;
import coint.module.epochsync.EpochRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Event listener for party-related events.
 * Handles rank synchronization when players join parties or log in.
 */
public class PartyEventListener {

    private static final Logger LOG = LogManager.getLogger(PartyEventListener.class);

    /**
     * Called when a player logs in.
     * Syncs the player's rank to their party's highest rank if applicable.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!CointConfig.syncNewPartyMembers || !CointConfig.partySyncEnabled) {
            return;
        }

        EntityPlayer player = event.player;
        if (player == null || player.worldObj.isRemote) {
            return;
        }

        UUID playerId = QuestingAPI.getQuestingUUID(player);
        LOG.debug("Player {} logged in, checking party sync", playerId);

        // Delay the sync slightly to ensure all systems are initialized
        scheduleDelayedSync(playerId);
    }

    /**
     * Schedule a delayed sync for a player.
     * This ensures ServerUtilities ranks are fully loaded.
     */
    private void scheduleDelayedSync(UUID playerId) {
        // Use a simple delayed task (1 second delay)
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                syncPlayerToParty(playerId);
            } catch (InterruptedException e) {
                LOG.debug("Sync thread interrupted for player {}", playerId);
            }
        }).start();
    }

    /**
     * Sync a player's rank to their party's highest rank.
     *
     * @param playerId The player's UUID
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
            LOG.info("Syncing player {} to party epoch: {} (was: {})", playerId, partyEpoch, playerEpoch);
            assignRankToPlayer(playerId, partyEpoch);
        } else {
            LOG.debug("Player {} already has equal or higher epoch: {}", playerId, playerEpoch);
        }
    }

    /**
     * Called when a player joins a party (via Mixin or manual trigger).
     *
     * @param playerId The player's UUID
     * @param party    The party they joined
     */
    public void onPlayerJoinParty(UUID playerId, IParty party) {
        if (!CointConfig.syncNewPartyMembers || !CointConfig.partySyncEnabled) {
            LOG.debug("Party sync disabled, skipping sync for player {}", playerId);
            return;
        }

        LOG.info("Player {} joined a party, syncing rank", playerId);
        syncPlayerToPartyInternal(playerId, party);
    }

    /**
     * Internal method to sync a player to a specific party.
     */
    private void syncPlayerToPartyInternal(UUID playerId, IParty party) {
        String partyEpoch = getHighestPartyEpoch(party);
        if (partyEpoch == null) {
            LOG.debug("Party has no epoch rank, nothing to sync for new member {}", playerId);
            return;
        }

        String playerEpoch = getPlayerCurrentEpoch(playerId);

        // Only upgrade, never downgrade
        if (playerEpoch == null
            || EpochRegistry.getEpochPriority(partyEpoch) > EpochRegistry.getEpochPriority(playerEpoch)) {
            LOG.info("Syncing new party member {} to epoch: {}", playerId, partyEpoch);
            assignRankToPlayer(playerId, partyEpoch);
        }
    }

    /**
     * Get the party for a player.
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
     */
    private String getHighestPartyEpoch(IParty party) {
        if (party == null) {
            return null;
        }

        String highestEpoch = null;
        int highestPriority = -1;

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
        if (ranksManager == null || !ranksManager.isReady()) {
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
     * Assign a rank to a player.
     */
    private void assignRankToPlayer(UUID playerId, String rank) {
        SURanksManager ranksManager = SURanksManager.getInstance();
        if (ranksManager == null) {
            LOG.warn("SURanksManager not initialized, cannot set rank");
            return;
        }

        try {
            ranksManager.setRank(playerId, rank);
            LOG.info("Successfully set rank {} for player {}", rank, playerId);
        } catch (Exception e) {
            LOG.error("Error setting rank {} for player {}: {}", rank, playerId, e.getMessage(), e);
        }
    }
}
