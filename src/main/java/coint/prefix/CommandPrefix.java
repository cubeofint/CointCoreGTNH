package coint.prefix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * {@code /prefix}: pick which of your ranks' prefixes to wear ({@code /prefix <rank>}, {@code /prefix auto}).
 * {@code /namecolor <color|reset>}: the color of your name, with {@code cointcore.namecolor}.
 */
public class CommandPrefix extends CommandBase {

    public static final String COLOR_PERMISSION = "cointcore.namecolor";
    private static final String AUTO = "auto";

    private final boolean color;

    public CommandPrefix(boolean color) {
        this.color = color;
        if (color) {
            PermissionAPI.registerNode(COLOR_PERMISSION, DefaultPermissionLevel.OP, "Pick the color of one's name");
        }
    }

    @Override
    public String getCommandName() {
        return color ? "namecolor" : "prefix";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return color ? "/namecolor <цвет|reset>" : "/prefix [ранг|auto]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (!(sender instanceof EntityPlayer player)) {
            return false;
        }
        return !color || PermissionAPI.hasPermission(player, COLOR_PERMISSION);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length != 1 || !(sender instanceof EntityPlayerMP player)) {
            return Collections.emptyList();
        }
        List<String> options = new ArrayList<>();
        if (color) {
            options.add("reset");
            for (EnumChatFormatting f : EnumChatFormatting.values()) {
                if (f.isColor()) {
                    options.add(f.getFriendlyName());
                }
            }
        } else {
            options.add(AUTO);
            for (PrefixService.Option option : PrefixService.available(player)) {
                options.add(option.rank);
            }
        }
        return getListOfStringsMatchingLastWord(args, options.toArray(new String[0]));
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP player)) {
            return;
        }
        if (color) {
            nameColor(player, args);
        } else {
            prefix(player, args);
        }
    }

    private static void prefix(EntityPlayerMP player, String[] args) {
        if (args.length == 0) {
            list(player);
            return;
        }
        String pick = args[0].toLowerCase(Locale.ROOT);
        if (AUTO.equals(pick)) {
            PrefixService.select(player, null);
            PrefixSiteSync.changed(player);
            player.addChatMessage(new ChatComponentText("§aТеперь показывается префикс вашего старшего ранга."));
            return;
        }
        if (!PrefixService.select(player, pick)) {
            player.addChatMessage(new ChatComponentText("§cУ вас нет ранга с таким префиксом: " + args[0]));
            return;
        }
        PrefixSiteSync.changed(player);
        player.addChatMessage(new ChatComponentText("§aПрефикс выбран."));
    }

    private static void list(EntityPlayerMP player) {
        List<PrefixService.Option> options = PrefixService.available(player);
        String selected = PrefixService.selected(player);
        player.addChatMessage(new ChatComponentText("§6Ваши префиксы (нажмите, чтобы выбрать):"));
        player.addChatMessage(option("auto", "§7Старший ранг", selected == null));
        for (PrefixService.Option option : options) {
            player.addChatMessage(option(option.rank, option.prefix(), option.rank.equals(selected)));
        }
    }

    private static ChatComponentText option(String rank, String shown, boolean current) {
        ChatComponentText line = new ChatComponentText((current ? "§a▶ " : "§7- ") + shown + "§r §8(" + rank + ")");
        String command = "/prefix " + rank;
        line.setChatStyle(
            new ChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(command))));
        return line;
    }

    private static void nameColor(EntityPlayerMP player, String[] args) {
        if (args.length == 0) {
            ChatComponentText line = new ChatComponentText("§6Цвет ника: ");
            for (EnumChatFormatting f : EnumChatFormatting.values()) {
                if (!f.isColor()) {
                    continue;
                }
                ChatComponentText sample = new ChatComponentText(f + "■ ");
                String command = "/namecolor " + f.getFriendlyName();
                sample.setChatStyle(
                    new ChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                        .setChatHoverEvent(
                            new HoverEvent(
                                HoverEvent.Action.SHOW_TEXT,
                                new ChatComponentText(f + f.getFriendlyName()))));
                line.appendSibling(sample);
            }
            ChatComponentText reset = new ChatComponentText("§7[сброс]");
            reset.setChatStyle(
                new ChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/namecolor reset")));
            line.appendSibling(reset);
            player.addChatMessage(line);
            return;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        if ("reset".equals(name)) {
            PrefixService.setColor(player, null);
            player.addChatMessage(new ChatComponentText("§aЦвет ника сброшен."));
            return;
        }
        EnumChatFormatting f = EnumChatFormatting.getValueByName(name);
        if (f == null || !f.isColor()) {
            player.addChatMessage(new ChatComponentText("§cНет такого цвета: " + args[0]));
            return;
        }
        PrefixService.setColor(player, f);
        player.addChatMessage(new ChatComponentText("§aЦвет ника: " + f + f.getFriendlyName()));
    }
}
