package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.commands.mute.PlayerMuteData;
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
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String playerName = args[0].toLowerCase();

        ForgePlayer player = Universe.get()
            .getPlayer(playerName);
        if (player == null) {
            throw new PlayerNotFoundException();
        }

        if (player.isOnline()) {
            EntityPlayer entityPlayer = player.getPlayer();
            PlayerMuteData muteData = PlayerMuteData.get(entityPlayer);
            muteData.clear();

            entityPlayer
                .addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "Ваш мут был снят администратором"));
        }

        sender.addChatMessage(new ChatComponentText("Вытащили кляп у " + player.getName()));
    }
}
