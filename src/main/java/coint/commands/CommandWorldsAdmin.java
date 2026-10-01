package coint.commands;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import coint.worldtravel.WorldTravelManager;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandWorldsAdmin extends CommandBase {

    private static final String PERMISSION = "cointcore.command.worldsadmin";

    public CommandWorldsAdmin() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "Manage CointCore world travel destinations");
    }

    @Override
    public String getCommandName() {
        return "worldsadmin";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/worldsadmin add <id> <name...> | setpos <id> | quest <id> <questId|none> | requirement <id> <text...|none> | protect <id> <true|false> | enable <id> <true|false> | remove <id> | info <id> | list";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(
                args,
                "add",
                "setpos",
                "quest",
                "requirement",
                "protect",
                "enable",
                "remove",
                "info",
                "list");
        }
        if (args.length == 2 && !"add".equalsIgnoreCase(args[0]) && !"list".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(
                args,
                WorldTravelManager.getDestinationIds()
                    .toArray(new String[0]));
        }
        if (args.length == 3 && ("protect".equalsIgnoreCase(args[0]) || "enable".equalsIgnoreCase(args[0]))) {
            return getListOfStringsMatchingLastWord(args, "true", "false");
        }
        if (args.length == 3 && "quest".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(args, "none");
        }
        return super.addTabCompletionOptions(sender, args);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        switch (args[0].toLowerCase()) {
            case "add" -> add(sender, args);
            case "setpos" -> setPosition(sender, args);
            case "quest" -> setQuest(sender, args);
            case "requirement" -> setRequirement(sender, args);
            case "protect" -> setProtect(sender, args);
            case "enable" -> setEnabled(sender, args);
            case "remove" -> remove(sender, args);
            case "info" -> info(sender, args);
            case "list" -> list(sender);
            default -> throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private void add(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 3) {
            throw new WrongUsageException("/worldsadmin add <id> <name...>");
        }
        EntityPlayerMP player = requirePlayer(sender);
        String id = args[1];
        String name = join(args, 2);
        if (!WorldTravelManager.createDestinationFromPlayer(player, id, name)) {
            throw new CommandException("Точка с id '" + id + "' уже существует или id пустой");
        }
        sender.addChatMessage(
            new ChatComponentText(
                "§aТочка §f" + id + " §aсоздана: DIM §f" + player.dimension + " §aпо текущим координатам."));
    }

    private void setPosition(ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 2) {
            throw new WrongUsageException("/worldsadmin setpos <id>");
        }
        EntityPlayerMP player = requirePlayer(sender);
        if (!WorldTravelManager.updateDestinationPositionFromPlayer(player, args[1])) {
            throw new CommandException("Точка '" + args[1] + "' не найдена");
        }
        sender.addChatMessage(
            new ChatComponentText(
                "§aПозиция §f" + args[1] + " §aобновлена: DIM §f" + player.dimension + "§a, текущие координаты."));
    }

    private void setQuest(ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 3) {
            throw new WrongUsageException("/worldsadmin quest <id> <questId|none>");
        }
        String quest = args[2];
        if ("none".equalsIgnoreCase(quest)) {
            quest = "";
        } else if (!WorldTravelManager.isValidQuestId(quest)) {
            throw new CommandException("Некорректный ID квеста: " + quest);
        }
        if (!WorldTravelManager.setDestinationQuest(args[1], quest)) {
            throw new CommandException("Точка '" + args[1] + "' не найдена");
        }
        sender.addChatMessage(
            new ChatComponentText(quest.isEmpty() ? "§aТребование квеста удалено." : "§aКвест для точки обновлён."));
    }

    private void setRequirement(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 3) {
            throw new WrongUsageException("/worldsadmin requirement <id> <text...|none>");
        }
        String text = join(args, 2);
        if ("none".equalsIgnoreCase(text)) {
            text = "";
        }
        if (!WorldTravelManager.setDestinationRequirementText(args[1], text)) {
            throw new CommandException("Точка '" + args[1] + "' не найдена");
        }
        sender.addChatMessage(new ChatComponentText("§aТекст требования обновлён."));
    }

    private void setProtect(ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 3) {
            throw new WrongUsageException("/worldsadmin protect <id> <true|false>");
        }
        boolean value = parseBoolean(args[2]);
        if (!WorldTravelManager.setDestinationProtected(args[1], value)) {
            throw new CommandException("Точка '" + args[1] + "' не найдена");
        }
        sender.addChatMessage(new ChatComponentText("§aЗащита DIM: §f" + value));
    }

    private void setEnabled(ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 3) {
            throw new WrongUsageException("/worldsadmin enable <id> <true|false>");
        }
        boolean value = parseBoolean(args[2]);
        if (!WorldTravelManager.setDestinationEnabled(args[1], value)) {
            throw new CommandException("Точка '" + args[1] + "' не найдена");
        }
        sender.addChatMessage(new ChatComponentText("§aТочка enabled: §f" + value));
    }

    private void remove(ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 2) {
            throw new WrongUsageException("/worldsadmin remove <id>");
        }
        if (!WorldTravelManager.removeDestination(args[1])) {
            throw new CommandException("Точка '" + args[1] + "' не найдена");
        }
        sender.addChatMessage(new ChatComponentText("§aТочка удалена: §f" + args[1]));
    }

    private void info(ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 2) {
            throw new WrongUsageException("/worldsadmin info <id>");
        }
        String info = WorldTravelManager.getDestinationInfo(args[1]);
        if (info == null) {
            throw new CommandException("Точка '" + args[1] + "' не найдена");
        }
        sender.addChatMessage(new ChatComponentText("§e" + info));
    }

    private void list(ICommandSender sender) {
        List<String> ids = WorldTravelManager.getDestinationIds();
        if (ids.isEmpty()) {
            sender.addChatMessage(new ChatComponentText("§7Точек путешествия нет."));
            return;
        }
        sender.addChatMessage(new ChatComponentText("§eТочки: §f" + String.join(", ", ids)));
    }

    private EntityPlayerMP requirePlayer(ICommandSender sender) throws CommandException {
        if (!(sender instanceof EntityPlayerMP player)) {
            throw new CommandException("Эта команда требует игрока, чтобы взять текущий DIM и координаты");
        }
        return player;
    }

    private boolean parseBoolean(String value) throws CommandException {
        if ("true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        throw new CommandException("Ожидалось true или false");
    }

    private String join(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }
}
