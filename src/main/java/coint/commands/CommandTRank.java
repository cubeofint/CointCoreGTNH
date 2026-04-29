package coint.commands;

import java.util.Calendar;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.commands.temprank.TempRankEntry;
import coint.commands.temprank.TempRankManager;
import coint.util.TimeUtil;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;
import serverutils.ranks.Ranks;

/**
 * Command for managing temporary rank assignments.
 *
 * <pre>
 * /trank give   &lt;player&gt; &lt;rank&gt; &lt;duration&gt;   — give a rank for a limited time
 * /trank extend &lt;player&gt; &lt;rank&gt; &lt;duration&gt; — продлить активную запись с конечным сроком; иначе как {@code give} (RCON / сайт)
 * /trank remove &lt;player&gt; &lt;rank&gt;              — manually revoke a temp rank
 * /trank list  [player]                       — list active temp ranks
 * </pre>
 *
 * Duration format: {@code 30s}, {@code 10m} (minutes), {@code 2h}, {@code 7d}, {@code 1mo} (calendar months).
 * Permission: {@code cointcore.command.trank} (default: OP)
 */
public class CommandTRank extends CommandBase {

    public CommandTRank() {
        PermissionAPI
            .registerNode("cointcore.command.trank", DefaultPermissionLevel.OP, "Manage temporary rank assignments");
    }

    @Override
    public String getCommandName() {
        return "trank";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/trank give <player> <rank> <duration> | /trank extend <player> <rank> <duration> | /trank remove <player> <rank> | /trank list [player]";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, "cointcore.command.trank");
        }
        return true;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "give", "extend", "remove", "list");
        }
        if (args.length == 2 && !"list".equals(args[0])) {
            return getListOfStringsMatchingLastWord(
                args,
                Universe.get()
                    .getPlayers()
                    .stream()
                    .map(ForgePlayer::getName)
                    .toArray(String[]::new));
        }
        if (args.length == 3 && ("give".equals(args[0]) || "extend".equals(args[0]))) {
            Ranks ranks = Ranks.INSTANCE;
            if (ranks != null) {
                return getListOfStringsMatchingLastWord(
                    args,
                    ranks.ranks.keySet()
                        .toArray(new String[0]));
            }
        }
        if (args.length == 3 && args[0].equals("remove")) {
            return getListOfStringsMatchingLastWord(
                args,
                Ranks.INSTANCE != null ? Ranks.INSTANCE.ranks.keySet()
                    .toArray(new String[0]) : new String[0]);
        }
        return super.addTabCompletionOptions(sender, args);
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new WrongUsageException(getCommandUsage(sender));

        switch (args[0].toLowerCase()) {
            case "give" -> cmdGive(sender, args);
            case "extend" -> cmdExtend(sender, args);
            case "remove" -> cmdRemove(sender, args);
            case "list" -> cmdList(sender, args);
            default -> throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    // ------------------------------------------------------------------
    // Sub-commands
    // ------------------------------------------------------------------

    /** /trank give <player> <rank> <duration> */
    private void cmdGive(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 4) throw new WrongUsageException("/trank give <player> <rank> <duration>");

        ForgePlayer fp = resolvePlayer(args[1]);
        String rankId = args[2];
        long durationMs = parseDuration(args[3]);

        // Validate rank exists before calling manager
        if (Ranks.INSTANCE == null || Ranks.INSTANCE.getRank(rankId) == null) {
            throw new CommandException("Ранг §e" + rankId + "§r не найден в ServerUtilities");
        }

        try {
            TempRankManager.get()
                .grant(
                    fp.getProfile()
                        .getId(),
                    fp.getName(),
                    rankId,
                    durationMs,
                    sender.getCommandSenderName());
        } catch (Exception e) {
            throw new CommandException(e.getMessage());
        }

        String durStr = durationMs < 0 ? "навсегда" : "на " + TimeUtil.formatDuration(durationMs);
        sender.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.GREEN + "Ранг §e" + rankId + "§a выдан §e" + fp.getName() + "§a " + durStr));
    }

    /**
     * /trank extend — если есть активная запись с конечным сроком, продлевает её; иначе выполняет ту же логику, что
     * {@link #cmdGive}.
     */
    private void cmdExtend(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 4) {
            throw new WrongUsageException("/trank extend <player> <rank> <duration>");
        }

        ForgePlayer fp = resolvePlayer(args[1]);
        String rankId = args[2];
        long durationMs = parseDuration(args[3]);
        if (durationMs < 0) {
            throw new CommandException("Для продления укажите конечную длительность (не perm/навсегда)");
        }

        if (Ranks.INSTANCE == null || Ranks.INSTANCE.getRank(rankId) == null) {
            throw new CommandException("Ранг §e" + rankId + "§r не найден в ServerUtilities");
        }

        UUID uuid = fp.getProfile()
            .getId();
        TempRankManager mgr = TempRankManager.get();

        if (mgr.findActiveTimedEntry(uuid, rankId) != null) {
            try {
                mgr.extend(uuid, fp.getName(), rankId, durationMs, sender.getCommandSenderName());
            } catch (IllegalArgumentException e) {
                throw new CommandException(e.getMessage());
            }
            sender.addChatMessage(
                new ChatComponentText(
                    EnumChatFormatting.GREEN + "Срок ранга §e"
                        + rankId
                        + "§a для §e"
                        + fp.getName()
                        + "§a продлён на "
                        + TimeUtil.formatDuration(durationMs)));
            return;
        }

        try {
            mgr.grant(uuid, fp.getName(), rankId, durationMs, sender.getCommandSenderName());
        } catch (Exception e) {
            throw new CommandException(e.getMessage());
        }

        String durStr = "на " + TimeUtil.formatDuration(durationMs);
        sender.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.GREEN + "Ранг §e" + rankId + "§a выдан §e" + fp.getName() + "§a " + durStr));
    }

    /** /trank remove <player> <rank> */
    private void cmdRemove(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 3) throw new WrongUsageException("/trank remove <player> <rank>");

        ForgePlayer fp = resolvePlayer(args[1]);
        String rankId = args[2];
        UUID uuid = fp.getProfile()
            .getId();

        boolean had = TempRankManager.get()
            .getEntries(uuid)
            .stream()
            .anyMatch(e -> e.rankId.equals(rankId));
        if (!had) {
            throw new CommandException("У игрока §e" + fp.getName() + "§r нет временного ранга §e" + rankId);
        }

        TempRankManager.get()
            .revoke(uuid, rankId);
        sender.addChatMessage(
            new ChatComponentText(
                EnumChatFormatting.GREEN + "Временный ранг §e" + rankId + "§a снят с §e" + fp.getName()));
    }

    /** /trank list [player] */
    private void cmdList(ICommandSender sender, String[] args) throws CommandException {
        if (args.length >= 2) {
            // List for a specific player
            ForgePlayer fp = resolvePlayer(args[1]);
            UUID uuid = fp.getProfile()
                .getId();
            List<TempRankEntry> list = TempRankManager.get()
                .getEntries(uuid);
            if (list.isEmpty()) {
                sender.addChatMessage(
                    new ChatComponentText(
                        "У игрока " + EnumChatFormatting.GOLD
                            + fp.getName()
                            + EnumChatFormatting.RESET
                            + " нет активных временных рангов"));
                return;
            }
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.YELLOW + "Временные ранги " + fp.getName() + ":"));
            for (TempRankEntry e : list) {
                sender.addChatMessage(
                    new ChatComponentText(
                        "  §e" + e.rankId
                            + "§r — "
                            + e.remainingTime()
                            + " (до "
                            + e.expireDate()
                            + "), выдал: §7"
                            + e.issuer));
            }
        } else {
            // Global list
            List<TempRankEntry> all = TempRankManager.get()
                .getAllEntries();
            if (all.isEmpty()) {
                sender.addChatMessage(new ChatComponentText("Активных временных рангов нет"));
                return;
            }
            sender.addChatMessage(
                new ChatComponentText(EnumChatFormatting.YELLOW + "Все временные ранги (" + all.size() + "):"));
            for (TempRankEntry e : all) {
                if (!e.isExpired()) {
                    sender.addChatMessage(
                        new ChatComponentText(
                            "  §e" + e.playerName
                                + "§r → §e"
                                + e.rankId
                                + "§r — "
                                + e.remainingTime()
                                + ", выдал: §7"
                                + e.issuer));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ForgePlayer resolvePlayer(String name) throws CommandException {
        ForgePlayer fp = Universe.get()
            .getPlayer(name);
        if (fp == null) {
            throw new CommandException("Игрок §e" + name + "§r не найден (ни разу не заходил на сервер)");
        }
        return fp;
    }

    /**
     * Разница во времени от «сейчас» до даты через {@code months} календарных месяцев
     * (учёт 28/29/30/31 дня, високосных лет и т.д. — через {@link Calendar#add(int, int)}).
     */
    private static long millisForCalendarMonths(long months) throws WrongUsageException {
        if (months <= 0) {
            throw new WrongUsageException("Число месяцев должно быть положительным");
        }
        if (months > Integer.MAX_VALUE) {
            throw new WrongUsageException("Слишком большое число месяцев");
        }
        Calendar cal = Calendar.getInstance();
        long start = cal.getTimeInMillis();
        cal.add(Calendar.MONTH, (int) months);
        return cal.getTimeInMillis() - start;
    }

    /**
     * Parses duration strings: {@code 30s}, {@code 10m} (minutes), {@code 2h}, {@code 7d}, {@code 1mo}…{@code 12mo}
     * (calendar months).
     *
     * @return milliseconds, or {@code -1} for "perm"
     */
    private static long parseDuration(String raw) throws WrongUsageException {
        String s = raw.toLowerCase()
            .trim();
        if (s.equals("perm") || s.equals("permanent") || s.equals("навсегда")) {
            return -1;
        }
        if (s.length() < 2) {
            throw new WrongUsageException(
                "Неверный формат времени. Примеры: 30s, 10m (минуты), 2h, 7d, 1mo (календарные месяцы)");
        }
        try {
            if (s.endsWith("mo")) {
                if (s.length() < 3) {
                    throw new WrongUsageException("Укажите число перед mo, например: 1mo, 3mo, 12mo");
                }
                long months = Long.parseLong(s.substring(0, s.length() - 2));
                return millisForCalendarMonths(months);
            }
            long value = Long.parseLong(s.substring(0, s.length() - 1));
            return switch (s.charAt(s.length() - 1)) {
                case 's' -> value * 1_000L;
                case 'm' -> value * 60_000L;
                case 'h' -> value * 3_600_000L;
                case 'd' -> value * 86_400_000L;
                default -> throw new WrongUsageException(
                    "Неверный суффикс. Используйте: s, m (минуты), h, d, mo (календарные месяцы)");
            };
        } catch (NumberFormatException e) {
            throw new WrongUsageException("Неверный формат времени: " + raw);
        }
    }
}
