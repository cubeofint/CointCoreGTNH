package coint.cluster;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/** {@code /cluster home <player> <server>}: sets which server is a player's home (admins). */
public class CommandCluster extends CommandBase {

    private static final String PERMISSION = "cointcore.command.cluster";

    public CommandCluster() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "Manage the server network");
        ClusterHomes.registerPermissions();
    }

    @Override
    public String getCommandName() {
        return "cluster";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/cluster home <player> <server>";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 3 || !"home".equalsIgnoreCase(args[0])) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        String name = args[1];
        String wanted = args[2];
        int timeout = coint.CointConfig.cluster.nodeTimeoutSeconds;
        boolean started = ClusterService.query(db -> {
            // The id as the network knows it, whatever its case: a home on a server that is not there would trap him.
            for (ClusterDatabase.NodeRow row : db.nodes(timeout)) {
                if (row.id.equalsIgnoreCase(wanted)) {
                    String uuid = db.setHome(name, row.id);
                    return new String[] { uuid, row.id };
                }
            }
            return new String[0];
        }, found -> {
            if (found.length == 0) {
                sender.addChatMessage(new ChatComponentText("§cНет сервера " + wanted + ". Список: /server"));
                return;
            }
            String uuid = found[0], node = found[1];
            if (uuid == null) {
                sender.addChatMessage(new ChatComponentText("§cИгрок " + name + " ещё ни разу не заходил в сеть."));
                return;
            }
            ClusterHomes.homeChanged(uuid, node);
            sender.addChatMessage(new ChatComponentText("§aДомашний сервер " + name + ": " + node));
        }, error -> sender.addChatMessage(new ChatComponentText("§cОшибка базы: " + error)));
        if (!started) {
            sender.addChatMessage(new ChatComponentText("§cСеть серверов выключена (cluster.enabled=false)."));
        }
    }
}
