package coint.commands;

import coint.config.CointConfig;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;

import coint.integration.serverutilities.RanksManager;
import coint.module.epochsync.EpochRegistry;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandReload extends CommandBase {

    public CommandReload() {
        PermissionAPI.registerNode("cointcore.command.reload", DefaultPermissionLevel.OP, "CointCore epochs reload permission");
    }

    @Override
    public String getCommandName() {
        return "coint_reload";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, "cointcore.command.reload");
        }
        return true;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/coint_reload epoch|config";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length < 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        switch (args[0]) {
            case "epoch": {
                EpochRegistry.INST.reload();
                RanksManager.get().reload();
                RanksManager.get().updateRanks();
                return;
            }
            case "config": {
                CointConfig.loadConfig();
                return;
            }
            default: {
                throw new WrongUsageException(getCommandUsage(sender));
            }
        }
    }
}
