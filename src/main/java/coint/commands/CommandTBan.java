package coint.commands;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.google.common.base.Joiner;

import coint.player.TempBanEntry;
import coint.util.TimeUtil;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandTBan extends CommandBase {

    public CommandTBan() {
        PermissionAPI.registerNode("cointcore.command.tban", DefaultPermissionLevel.OP, "CointCore tban permission");
    }

    @Override
    public String getCommandName() {
        return "tban";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, "cointcore.command.tban");
        }
        return true; // console/RCON
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/tban <player> <time> 'reason'";
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(
                args,
                Universe.get()
                    .getPlayers()
                    .stream()
                    .map(ForgePlayer::getName)
                    .toArray(String[]::new));
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "remove", "1d", "7d", "30d", "perm");
        }
        return super.addTabCompletionOptions(sender, args);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        // target
        ForgePlayer player = Universe.get()
            .getPlayer(args[0]);
        if (player == null) {
            throw new PlayerNotFoundException();
        }

        // end date of ban
        long durationMs = TimeUtil.parseDuration(args[1]);
        var end = durationMs < 0 ? null : new Date(System.currentTimeMillis() + durationMs);

        // reason of ban
        String reason = Joiner.on(" ")
            .join(Arrays.copyOfRange(args, 2, args.length))
            .trim()
            .replaceAll("^['\"]|['\"]$", "");
        if (reason.isEmpty()) {
            throw new WrongUsageException("Укажите причину бана");
        }

        // ban execute
        TempBanEntry tbe = new TempBanEntry(player.profile, sender.getCommandSenderName(), end, reason);
        MinecraftServer ms = MinecraftServer.getServer();
        ms.getConfigurationManager()
            .func_152608_h()
            .func_152687_a(tbe);
        if (player.isOnline()) {
            player.getPlayer().playerNetServerHandler.kickPlayerFromServer(reason);
        }

        // ban msg
        ChatComponentText message = new ChatComponentText(
            EnumChatFormatting.GOLD + sender.getCommandSenderName()
                + EnumChatFormatting.RESET
                + " забанил "
                + EnumChatFormatting.GOLD
                + player.getName()
                + EnumChatFormatting.RESET
                + (durationMs < 0 ? " навсегда" : " на " + TimeUtil.formatDuration(durationMs))
                + ": "
                + EnumChatFormatting.YELLOW
                + reason);

        ms.getConfigurationManager()
            .sendChatMsg(message);
    }
}
