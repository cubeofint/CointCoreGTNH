package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import coint.network.WorldTravelNetwork;

public class CommandWorlds extends CommandBase {

    @Override
    public String getCommandName() {
        return "worlds";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/worlds";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP player)) {
            sender.addChatMessage(new ChatComponentText("This command can only be used by a player."));
            return;
        }
        WorldTravelNetwork.openFor(player);
    }
}
