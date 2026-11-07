package coint.commands;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import com.mojang.authlib.GameProfile;

import coint.cluster.ClusterSanctions;
import coint.player.CointPlayer;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandUnmute extends CommandBase {

    public CommandUnmute() {
        PermissionAPI
            .registerNode("cointcore.command.unmute", DefaultPermissionLevel.OP, "CointCore unmute permission");
    }

    @Override
    public String getCommandName() {
        return "unmute";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, "cointcore.command.unmute");
        }
        return true; // console/RCON
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/unmute <player>";
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
        return super.addTabCompletionOptions(sender, args);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        CointPlayer player = null;
        try {
            player = CointPlayer.get(args[0].toLowerCase());
        } catch (PlayerNotFoundException e) {
            // Never played here: with shared mutes his mute on the other servers can still go.
            if (!ClusterSanctions.sharesMutes()) {
                throw e;
            }
        }
        String name;
        if (player != null) {
            player.unmute();
            name = player.getName();
            ClusterSanctions.publishUnmute(player.getId(), name);
        } else {
            GameProfile profile = ClusterSanctions.profileFor(args[0]);
            if (profile == null || profile.getId() == null) {
                throw new PlayerNotFoundException();
            }
            name = profile.getName();
            ClusterSanctions.publishUnmute(profile.getId(), name);
        }

        sender.addChatMessage(new ChatComponentText("Вытащили кляп у " + name));
    }
}
