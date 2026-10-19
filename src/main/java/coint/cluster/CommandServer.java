package coint.cluster;

import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import coint.CointConfig;

/**
 * {@code /server} lists the servers of the network; {@code /server <id>} moves the player there, {@code /server home}
 * to his home server.
 */
public class CommandServer extends CommandBase {

    @Override
    public String getCommandName() {
        return "server";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/server [id|home]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!ClusterService.isRunning()) {
            sender.addChatMessage(new ChatComponentText("§cСеть серверов сейчас недоступна."));
            return;
        }
        if (args.length > 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        int timeout = CointConfig.cluster.nodeTimeoutSeconds;
        boolean started = ClusterService.query(db -> db.nodes(timeout), nodes -> {
            if (args.length == 0) {
                list(sender, nodes);
            } else {
                move(sender, nodes, args[0].toLowerCase(Locale.ROOT));
            }
        }, error -> sender.addChatMessage(new ChatComponentText("§cСеть серверов сейчас недоступна.")));
        if (!started) {
            sender.addChatMessage(new ChatComponentText("§cСеть серверов сейчас недоступна."));
        }
    }

    private static void list(ICommandSender sender, List<ClusterDatabase.NodeRow> nodes) {
        String home = sender instanceof EntityPlayerMP player ? ClusterHomes.homeOf(player.getUniqueID()) : null;
        sender.addChatMessage(new ChatComponentText("§6Серверы сети:"));
        for (ClusterDatabase.NodeRow node : nodes) {
            boolean here = node.id.equals(ClusterService.nodeId());
            String state = node.online
                ? "§a" + node.players + " онлайн, TPS " + String.format(Locale.ROOT, "%.1f", Math.min(20, node.tps))
                : "§8выключен";
            sender.addChatMessage(
                new ChatComponentText(
                    " §f" + node.id
                        + " §7— "
                        + node.name
                        + " "
                        + state
                        + (here ? " §b(вы здесь)" : "")
                        + (node.id.equals(home) ? " §e⌂ дом" : "")));
        }
        sender.addChatMessage(new ChatComponentText("§7Перейти: /server <id>, домой: /server home"));
    }

    private static void move(ICommandSender sender, List<ClusterDatabase.NodeRow> nodes, String id) {
        if (!(sender instanceof EntityPlayerMP player)) {
            sender.addChatMessage(new ChatComponentText("§cТолько для игроков."));
            return;
        }
        if (id.equals("home")) {
            // His home server, unless a server is really named so.
            boolean named = false;
            for (ClusterDatabase.NodeRow node : nodes) {
                named |= node.id.equalsIgnoreCase(id);
            }
            String home = named ? id : ClusterHomes.homeOf(player.getUniqueID());
            if (home == null) {
                sender.addChatMessage(new ChatComponentText("§cДомашний сервер не задан. Список: /server"));
                return;
            }
            id = home.toLowerCase(Locale.ROOT);
        }
        if (id.equalsIgnoreCase(ClusterService.nodeId())) {
            sender.addChatMessage(new ChatComponentText("§eВы уже на этом сервере."));
            return;
        }
        for (ClusterDatabase.NodeRow node : nodes) {
            if (!node.id.equalsIgnoreCase(id)) {
                continue;
            }
            if (!node.online) {
                sender.addChatMessage(new ChatComponentText("§cСервер " + node.name + " сейчас выключен."));
                return;
            }
            if (player.playerNetServerHandler == null) {
                return;
            }
            ClusterTransfers.send(player, node);
            return;
        }
        sender.addChatMessage(new ChatComponentText("§cНет сервера " + id + ". Список: /server"));
    }
}
