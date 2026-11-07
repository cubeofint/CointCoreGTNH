package coint.commands;

import java.util.Arrays;
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
import com.mojang.authlib.GameProfile;

import coint.cluster.ClusterSanctions;
import coint.player.CointPlayer;
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
            return getListOfStringsMatchingLastWord(args, "1h", "12h", "1d", "7d", "30d");
        }
        return super.addTabCompletionOptions(sender, args);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        long durationMs = TimeUtil.simpleParse(args[1]);
        String reason = Joiner.on(" ")
            .join(Arrays.copyOfRange(args, 2, args.length));
        reason = reason.replaceAll("^['\"]|['\"]$", "");

        String name;
        CointPlayer player = null;
        try {
            player = CointPlayer.get(args[0].toLowerCase());
        } catch (PlayerNotFoundException e) {
            // Never played here: with shared mutes he can still be muted for the servers he plays on.
            if (!ClusterSanctions.sharesMutes()) {
                throw e;
            }
        }
        if (player != null) {
            player.mute(sender, reason, durationMs);
            name = player.getName();
            ClusterSanctions
                .publishMute(player.getId(), name, sender.getCommandSenderName(), reason, player.getMute().expiresAt);
        } else {
            GameProfile profile = ClusterSanctions.profileFor(args[0]);
            if (profile == null || profile.getId() == null) {
                throw new PlayerNotFoundException();
            }
            name = profile.getName();
            ClusterSanctions.publishMute(
                profile.getId(),
                name,
                sender.getCommandSenderName(),
                reason,
                System.currentTimeMillis() + durationMs);
        }

        ChatComponentText message = new ChatComponentText(
            EnumChatFormatting.GOLD + sender.getCommandSenderName()
                + EnumChatFormatting.RESET
                + " выдал мут "
                + EnumChatFormatting.GOLD
                + name
                + EnumChatFormatting.RESET
                + " на "
                + TimeUtil.formatDuration(durationMs)
                + " по причине: "
                + reason);

        MinecraftServer.getServer()
            .getConfigurationManager()
            .sendChatMsg(message);
    }
}
