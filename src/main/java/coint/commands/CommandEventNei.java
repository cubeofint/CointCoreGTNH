package coint.commands;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;

import coint.events.EventNei;
import serverutils.lib.util.permission.PermissionAPI;

public final class CommandEventNei extends CommandBase {

    public static final String PERMISSION = "cointcore.command.eventnei";

    @Override
    public String getCommandName() {
        return "eventnei";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "commands.cointcore.eventnei.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return EventNei.available()
            && (sender instanceof EntityPlayer player ? PermissionAPI.hasPermission(player, PERMISSION)
                : super.canCommandSenderUseCommand(sender));
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "hide", "show") : null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!EventNei.available()) throw new CommandException("commands.cointcore.eventnei.hubOnly");
        if (!canCommandSenderUseCommand(sender)) throw new CommandException("commands.generic.permission");
        if (args.length == 2 && "hide".equalsIgnoreCase(args[0])) {
            int minutes = parseIntWithMin(sender, args[1], 1);
            int unsupported = EventNei.hide(minutes);
            sender.addChatMessage(new ChatComponentTranslation("commands.cointcore.eventnei.hidden", minutes));
            if (unsupported > 0) {
                sender.addChatMessage(new ChatComponentTranslation("commands.cointcore.eventnei.update", unsupported));
            }
        } else if (args.length == 1 && "show".equalsIgnoreCase(args[0])) {
            EventNei.show();
            sender.addChatMessage(new ChatComponentTranslation("commands.cointcore.eventnei.shown"));
        } else {
            throw new WrongUsageException(getCommandUsage(sender));
        }
    }
}
