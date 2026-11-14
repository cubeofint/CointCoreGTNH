package coint.commands;

import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;

import coint.cluster.ClusterComfort;
import serverutils.command.CmdGod;

public final class CommandGuestGod extends CmdGod {

    public boolean serverutilities$hasPermission(EntityPlayerMP player) {
        if (ClusterComfort.guestCommands(player)) return true;
        if (net.minecraft.server.MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152596_g(player.getGameProfile())) return true;
        cpw.mods.fml.common.eventhandler.Event.Result result = serverutils.ranks.Ranks.INSTANCE
            .getPermissionResult(player, "command.serverutilities.god", true);
        return result == cpw.mods.fml.common.eventhandler.Event.Result.DEFAULT
            ? super.canCommandSenderUseCommand(player)
            : result == cpw.mods.fml.common.eventhandler.Event.Result.ALLOW;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender instanceof EntityPlayerMP player ? serverutilities$hasPermission(player)
            : super.canCommandSenderUseCommand(sender);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (sender instanceof EntityPlayerMP player && ClusterComfort.guestCommands(player)) {
            if (args.length != 0) throw new WrongUsageException("В гостях используйте /god без имени игрока");
            ClusterComfort.toggle(player, "god");
        } else super.processCommand(sender, args);
    }
}
