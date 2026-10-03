package coint.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;

import coint.player.PlayerResetManager;
import coint.player.PlayerResetManager.TeamResetResult;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandPlayerReset extends CommandBase {

    private static final String PERMISSION = "cointcore.command.playerreset";

    public CommandPlayerReset() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "Reset player server progress");
    }

    @Override
    public String getCommandName() {
        return "playerreset";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/playerreset <player> [all|pdim|quests|thaum|data] [newLeader]";
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
        if (args.length < 1 || args.length > 3) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        ForgePlayer target = requireForgePlayer(args[0]);
        String mode = args.length == 1 ? "all" : args[1].toLowerCase(Locale.ROOT);

        if (args.length == 3 && !mode.equals("all")) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        switch (mode) {
            case "all" -> resetAll(sender, target, args.length == 3 ? args[2] : null);
            case "pdim" -> resetPersonalDimension(sender, target);
            case "quests" -> {
                PlayerResetManager.resetQuests(target);
                success(sender, "Квесты игрока " + target.getName() + " полностью сброшены.");
            }
            case "thaum" -> {
                PlayerResetManager.resetThaum(target);
                success(sender, "Thaumcraft игрока " + target.getName() + " полностью сброшен.");
            }
            case "data", "playerdata" -> resetPlayerData(sender, target);
            default -> throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private void resetAll(ICommandSender sender, ForgePlayer target, String successorName) throws CommandException {
        if (PlayerResetManager.requiresLeadershipTransfer(target) && successorName == null) {
            List<String> candidates = PlayerResetManager.getLeadershipCandidates(target);
            sender.addChatMessage(
                new ChatComponentText(
                    "§eИгрок " + target.getName() + " является лидером команды. Сначала укажи нового лидера:"));
            sender.addChatMessage(new ChatComponentText("§e/playerreset " + target.getName() + " all <ник>"));
            if (!candidates.isEmpty()) {
                sender.addChatMessage(new ChatComponentText("§7Можно передать: " + String.join(", ", candidates)));
            } else {
                sender.addChatMessage(
                    new ChatComponentText(
                        "§cНет общего участника, которому можно передать лидерство одновременно в SU и BQ."));
            }
            return;
        }

        ForgePlayer successor = successorName == null ? null : requireForgePlayer(successorName);
        TeamResetResult teamResult = PlayerResetManager.detachTeamsForFullReset(target, successor);

        if (teamResult.deletedPersonalDimension > 0) {
            success(
                sender,
                "PersonalSpace DIM" + teamResult.deletedPersonalDimension + " игрока " + target.getName() + " удален.");
        } else if (teamResult.keptSharedPersonalDimension) {
            success(sender, "Общая PersonalSpace команды сохранена; игрок удален только из команды.");
        }

        if (teamResult.serverUtilitiesTeamRemoved) {
            success(sender, "Игрок удален из команды ServerUtilities.");
        }
        if (teamResult.serverUtilitiesTeamDeleted) {
            success(sender, "Одиночная команда ServerUtilities удалена.");
        }
        if (teamResult.betterQuestingPartyRemoved) {
            success(sender, "Игрок удален из команды BetterQuesting.");
        }
        if (teamResult.betterQuestingPartyDeleted) {
            success(sender, "Одиночная команда BetterQuesting удалена.");
        }
        if (teamResult.leadershipTransferredTo != null) {
            success(sender, "Лидерство передано игроку " + teamResult.leadershipTransferredTo + ".");
        }

        PlayerResetManager.resetQuests(target);
        success(sender, "Квесты игрока " + target.getName() + " полностью сброшены.");

        PlayerResetManager.resetThaum(target);
        success(sender, "Thaumcraft игрока " + target.getName() + " полностью сброшен.");

        resetPlayerData(sender, target);
        success(sender, "Полный сброс игрока " + target.getName() + " запущен.");
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            for (ForgePlayer player : Universe.get()
                .getPlayers()) {
                values.add(player.getName());
            }
            return getListOfStringsMatchingLastWord(args, values.toArray(new String[0]));
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "all", "pdim", "quests", "thaum", "data");
        }
        if (args.length == 3 && "all".equalsIgnoreCase(args[1])) {
            ForgePlayer target = Universe.get()
                .getPlayer(args[0]);
            if (target != null) {
                List<String> candidates = PlayerResetManager.getLeadershipCandidates(target);
                return getListOfStringsMatchingLastWord(args, candidates.toArray(new String[0]));
            }
        }
        return super.addTabCompletionOptions(sender, args);
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return index == 0 || index == 2;
    }

    private void resetPersonalDimension(ICommandSender sender, ForgePlayer target) throws CommandException {
        int result = PlayerResetManager.resetPersonalDimension(target);
        if (result > 0) {
            success(sender, "PersonalSpace DIM" + result + " команды игрока " + target.getName() + " удален.");
        } else if (result == 0) {
            success(
                sender,
                "У команды игрока " + target.getName()
                    + " активная персоналка не найдена. Привязка и флаги выдачи очищены.");
        } else {
            success(
                sender,
                "Игрок " + target.getName() + " не состоит в ServerUtilities-команде. Сброс персоналки пропущен.");
        }
    }

    private void resetPlayerData(ICommandSender sender, ForgePlayer target) throws CommandException {
        boolean kicked = PlayerResetManager.resetPlayerData(target);
        if (kicked) {
            success(
                sender,
                "Playerdata, stats и данные/ранги ServerUtilities игрока " + target.getName()
                    + " сбрасываются. Игрок кикнут, файлы будут удалены после полного выхода.");
        } else {
            success(
                sender,
                "Playerdata, stats и данные/ранги ServerUtilities игрока " + target.getName() + " сброшены.");
        }
    }

    private ForgePlayer requireForgePlayer(String name) throws CommandException {
        ForgePlayer player = Universe.get()
            .getPlayer(name);
        if (player == null) {
            throw new CommandException("Игрок не найден: " + name);
        }
        return player;
    }

    private void success(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText("§a" + message));
    }
}
