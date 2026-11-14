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
            + " | nodes | requests | accept <id> [confirm] | move <player> <target> | move-status <id> | move-resume <id>"
            + " | move-finish-import <id> confirm-reviewed | request <player> <target>"
            + " | batch plan <epoch> <target> | batch run/status/stop <id>";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return canOpen(sender);
    }

    public boolean serverutilities$hasPermission(EntityPlayerMP player) {
        return canOpen(player);
    }

    public static boolean canOpen(ICommandSender sender) {
        if (allowed(sender)) return true;
        if (!(sender instanceof EntityPlayerMP player) || !Universe.loaded()) return false;
        ForgePlayer fp = Universe.get()
            .getPlayer(player);
        return fp != null && fp.hasTeam() && fp.team.isOwner(fp);
    }

    public static boolean allowed(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (!allowed(sender)) {
            if (!canOpen(sender)) throw new CommandException("Перенос доступен только лидеру команды");
            EntityPlayerMP leader = (EntityPlayerMP) sender;
            if (args.length == 0 || args.length == 1 && "ui".equalsIgnoreCase(args[0])) {
                MigrationCoordinator.open(leader);
                return;
            }
            if (args.length == 3 && "request".equalsIgnoreCase(args[0])
                && leader.getCommandSenderName()
                    .equalsIgnoreCase(args[1])
                && coint.cluster.ClusterService.nodeId()
                    .equals(coint.CointConfig.cluster.migrationQuestSourceNode.trim())
                && args[2].equals(coint.CointConfig.cluster.migrationQuestTargetNode.trim())) {
                coint.migration.MigrationConsent.request(
                    leader,
                    Universe.get()
                        .getPlayer(leader),
                    args[2]);
                return;
            }
            throw new CommandException("Лидер переносит только свою команду с исходного сервера: /migrate ui");
        }
        if (args.length >= 1 && "batch".equalsIgnoreCase(args[0])) {
            if (!allowed(sender)) throw new CommandException("commands.generic.permission");
            try {
                if (args.length == 4 && "plan".equals(args[1]))
                    coint.migration.MigrationBatch.plan(sender, args[2], args[3]);
                else if (args.length == 3) coint.migration.MigrationBatch.command(sender, args[1], args[2]);
                else throw new java.io.IOException("/migrate batch plan <epoch> <target> | run/status/stop <id>");
            } catch (java.io.IOException | RuntimeException e) {
                throw new CommandException(e.getMessage());
            }
            return;
        }
        if (!allowed(sender)) throw new CommandException("Нет права " + PERMISSION);
        if (args.length == 3 && "request".equalsIgnoreCase(args[0])) {
            ForgePlayer player = PlayerLookup.exact(args[1]);
            if (player == null) throw new CommandException("Игрок не найден: " + args[1]);
            coint.migration.MigrationConsent.request(sender, player, args[2]);
            return;
        }
        if (args.length == 3 && "move-finish-import".equalsIgnoreCase(args[0]) && "confirm-reviewed".equals(args[2])) {
            coint.migration.MigrationMove.finishReviewedImport(sender, args[1]);
            return;
        }
        if (args.length == 3 && "move".equalsIgnoreCase(args[0])) {
            ForgePlayer player = PlayerLookup.exact(args[1]);
            if (player == null) throw new CommandException("Игрок не найден: " + args[1]);
            coint.migration.MigrationMove.start(sender, player, args[2]);
            return;
        }
        if (args.length == 2 && "move-status".equalsIgnoreCase(args[0])) {
            coint.migration.MigrationMove.status(sender, args[1]);
            return;
        }
        if (args.length == 2 && "move-resume".equalsIgnoreCase(args[0])) {
            coint.migration.MigrationMove.resume(sender, args[1]);
            return;
        }
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
                "accept",
                "move",
                "request",
                "batch",
                "move-status",
                "move-resume");
        }
        if (args.length == 2 && "batch".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "plan", "run", "status", "stop");
        }
        if (args.length == 3 && ("export".equalsIgnoreCase(args[0]) || "queue".equalsIgnoreCase(args[0])
            || "move".equalsIgnoreCase(args[0])
            || "request".equalsIgnoreCase(args[0]))) {
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
        if (args.length == 2 && ("export".equalsIgnoreCase(args[0]) || "move".equalsIgnoreCase(args[0])
            || "request".equalsIgnoreCase(args[0]))) {
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
