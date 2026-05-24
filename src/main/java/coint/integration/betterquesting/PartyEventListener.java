package coint.integration.betterquesting;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import betterquesting.api.api.QuestingAPI;
import betterquesting.api.questing.party.IParty;
import betterquesting.api2.storage.DBEntry;
import betterquesting.questing.party.PartyManager;
import coint.CointConfig;
import coint.epochsync.EpochEntry;
import coint.integration.serverutilities.RanksManager;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Event listener for party-related events.
 * Handles rank synchronization when players join parties or log in.
 */
@EventBusSubscriber
public class PartyEventListener {

    private static final Logger LOG = LogManager.getLogger(PartyEventListener.class);

    @EventBusSubscriber.Condition
    public static boolean isEnabled() {
        // disabled due to offline rank assignment
        return false;
    }

    /**
     * Called when a player logs in.
     * Syncs the player's rank to their party's highest rank if applicable.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!CointConfig.epochs.syncNewPartyMembers || !CointConfig.epochs.partySync) {
            return;
        }

        EntityPlayer player = event.player;
        if (player == null || player.worldObj.isRemote) {
            return;
        }

        UUID playerId = QuestingAPI.getQuestingUUID(player);
        LOG.debug("Player {} logged in, checking party sync", playerId);

        syncPlayerToParty(playerId);
    }

    /**
     * Sync a player's rank to their party's highest rank.
     *
     * @param playerId The player's UUID
     */
    public static void syncPlayerToParty(UUID playerId) {
        IParty party = getPlayerParty(playerId);
        if (party == null) {
            LOG.debug("Player {} is not in a party, nothing to sync", playerId);
            return;
        }

        RanksManager ranksManager = RanksManager.get();
        if (ranksManager == null) {
            return;
        }

        EpochEntry partyEpoch = ranksManager.getHighestPartyEpoch(party);
        if (partyEpoch == null) {
            LOG.debug("Party has no epoch rank, nothing to sync for player {}", playerId);
            return;
        }

        // Only upgrade, never downgrade
        if (ranksManager.needsEpochUpgrade(playerId, partyEpoch)) {
            LOG.info("Syncing player {} to party epoch: {}", playerId, partyEpoch.rankName);
            assignRankToPlayer(playerId, partyEpoch.rankName);
        } else {
            LOG.debug("Player {} already has equal or higher epoch", playerId);
        }
    }

    /**
     * Internal method to sync a player to a specific party.
     */
    // TODO: move method
    public static void syncToPartyEpoch(UUID playerId, IParty party) {
        RanksManager ranksManager = RanksManager.get();
        if (ranksManager == null) {
            return;
        }

        EpochEntry partyEpoch = ranksManager.getHighestPartyEpoch(party);
        if (partyEpoch == null) {
            LOG.debug("Party has no epoch rank, nothing to sync for new member {}", playerId);
            return;
        }

        // Only upgrade, never downgrade
        if (ranksManager.needsEpochUpgrade(playerId, partyEpoch)) {
            LOG.info("Syncing new party member {} to epoch: {}", playerId, partyEpoch.rankName);
            assignRankToPlayer(playerId, partyEpoch.rankName);
        }
    }

    /**
     * Get the party for a player.
     */
    private static IParty getPlayerParty(UUID playerId) {
        try {
            DBEntry<IParty> entry = PartyManager.INSTANCE.getParty(playerId);
            return entry != null ? entry.getValue() : null;
        } catch (Exception e) {
            LOG.debug("Could not get party for player {}: {}", playerId, e.getMessage());
            return null;
        }
    }

    /**
     * Assign a rank to a player.
     */
    private static void assignRankToPlayer(UUID playerId, String rank) {
        RanksManager ranksManager = RanksManager.get();
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
