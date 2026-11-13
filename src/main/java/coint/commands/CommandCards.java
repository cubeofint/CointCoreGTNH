package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;

import coint.cards.CardTables;

/** Lists local tables without exposing any player's hand. */
public final class CommandCards extends CommandBase {

    @Override
    public String getCommandName() {
        return "cards";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/cards [watch <table>]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        if (args.length == 2 && "watch".equals(args[0])) CardTables.watch(player, args[1]);
        else CardTables.list(player);
    }
}
