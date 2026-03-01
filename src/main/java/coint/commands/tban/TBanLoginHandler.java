package coint.commands.tban;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumChatFormatting;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class TBanLoginHandler {

    private static final Logger LOG = LogManager.getLogger(TBanLoginHandler.class);

    /**
     * Number of server ticks to wait after login before kicking a banned player.
     * This gives the client enough time to complete the connection handshake so
     * the disconnect screen shows the ban message instead of "Connection reset".
     */
    private static final int KICK_DELAY_TICKS = 40; // ~2 seconds

    // UUID -> ticks remaining until ban check; non-static, reset per server start
    private final Map<UUID, Integer> pendingChecks = new HashMap<>();

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        EntityPlayer player = event.player;
        if (player == null || player.worldObj.isRemote) {
            return;
        }
        pendingChecks.put(player.getUniqueID(), KICK_DELAY_TICKS);
        LOG.debug(
            "Player {} queued for ban check in {} ticks",
            player.getGameProfile()
                .getName(),
            KICK_DELAY_TICKS);
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pendingChecks.isEmpty()) {
            return;
        }

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        java.util.List<EntityPlayer> players = (java.util.List<EntityPlayer>) (java.util.List<?>) server
            .getConfigurationManager().playerEntityList;

        // Tick down all pending entries
        Iterator<Map.Entry<UUID, Integer>> it = pendingChecks.entrySet()
            .iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            if (remaining > 0) {
                entry.setValue(remaining);
                continue;
            }
            // Delay elapsed — perform the check
            it.remove();

            UUID uuid = entry.getKey();
            EntityPlayer player = findPlayer(players, uuid);
            if (player == null) {
                // Player disconnected before delay elapsed
                continue;
            }

            PlayerTBanData tbanData = PlayerTBanData.get(player);

            // If EEP has no active ban, check persistent storage (covers offline-ban case)
            if ((tbanData == null || !tbanData.isBanned()) && TBanStorage.isBanned(uuid)) {
                TBan storedTban = TBanStorage.get(uuid);
                if (storedTban != null && tbanData != null) {
                    tbanData.set(storedTban);
                    LOG.info(
                        "Loaded ban from storage for player {}",
                        player.getGameProfile()
                            .getName());
                }
                // Re-fetch after potential update
                tbanData = PlayerTBanData.get(player);
            }

            if (tbanData != null && tbanData.isBanned()) {
                TBan tban = tbanData.get();
                long remainingMs = tban.expiresAt - System.currentTimeMillis();
                if (remainingMs > 0) {
                    String banMessage = EnumChatFormatting.RED + "Ваш аккаунт будет разблокирован через "
                        + formatDuration(remainingMs)
                        + EnumChatFormatting.RED
                        + " по причине: "
                        + EnumChatFormatting.YELLOW
                        + tban.reason;
                    LOG.info(
                        "Player {} is banned, kicking",
                        player.getGameProfile()
                            .getName());
                    ((EntityPlayerMP) player).playerNetServerHandler.kickPlayerFromServer(banMessage);
                } else {
                    LOG.debug(
                        "Ban for player {} has expired, clearing",
                        player.getGameProfile()
                            .getName());
                    tbanData.clear();
                    TBanStorage.clearBan(uuid);
                }
            } else {
                LOG.debug(
                    "Player {} is not banned",
                    player.getGameProfile()
                        .getName());
            }
        }
    }

    private EntityPlayer findPlayer(java.util.List<EntityPlayer> players, UUID uuid) {
        for (EntityPlayer p : players) {
            if (uuid.equals(p.getUniqueID())) {
                return p;
            }
        }
        return null;
    }

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return days + "д " + (hours % 24) + "ч";
        } else if (hours > 0) {
            return hours + "ч " + (minutes % 60) + "м";
        } else if (minutes > 0) {
            return minutes + "м " + (seconds % 60) + "с";
        } else {
            return seconds + "с";
        }
    }
}
