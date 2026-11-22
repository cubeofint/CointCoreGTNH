package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;

import coint.cluster.TeamHistory;

public final class CommandTeamHistory extends CommandBase {

    @Override
    public String getCommandName() {
        return "teamhistory";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/teamhistory";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 0 || !(sender instanceof EntityPlayerMP player))
            throw new WrongUsageException(getCommandUsage(sender));
        TeamHistory.request(player, 0, 0, true);
    }
}
