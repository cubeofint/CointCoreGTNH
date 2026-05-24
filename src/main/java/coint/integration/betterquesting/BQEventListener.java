package coint.integration.betterquesting;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import betterquesting.api.events.QuestEvent;
import coint.CointConfig;
import coint.epochsync.EpochEntry;
import coint.epochsync.EpochRegistry;
import coint.integration.serverutilities.RanksManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

/**
 * Event listener for BetterQuesting quest completion events.
 * Handles automatic rank assignment for all party members.
 */
@EventBusSubscriber
public class BQEventListener {

    private static final Logger LOG = LogManager.getLogger(BQEventListener.class);

    @EventBusSubscriber.Condition
    public static boolean isEnabled() {
        return CointConfig.epochs.syncOnQuestComplete;
    }

    @SideOnly(Side.SERVER)
    @SubscribeEvent
    public static void onQuestComplete(QuestEvent event) {
        if (event.getType() != QuestEvent.Type.COMPLETED || event.getQuestIDs()
            .isEmpty()) {
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
    private static void processQuestCompletion(UUID playerId, UUID questID) {
        LOG.debug("Processing quest completion: {}", questID);

        EpochEntry epoch = EpochRegistry.INST.getEpoch(questID);

        if (epoch == null) {
            LOG.debug("Quest {} is not an epoch quest", questID);
            return;
        }

        LOG.info("Quest {} triggers rank: {}", questID, epoch.rankName);
        assignRankToPlayer(playerId, epoch);
    }

    /**
     * Assign rank to a single player and broadcast the epoch-up message.
     */
    private static void assignRankToPlayer(UUID playerId, EpochEntry epoch) {
        try {
            RanksManager.get()
                .setRank(playerId, epoch.rankName);

            String msg = epoch.epochUpMessage;
            if (msg != null && !msg.isEmpty()) {
                ForgePlayer forgePlayer = Universe.get()
                    .getPlayer(playerId);
                String playerName = forgePlayer != null ? forgePlayer.getName() : playerId.toString();
                msg = msg.replace("@p", EnumChatFormatting.GOLD + playerName + EnumChatFormatting.RESET);
                msg = msg.replace("@e", epoch.displayName);
                MinecraftServer.getServer()
                    .getConfigurationManager()
                    .sendChatMsg(new ChatComponentText(msg));
            }

            LOG.info("Successfully set rank {} for player {}", epoch.rankName, playerId);
        } catch (Exception e) {
            LOG.error("Error setting rank {} for player {}: {}", epoch.rankName, playerId, e.getMessage(), e);
        }
    }
}
