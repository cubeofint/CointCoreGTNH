package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;

import coint.core.ChatWSClient;

public class CommandHub extends CommandBase {

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return !(sender instanceof EntityPlayerMP);
    }

    @Override
    public String getCommandName() {
        return "hub";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/hub reconnect";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        switch (args[0]) {
            case "reconnect": {
                ChatWSClient.inst.reconnect();
                break;
            }
            case "":
            default: {
                throw new WrongUsageException(getCommandUsage(sender));
            }
        }
    }
}
