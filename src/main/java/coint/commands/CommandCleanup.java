package coint.commands;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;

import coint.tasks.CleanupTask;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandCleanup extends CommandBase {

    private final static String PERM_CLEANUP = "cointcore.command.cleanup";

    public CommandCleanup() {
        PermissionAPI.registerNode(PERM_CLEANUP, DefaultPermissionLevel.OP, "CointCore cleanup command");
    }

    @Override
    public String getCommandName() {
        return "cleanup";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/cleanup";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERM_CLEANUP);
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        CleanupTask task = CleanupTask.get();
        if (task != null) task.execute(Universe.get());
        else throw new WrongUsageException("Cleanup task is null");
    }
}
