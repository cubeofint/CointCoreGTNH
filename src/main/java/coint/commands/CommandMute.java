package coint.commands;

import java.util.Arrays;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.google.common.base.Joiner;

import coint.commands.mute.Mute;
import coint.commands.mute.PlayerMuteData;
import coint.util.TimeUtil;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandMute extends CommandBase {

    public CommandMute() {
        PermissionAPI.registerNode("cointcore.command.mute", DefaultPermissionLevel.OP, "CointCore mute permission");
    }

    @Override
    public String getCommandName() {
        return "mute";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, "cointcore.command.mute");
        }
        return true; // console/RCON
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/mute <player> <time> 'reason'";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String playerName = args[0].toLowerCase();

        ForgePlayer player = Universe.get()
            .getPlayer(playerName);
        if (player == null) {
            throw new PlayerNotFoundException();
        }

        long durationMs = parseTimeToMs(args[1]);
        String reason = Joiner.on(" ")
            .join(Arrays.copyOfRange(args, 2, args.length));
        reason = reason.replaceAll("^['\"]|['\"]$", "");

        mute(sender, player, durationMs, reason);
    }

    private void mute(ICommandSender sender, ForgePlayer player, long durationMs, String reason) {
        Mute mute = new Mute(sender, reason, durationMs);

        if (player.isOnline()) {
            EntityPlayer entityPlayer = player.getPlayer();
            PlayerMuteData muteData = PlayerMuteData.get(entityPlayer);
            muteData.set(mute);

            entityPlayer.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.RED + "Вам выдан мут от "
                        + EnumChatFormatting.GOLD
                        + sender.getCommandSenderName()
                        + EnumChatFormatting.RED
                        + " на "
                        + TimeUtil.formatDuration(durationMs)
                        + EnumChatFormatting.RED
                        + " по причине: "
                        + EnumChatFormatting.YELLOW
                        + reason));
        }

        MinecraftServer server = MinecraftServer.getServer();
        ChatComponentText message = new ChatComponentText(
            EnumChatFormatting.GOLD + sender.getCommandSenderName()
                + EnumChatFormatting.RESET
                + " выдал мут "
                + EnumChatFormatting.GOLD
                + player.getName()
                + EnumChatFormatting.RESET
                + " на "
                + TimeUtil.formatDuration(durationMs));

        @SuppressWarnings("unchecked")
        java.util.List<EntityPlayer> players = (java.util.List<EntityPlayer>) (java.util.List<?>) server
            .getConfigurationManager().playerEntityList;
        for (EntityPlayer p : players) {
            p.addChatMessage(message);
        }
    }

    private long parseTimeToMs(String time) {
        String str = time.toLowerCase();

        if (str.endsWith("s")) {
            return Long.parseLong(str.substring(0, str.length() - 1)) * 1000;
        } else if (str.endsWith("m")) {
            return Long.parseLong(str.substring(0, str.length() - 1)) * 60 * 1000;
        } else if (str.endsWith("h")) {
            return Long.parseLong(str.substring(0, str.length() - 1)) * 60 * 60 * 1000;
        } else if (str.endsWith("d")) {
            return Long.parseLong(str.substring(0, str.length() - 1)) * 24 * 60 * 60 * 1000;
        }

        throw new WrongUsageException("Неверный формат времени. Используйте: 10s, 5m, 2h, 1d");
    }
}
