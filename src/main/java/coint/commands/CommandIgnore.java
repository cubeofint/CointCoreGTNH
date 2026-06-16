package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;

public class CommandIgnore extends CommandBase {

    @Override
    public String getCommandName() {
        return "ignore";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/ignore <player>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) throw new WrongUsageException(getCommandUsage(sender));

        EntityPlayerMP target = CommandBase.getPlayer(sender, args[0]);

        MessageTracker.toggleIgnore(sender.getCommandSenderName(), target.getCommandSenderName());
    }
}
