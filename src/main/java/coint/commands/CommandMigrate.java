package coint.commands;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

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
        return "/migrate export <player> | /migrate import <file.zip>";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 2 && "import".equalsIgnoreCase(args[0])) {
            String error = MigrationImporter.start(sender, args[1]);
            if (error != null) {
                throw new CommandException(error);
            }
            return;
        }
        if (args.length != 2 || !"export".equalsIgnoreCase(args[0])) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        ForgePlayer target = PlayerLookup.exact(args[1]);
        if (target == null) {
            throw new CommandException("Игрок не найден: " + args[1]);
        }
        sender.addChatMessage(
            new ChatComponentText("§eЭкспорт §f" + target.getName() + "§e: сохраняю мир и копирую файлы..."));
        String error = MigrationExporter.export(sender, target);
        if (error != null) {
            throw new CommandException(error);
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "export", "import");
        }
        if (args.length == 2 && "import".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(
                args,
                MigrationImporter.available()
                    .toArray(new String[0]));
        }
        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            for (ForgePlayer player : Universe.get()
                .getPlayers()) {
                names.add(player.getName());
            }
            return getListOfStringsMatchingLastWord(args, names.toArray(new String[0]));
        }
        return super.addTabCompletionOptions(sender, args);
    }
}
