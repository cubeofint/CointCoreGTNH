package coint.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import coint.restart.RestartManager;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

public class CommandRestart extends CommandBase {

    private static final String PERMISSION = "cointcore.command.restart";
    private static final Pattern PART = Pattern.compile("(\\d+)([smhd])", Pattern.CASE_INSENSITIVE);

    public CommandRestart() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "Schedule a safe server restart");
    }

    @Override
    public String getCommandName() {
        return "restart";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/restart <30s|5m|1h|now|cancel|status>";
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
        if (args.length != 1) {
            throw new WrongUsageException(getCommandUsage(sender));
        }

        String arg = args[0].trim();
        if (arg.equalsIgnoreCase("cancel")) {
            send(sender, RestartManager.INSTANCE.cancel(), EnumChatFormatting.YELLOW);
            return;
        }
        if (arg.equalsIgnoreCase("status")) {
            send(sender, RestartManager.INSTANCE.getStatus(), EnumChatFormatting.AQUA);
            return;
        }

        long seconds;
        if (arg.equalsIgnoreCase("now")) {
            seconds = 0L;
        } else {
            seconds = parseDuration(arg);
            if (seconds < 0L) {
                throw new WrongUsageException(getCommandUsage(sender));
            }
        }

        if (seconds > 7L * 24L * 60L * 60L) {
            send(sender, "Максимум можно запланировать на 7 дней вперёд.", EnumChatFormatting.RED);
            return;
        }

        send(sender, RestartManager.INSTANCE.schedule(seconds), EnumChatFormatting.GREEN);
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "5m", "15m", "30m", "1h", "now", "status", "cancel");
        }
        return new ArrayList<String>();
    }

    private static long parseDuration(String input) {
        if (input.matches("\\d+")) {
            try {
                return Long.parseLong(input);
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }

        Matcher matcher = PART.matcher(input);
        int end = 0;
        long total = 0L;
        boolean any = false;
        while (matcher.find()) {
            if (matcher.start() != end) {
                return -1L;
            }
            long value;
            try {
                value = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return -1L;
            }
            char unit = Character.toLowerCase(
                matcher.group(2)
                    .charAt(0));
            long multiplier;
            switch (unit) {
                case 's':
                    multiplier = 1L;
                    break;
                case 'm':
                    multiplier = 60L;
                    break;
                case 'h':
                    multiplier = 3600L;
                    break;
                case 'd':
                    multiplier = 86400L;
                    break;
                default:
                    return -1L;
            }
            if (value > Long.MAX_VALUE / multiplier || total > Long.MAX_VALUE - value * multiplier) {
                return -1L;
            }
            total += value * multiplier;
            end = matcher.end();
            any = true;
        }
        return any && end == input.length() ? total : -1L;
    }

    private static void send(ICommandSender sender, String text, EnumChatFormatting color) {
        ChatComponentText message = new ChatComponentText(text);
        message.getChatStyle()
            .setColor(color);
        sender.addChatMessage(message);
    }
}
