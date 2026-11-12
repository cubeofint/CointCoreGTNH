package coint.commands;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import coint.cluster.MigrationCoordinator;
import coint.migration.MigrationExporter;
import coint.migration.MigrationImporter;
import coint.player.PlayerLookup;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * /migrate export &lt;player&gt; — packs the player's team (personal worlds and player files) into one zip under
 * {@code migrations/} for moving the base to another server.
 */
public class CommandMigrate extends CommandBase {

    private static final String PERMISSION = "cointcore.command.migrate";

    public CommandMigrate() {
        PermissionAPI
            .registerNode(PERMISSION, DefaultPermissionLevel.OP, "Export a team's base for moving to another server");
    }

    @Override
    public String getCommandName() {
        return "migrate";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/migrate [ui] | export <player> [target] | import <file.zip> | queue <file.zip> <target>"
            + " | nodes | requests | accept <id> [confirm]";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return allowed(sender);
    }

    public static boolean allowed(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (!allowed(sender)) throw new CommandException("Нет права " + PERMISSION);
        if (args.length == 0 || (args.length == 1 && "ui".equalsIgnoreCase(args[0]))) {
            if (sender instanceof EntityPlayerMP player) MigrationCoordinator.open(player);
            else MigrationCoordinator.tell(sender, getCommandUsage(sender));
            return;
        }
        if (args.length == 1 && "nodes".equalsIgnoreCase(args[0])) {
            MigrationCoordinator.nodes(sender);
            return;
        }
        if (args.length == 1 && "requests".equalsIgnoreCase(args[0])) {
            MigrationCoordinator.requests(sender);
            return;
        }
        if (args.length == 3 && "queue".equalsIgnoreCase(args[0])) {
            MigrationCoordinator.queue(sender, args[1], args[2]);
            return;
        }
        if ((args.length == 2 || (args.length == 3 && "confirm".equalsIgnoreCase(args[2])))
            && "accept".equalsIgnoreCase(args[0])) {
            MigrationCoordinator.accept(sender, args[1], args.length == 3);
            return;
        }
        if (args.length == 2 && "import".equalsIgnoreCase(args[0])) {
            String error = MigrationImporter.start(sender, args[1]);
            if (error != null) {
                throw new CommandException(error);
            }
            return;
        }
        if ((args.length != 2 && args.length != 3) || !"export".equalsIgnoreCase(args[0])) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        ForgePlayer target = PlayerLookup.exact(args[1]);
        if (target == null) {
            throw new CommandException("Игрок не найден: " + args[1]);
        }
        sender.addChatMessage(
            new ChatComponentText("§eЭкспорт §f" + target.getName() + "§e: сохраняю мир и копирую файлы..."));
        String destination = args.length == 3 ? args[2] : null;
        String error = MigrationExporter.export(
            sender,
            target,
            archive -> { if (destination != null) MigrationCoordinator.queue(sender, archive, destination); });
        if (error != null) {
            throw new CommandException(error);
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(
                args,
                "ui",
                "export",
                "import",
                "queue",
                "nodes",
                "requests",
                "accept");
        }
        if (args.length == 3 && ("export".equalsIgnoreCase(args[0]) || "queue".equalsIgnoreCase(args[0]))) {
            return getListOfStringsMatchingLastWord(
                args,
                MigrationCoordinator.nodeSuggestions()
                    .toArray(new String[0]));
        }
        if (args.length == 3 && "accept".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "confirm");
        }
        if (args.length == 2 && ("import".equalsIgnoreCase(args[0]) || "queue".equalsIgnoreCase(args[0]))) {
            return getListOfStringsMatchingLastWord(
                args,
                MigrationImporter.available()
                    .toArray(new String[0]));
        }
        if (args.length == 2 && "export".equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>();
            for (ForgePlayer player : Universe.get()
                .getPlayers()) {
                if (!player.isFake()) names.add(player.getName());
            }
            return getListOfStringsMatchingLastWord(args, names.toArray(new String[0]));
        }
        return super.addTabCompletionOptions(sender, args);
    }
}
