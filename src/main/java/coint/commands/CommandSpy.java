package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import coint.commands.spy.LocalSpyRegistry;
import coint.commands.spy.PersonalSpyRegistry;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandSpy extends CommandBase {

    public static final String PERMISSION = "cointcore.dmspy";

    public CommandSpy() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "Toggle in-game DM spy mode");
    }

    @Override
    public String getCommandName() {
        return "spy";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true; // console / RCON
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/spy — переключить режим шпионажа за личной/локальной перепиской"
            + "| /spy [dm|local] - отдельное переключение";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        ChatComponentText msg;

        var p = Universe.get()
            .getPlayer(sender);
        if (args.length > 0) {
            msg = switch (args[0]) {
                case "dm" -> PersonalSpyRegistry.toggleWithMessage(p);
                case "local" -> LocalSpyRegistry.toggleWithMessage(p);
                default -> throw new WrongUsageException(getCommandUsage(sender));
            };
        } else {
            msg = PersonalSpyRegistry.toggleWithMessage(p);
            msg.appendSibling(LocalSpyRegistry.toggleWithMessage(p));
        }

        sender.addChatMessage(msg);
    }
}
