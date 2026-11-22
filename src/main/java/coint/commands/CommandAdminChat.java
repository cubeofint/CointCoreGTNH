package coint.commands;

import java.util.Arrays;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;

import coint.chat.AdminChat;

public final class CommandAdminChat extends CommandBase {

    @Override
    public String getCommandName() {
        return "adminchat";
    }

    @Override
    public List<String> getCommandAliases() {
        return Arrays.asList("a", "ac");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/adminchat <message>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return AdminChat.permitted(sender);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) throw new WrongUsageException(getCommandUsage(sender));
        AdminChat.send(sender, String.join(" ", args));
    }
}
