package coint.integration.betterquesting;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import betterquesting.api.api.QuestingAPI;
import betterquesting.api.questing.party.IParty;
import betterquesting.api2.storage.DBEntry;
import betterquesting.questing.party.PartyManager;
import coint.config.CointConfig;
import coint.integration.serverutilities.SURanksManager;
import coint.module.epochsync.EpochEntry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

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
     * Schedule a sync for a player on the server thread.
     * Registers a one-shot TickEvent handler that runs on the next server tick
     * and immediately unregisters itself — safe pattern for Forge 1.7.10.
     */
    private void scheduleDelayedSync(UUID playerId) {
        MinecraftForge.EVENT_BUS.register(new Object() {

            @SubscribeEvent
            public void onServerTick(TickEvent.ServerTickEvent event) {
                if (event.phase != TickEvent.Phase.END) {
                    return;
                }
                MinecraftForge.EVENT_BUS.unregister(this);
                syncPlayerToParty(playerId);
            }
        });
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

        SURanksManager ranksManager = SURanksManager.get();
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
        SURanksManager ranksManager = SURanksManager.get();
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
     * Assign a rank to a player.
     */
    private void assignRankToPlayer(UUID playerId, String rank) {
        SURanksManager ranksManager = SURanksManager.get();
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
