package coint.commands.tban;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumChatFormatting;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class TBanLoginHandler {

    private static final Logger LOG = LogManager.getLogger(TBanLoginHandler.class);
    private static final Set<UUID> CHECKED_PLAYERS = new HashSet<>();

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        EntityPlayer player = event.player;
        if (player == null || player.worldObj.isRemote) {
            return;
        }

        // Mark player for checking in next tick
        CHECKED_PLAYERS.add(player.getUniqueID());
        LOG.debug(
            "Player {} added to ban check queue",
            player.getGameProfile()
                .getName());
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || CHECKED_PLAYERS.isEmpty()) {
            return;
        }

        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        java.util.List<EntityPlayer> players = (java.util.List<EntityPlayer>) (java.util.List<?>) server
            .getConfigurationManager().playerEntityList;

        for (EntityPlayer player : players) {
            UUID uuid = player.getUniqueID();
            if (CHECKED_PLAYERS.contains(uuid)) {
                CHECKED_PLAYERS.remove(uuid);

                PlayerTBanData tbanData = PlayerTBanData.get(player);

                if (tbanData != null && tbanData.isBanned()) {
                    TBan tban = tbanData.get();
                    long remainingMs = tban.expiresAt - System.currentTimeMillis();

                    if (remainingMs > 0) {
                        String banMessage = EnumChatFormatting.RED + "Вы забанены на "
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
                    }
                } else {
                    LOG.debug(
                        "Player {} is not banned",
                        player.getGameProfile()
                            .getName());
                }
            }
        }
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
