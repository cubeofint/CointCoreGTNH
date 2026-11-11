package coint.chunkbonus;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import serverutils.data.ServerUtilitiesTeamData;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * {@code /chunkbonus}: chunk loading bought on the website.
 * <ul>
 * <li>{@code /chunkbonus}: a player sees his purchases and the limit of his team.</li>
 * <li>{@code add <nick> <chunks> <days> [note]}: the website (RCON) or an admin gives a purchase.</li>
 * <li>{@code list <nick>}, {@code remove <nick> <n|all>}: admins.</li>
 * </ul>
 */
public class CommandChunkBonus extends CommandBase {

    public static final String ADMIN = "cointcore.chunkbonus.admin";

    public CommandChunkBonus() {
        PermissionAPI.registerNode(ADMIN, DefaultPermissionLevel.OP, "Give and take paid chunk loading");
    }

    @Override
    public String getCommandName() {
        return "chunkbonus";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return isAdmin(sender) ? "/chunkbonus [add <ник> <чанки> <дни> [пометка] | list <ник> | remove <ник> <n|all>]"
            : "/chunkbonus";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    private static boolean isAdmin(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, ADMIN);
        }
        return true;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (!isAdmin(sender)) {
            return null;
        }
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "add", "list", "remove");
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(
                args,
                MinecraftServer.getServer()
                    .getAllUsernames());
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof EntityPlayerMP player)) {
                throw new WrongUsageException(getCommandUsage(sender));
            }
            show(sender, player.getCommandSenderName());
            return;
        }
        if (!isAdmin(sender)) {
            throw new WrongUsageException("/chunkbonus");
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 4) {
                    throw new WrongUsageException("/chunkbonus add <ник> <чанки> <дни> [пометка]");
                }
                int chunks = parseIntBounded(sender, args[2], 1, 10_000);
                int days = parseIntBounded(sender, args[3], 1, 3650);
                String note = args.length > 4 ? func_147178_a(sender, args, 4).getUnformattedText() : null;
                ChunkBonus.add(args[1], chunks, days, note);
                sender.addChatMessage(
                    new ChatComponentText("§a" + args[1] + ": +" + chunks + " чанк(ов) прогрузки на " + days + " дн."));
                notifyPlayer(
                    args[1],
                    "§aВашей команде добавлено §f" + chunks
                        + "§a чанк(ов) прогрузки на §f"
                        + days
                        + "§a дн. Спасибо за поддержку!");
            }
            case "list" -> {
                if (args.length < 2) {
                    throw new WrongUsageException("/chunkbonus list <ник>");
                }
                show(sender, args[1]);
            }
            case "remove" -> {
                if (args.length < 3) {
                    throw new WrongUsageException("/chunkbonus remove <ник> <n|all>");
                }
                int index = "all".equalsIgnoreCase(args[2]) ? -1 : parseIntWithMin(sender, args[2], 1) - 1;
                int removed = ChunkBonus.remove(args[1], index);
                sender.addChatMessage(new ChatComponentText("§eСнято покупок: " + removed));
            }
            default -> throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private static void show(ICommandSender sender, String name) {
        List<ChunkBonus.Entry> mine = ChunkBonus.of(name);
        SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        if (mine.isEmpty()) {
            sender.addChatMessage(new ChatComponentText("§7У " + name + " нет оплаченной прогрузки чанков."));
        } else {
            sender.addChatMessage(new ChatComponentText("§6Оплаченная прогрузка " + name + ":"));
            int i = 1;
            for (ChunkBonus.Entry e : mine) {
                sender.addChatMessage(
                    new ChatComponentText(
                        "§7" + i++
                            + ". §f+"
                            + e.chunks
                            + "§7 до §f"
                            + date.format(new Date(e.expiresAt))
                            + (e.note == null ? "" : " §8(" + e.note + ")")));
            }
        }
        ForgePlayer player = Universe.loaded() ? Universe.get()
            .getPlayer(name) : null;
        if (player != null && player.team != null && player.team.isValid()) {
            int max = ServerUtilitiesTeamData.get(player.team)
                .getMaxChunkloaderChunks();
            sender.addChatMessage(
                new ChatComponentText(
                    "§7Лимит прогрузки команды: §f" + max
                        + "§7, из них оплачено участниками: §f"
                        + ChunkBonus.bonusOf(player.team)));
        } else if (!mine.isEmpty()) {
            sender.addChatMessage(
                new ChatComponentText(
                    "§eКоманды нет: чанки добавятся к лимиту, как только " + name + " будет в команде."));
        }
    }

    private static void notifyPlayer(String name, String text) {
        EntityPlayerMP player = MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152612_a(name);
        if (player != null) {
            player.addChatMessage(new ChatComponentText(text));
        }
    }
}
