package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.util.Arrays;
import java.util.List;

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
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, MinecraftServer.getServer().getAllUsernames());
        }
        return super.addTabCompletionOptions(sender, args);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) throw new WrongUsageException(getCommandUsage(sender));

        EntityPlayerMP target = CommandBase.getPlayer(sender, args[0]);

        MessageTracker.toggleIgnore(sender.getCommandSenderName(), target.getCommandSenderName());
    }
}
