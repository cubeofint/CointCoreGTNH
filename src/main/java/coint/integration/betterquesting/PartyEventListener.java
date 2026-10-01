package coint.integration.betterquesting;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
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
import cpw.mods.fml.common.gameevent.TickEvent;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

/**
 * Event listener for party-related events.
 * Handles rank synchronization when players join parties or log in.
 */
@EventBusSubscriber
public class PartyEventListener {

    private static final Logger LOG = LogManager.getLogger(PartyEventListener.class);
    private static final int LOGIN_SYNC_DELAY_TICKS = 40;
    private static final Map<UUID, Integer> pendingLoginSyncs = new HashMap<>();

    @EventBusSubscriber.Condition
    public static boolean isEnabled() {
        return CointConfig.epochs.enabled && CointConfig.epochs.syncNewPartyMembers && CointConfig.epochs.partySync;
    }

    /**
     * Called when a player logs in.
     * Syncs the player's rank to their party's highest rank if applicable.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        EntityPlayer player = event.player;
        if (player == null || player.worldObj.isRemote) {
            return;
        }

        UUID playerId = QuestingAPI.getQuestingUUID(player);
        pendingLoginSyncs.put(playerId, LOGIN_SYNC_DELAY_TICKS);
        LOG.debug("Player {} logged in, scheduled party rank reconciliation", playerId);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pendingLoginSyncs.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, Integer>> iterator = pendingLoginSyncs.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int ticksLeft = entry.getValue() - 1;
            if (ticksLeft > 0) {
                entry.setValue(ticksLeft);
                continue;
            }

            UUID playerId = entry.getKey();
            iterator.remove();

            ForgePlayer forgePlayer = Universe.get()
                .getPlayer(playerId);
            if (forgePlayer == null || !forgePlayer.isOnline()) {
                continue;
            }

            syncPlayerToParty(playerId);
        }
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

        EpochEntry currentEpoch = ranksManager.getPlayerEpoch(playerId);
        if (ranksManager.needsEpochUpgrade(playerId, partyEpoch)) {
            ForgePlayer forgePlayer = Universe.get()
                .getPlayer(playerId);
            String playerName = forgePlayer != null ? forgePlayer.getName() : playerId.toString();
            String currentRank = currentEpoch != null ? currentEpoch.rankName : "none";
            LOG.info(
                "[EpochSync] Login reconciliation for {} ({}): {} -> {}",
                playerName,
                playerId,
                currentRank,
                partyEpoch.rankName);
            assignRankToPlayer(playerId, partyEpoch.rankName);
        } else {
            LOG.debug("Player {} already has equal or higher epoch", playerId);
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
