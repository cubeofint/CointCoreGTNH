package coint.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.google.gson.JsonParseException;

import coint.epochsync.EpochEntry;
import coint.epochsync.EpochRegistry;
import coint.poll.PollDefinition;
import serverutils.lib.util.permission.PermissionAPI;

public final class CommandPoll extends CommandBase {

    public static final String PERMISSION = "cointcore.command.poll";
    private static final String DRAFT = "cointcore/poll.json";

    @Override
    public String getCommandName() {
        return "poll";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/poll start <эпоха> <срок> | /poll stop | /poll results [ID]";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return sender instanceof EntityPlayer player ? PermissionAPI.hasPermission(player, PERMISSION)
            : super.canCommandSenderUseCommand(sender);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "start", "stop", "results");
        if (args.length == 2 && "start".equals(args[0]) && EpochRegistry.INST != null) {
            return getListOfStringsMatchingLastWord(
                args,
                EpochRegistry.INST.epochs.values()
                    .stream()
                    .map(epoch -> epoch.rankName)
                    .toArray(String[]::new));
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender)) throw new CommandException("commands.generic.permission");
        if (!ClusterService.isRunning()) throw new CommandException("Опросам нужна активная база кластера.");
        if (args.length == 0) throw new WrongUsageException(getCommandUsage(sender));
        switch (args[0]) {
            case "start" -> {
                if (args.length != 3) throw new WrongUsageException("/poll start <эпоха> <срок, например 48h или 2d>");
                start(sender, args[1], args[2]);
            }
            case "stop" -> {
                if (args.length != 1) throw new WrongUsageException("/poll stop");
                ClusterService.query(db -> ClusterPollStore.stop(db.connection()), stopped -> {
                    ClusterPolls.refreshSoon();
                    tell(
                        sender,
                        stopped ? "Опрос завершён досрочно. Голоса сохранены." : "Активного опроса сейчас нет.");
                }, error -> unavailable(sender));
            }
            case "results" -> {
                if (args.length > 2 || args.length == 2 && !PollDefinition.validId(args[1]))
                    throw new WrongUsageException("/poll results [ID]");
                String id = args.length == 2 ? args[1] : null;
                ClusterService.query(
                    db -> ClusterPollStore.results(db.connection(), id),
                    results -> showResults(sender, results),
                    error -> unavailable(sender));
            }
            default -> throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private static void start(ICommandSender sender, String rank, String duration) {
        EpochEntry epoch = EpochRegistry.INST == null ? null : EpochRegistry.INST.getEpoch(rank);
        if (epoch == null) throw new CommandException("Неизвестная эпоха " + rank + ". Выберите ID с Tab.");
        if (!duration.matches("[1-9][0-9]*[hHdD]")) {
            throw new WrongUsageException("Укажите целое число часов или дней, например 48h или 2d.");
        }
        int amount = parseIntWithMin(sender, duration.substring(0, duration.length() - 1), 1);
        long millis = Character.toLowerCase(duration.charAt(duration.length() - 1)) == 'd'
            ? TimeUnit.DAYS.toMillis(amount)
            : TimeUnit.HOURS.toMillis(amount);
        PollDefinition draft = readDraft();
        ClusterService.query(db -> ClusterPollStore.start(db.connection(), draft, rank, millis), result -> {
            switch (result) {
                case STARTED -> {
                    ClusterPolls.refreshSoon();
                    tell(
                        sender,
                        "Начался опрос " + draft.id + " для эпохи " + rank + " и выше. Срок " + duration + ".");
                }
                case ALREADY_OPEN -> tell(
                    sender,
                    "Дождитесь конца текущего опроса или завершите его через /poll stop.");
                case ID_USED -> tell(
                    sender,
                    "ID " + draft.id + " уже использовался. Укажите новый ID в " + DRAFT + ".");
            }
        }, error -> unavailable(sender));
    }

    private static PollDefinition readDraft() {
        Path file = MinecraftServer.getServer()
            .getFile(DRAFT)
            .toPath();
        try {
            if (!Files.isRegularFile(file)) throw new CommandException("Поместите черновик опроса в " + DRAFT + ".");
            if (Files.size(file) > 32 * 1024) throw new CommandException("Сократите черновик опроса до 32 КБ.");
            return PollDefinition.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new CommandException("Не удалось прочитать " + DRAFT + ".");
        } catch (JsonParseException e) {
            throw new CommandException("Исправьте синтаксис JSON в " + DRAFT + ".");
        } catch (IllegalArgumentException e) {
            throw new CommandException(e.getMessage());
        }
    }

    private static void showResults(ICommandSender sender, ClusterPollStore.Results results) {
        if (results == null) {
            tell(sender, "Опрос не найден.");
            return;
        }
        ClusterPollStore.Poll poll = results.poll;
        tell(sender, "§6" + poll.definition.question + " §7(" + poll.definition.id + ")");
        tell(sender, poll.timeLeft > 0 ? "Опрос идёт. Минимальная эпоха " + poll.epoch + "." : "Опрос завершён.");
        long total = 0;
        for (int i = 0; i < results.counts.length; i++) {
            long count = results.counts[i];
            total += count;
            tell(sender, poll.definition.answers.get(i) + " — " + count);
        }
        tell(sender, "Всего голосов " + total + ".");
    }

    private static void unavailable(ICommandSender sender) {
        tell(sender, "§cБаза опросов временно недоступна. Повторите команду.");
    }

    private static void tell(ICommandSender sender, String message) {
        sender.addChatMessage(new ChatComponentText(message));
    }
}
