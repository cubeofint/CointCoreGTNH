package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;

import coint.migration.MigrationConsent;

/** Players can answer only for their own authenticated UUID in an existing request. */
public final class CommandMigrationConfirm extends CommandBase {

    @Override
    public String getCommandName() {
        return "migrate-confirm";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/migrate-confirm <id> <yes|no>";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender instanceof EntityPlayerMP;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP player) || args.length != 2
            || !("yes".equals(args[1]) || "no".equals(args[1]))) throw new CommandException(getCommandUsage(sender));
        try {
            MigrationConsent.answer(player, args[0], "yes".equals(args[1]));
        } catch (java.io.IOException | RuntimeException e) {
            throw new CommandException(e.getMessage());
        }
    }
}
