package coint.blocklog;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.cluster.ClusterCommands;
import coint.cluster.ClusterService;
import coint.util.TimeUtil;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.registry.GameData;
import serverutils.lib.util.permission.DefaultPermissionLevel;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * {@code /blocklog} for admins: who did what to blocks and containers.
 * <ul>
 * <li>{@code inspect}: while on, clicking a block shows its history instead of breaking or using it.</li>
 * <li>{@code near [radius] [time]}: what happened around.</li>
 * <li>{@code player <nick> [time]}: what a player did, on every server.</li>
 * <li>{@code page <n>}: another page of the last answer.</li>
 * </ul>
 */
@EventBusSubscriber
public class CommandBlockLog extends CommandBase {

    public static final String PERMISSION = "cointcore.blocklog";
    public static final String TELEPORT_PERMISSION = "cointcore.blocklog.teleport";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
        .withZone(TimeUtil.getZone());
    private static final long DEFAULT_TIME_MS = 3 * 86_400_000L;
    private static final int DEFAULT_RADIUS = 5, MAX_RADIUS = 32;

    /** Admins with inspect on. */
    private static final Set<UUID> inspecting = new HashSet<>();
    /** The last question each asked, to page through. */
    private static final Map<String, Query> lastQuery = new HashMap<>();

    private interface Query {

        BlockLogStore.Lookup<List<BlockLogRow>> page(int page);
    }

    public CommandBlockLog() {
        PermissionAPI.registerNode(PERMISSION, DefaultPermissionLevel.OP, "Look up the block and container log");
        PermissionAPI.registerNode(TELEPORT_PERMISSION, DefaultPermissionLevel.OP, "Teleport to block log entries");
    }

    @Override
    public String getCommandName() {
        return "blocklog";
    }

    @Override
    public List<String> getCommandAliases() {
        return Collections.singletonList("bl");
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/blocklog inspect | near [радиус] [время] | player <ник> [время] | page <n>   (время: 30m, 12h, 3d)";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer player) {
            return PermissionAPI.hasPermission(player, PERMISSION);
        }
        return true;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "inspect", "near", "player", "page");
        }
        if (args.length == 2 && "player".equalsIgnoreCase(args[0])) {
            return getListOfStringsMatchingLastWord(
                args,
                MinecraftServer.getServer()
                    .getAllUsernames());
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender)) {
            throw new CommandException("commands.generic.permission");
        }
        if (!BlockLog.active()) {
            sender.addChatMessage(new ChatComponentText("§cЖурнал блоков выключен (blockLog.enabled)."));
            return;
        }
        if (args.length == 0) {
            throw new WrongUsageException(getCommandUsage(sender));
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "inspect", "i" -> inspect(sender);
            case "near" -> near(sender, args);
            case "player", "p" -> player(sender, args);
            case "page" -> {
                if (args.length < 2) {
                    throw new WrongUsageException("/blocklog page <n>");
                }
                Query query = lastQuery.get(sender.getCommandSenderName());
                if (query == null) {
                    sender.addChatMessage(new ChatComponentText("§cСначала задайте вопрос: inspect, near или player."));
                    return;
                }
                run(sender, query, Math.max(1, parseInt(sender, args[1])) - 1, null);
            }
            case "tp" -> {
                // From a click on a line: /bl tp <server> <dim> <x> <y> <z>
                if (args.length != 6 || !(sender instanceof EntityPlayerMP player)) {
                    throw new WrongUsageException("/blocklog tp <сервер> <измерение> <x> <y> <z>");
                }
                if (!PermissionAPI.hasPermission(player, TELEPORT_PERMISSION)) {
                    throw new CommandException("commands.generic.permission");
                }
                // A stopped cluster must not turn a remote destination into a local teleport.
                if (!args[1].equals(BlockLog.node()) && !ClusterService.isRunning()) {
                    throw new CommandException("Сеть серверов сейчас недоступна.");
                }
                ClusterCommands.teleport(
                    player,
                    args[1],
                    parseInt(sender, args[2]),
                    parseInt(sender, args[3]) + 0.5,
                    parseInt(sender, args[4]) + 1.0,
                    parseInt(sender, args[5]) + 0.5);
            }
            default -> throw new WrongUsageException(getCommandUsage(sender));
        }
    }

    private static void inspect(ICommandSender sender) {
        if (!(sender instanceof EntityPlayerMP player)) {
            sender.addChatMessage(new ChatComponentText("§cТолько для игроков."));
            return;
        }
        if (inspecting.remove(player.getUniqueID())) {
            sender.addChatMessage(new ChatComponentText("§eОсмотр выключен."));
        } else {
            inspecting.add(player.getUniqueID());
            sender.addChatMessage(
                new ChatComponentText(
                    "§aОсмотр включён: кликните по блоку, чтобы увидеть его историю. Выключить: /bl i"));
        }
    }

    private static void near(ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP player)) {
            sender.addChatMessage(new ChatComponentText("§cТолько для игроков."));
            return;
        }
        int radius = args.length > 1 ? parseIntBounded(sender, args[1], 1, MAX_RADIUS) : DEFAULT_RADIUS;
        long since = System.currentTimeMillis() - (args.length > 2 ? parseTime(args[2]) : DEFAULT_TIME_MS);
        String node = BlockLog.node();
        int dim = player.dimension;
        int x = (int) Math.floor(player.posX), y = (int) Math.floor(player.posY), z = (int) Math.floor(player.posZ);
        Query query = page -> c -> BlockLogStore.near(c, node, dim, x, y, z, radius, since, null, page);
        run(sender, query, 0, "§6--- Рядом (радиус " + radius + ") ---");
    }

    private static void player(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new WrongUsageException("/blocklog player <ник> [время]");
        }
        String name = args[1];
        long since = System.currentTimeMillis() - (args.length > 2 ? parseTime(args[2]) : DEFAULT_TIME_MS);
        Query query = page -> c -> BlockLogStore.byActor(c, name, since, page);
        run(sender, query, 0, "§6--- Действия " + name + " ---");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent event) {
        if (event.entityPlayer.worldObj.isRemote || !(event.entityPlayer instanceof EntityPlayerMP player)
            || event.action == PlayerInteractEvent.Action.RIGHT_CLICK_AIR
            || !inspecting.contains(player.getUniqueID())) {
            return;
        }
        event.setCanceled(true);
        if (!PermissionAPI.hasPermission(player, PERMISSION) || !BlockLog.active()) {
            inspecting.remove(player.getUniqueID());
            return;
        }
        String node = BlockLog.node();
        int dim = player.dimension, x = event.x, y = event.y, z = event.z;
        Query query = page -> c -> BlockLogStore.at(c, node, dim, x, y, z, page);
        run(player, query, 0, "§6--- Блок " + x + " " + y + " " + z + " ---");
    }

    private static void run(ICommandSender sender, Query query, int page, String header) {
        String key = sender.getCommandSenderName();
        lastQuery.put(key, query);
        boolean started = BlockLogStore.lookup(query.page(page), rows -> {
            if (sender instanceof EntityPlayerMP p && p.playerNetServerHandler == null) {
                return;
            }
            if (rows == null) {
                sender.addChatMessage(new ChatComponentText("§cБаза журнала сейчас недоступна."));
                return;
            }
            if (header != null || page > 0) {
                sender.addChatMessage(
                    new ChatComponentText(header != null ? header : "§6--- Страница " + (page + 1) + " ---"));
            }
            if (rows.isEmpty()) {
                sender.addChatMessage(new ChatComponentText("§7Ничего не найдено."));
                return;
            }
            for (int i = 0; i < Math.min(rows.size(), BlockLogStore.PAGE); i++) {
                sender.addChatMessage(line(rows.get(i)));
            }
            if (rows.size() > BlockLogStore.PAGE) {
                ChatComponentText more = new ChatComponentText("§7Дальше: /bl page " + (page + 2));
                more.setChatStyle(
                    new ChatStyle()
                        .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/bl page " + (page + 2))));
                sender.addChatMessage(more);
            }
        });
        if (!started) {
            sender.addChatMessage(new ChatComponentText("§cЖурнал блоков выключен."));
        }
    }

    private static ChatComponentText line(BlockLogRow row) {
        String actor = row.actor.startsWith("#") ? "§6" + row.actor.substring(1) : "§f" + row.actor;
        String verb = switch (row.action) {
            case BlockLogEntry.BREAK -> "§cсломал";
            case BlockLogEntry.PLACE -> "§aпоставил";
            case BlockLogEntry.EXPLODE -> "§6взорвал";
            case BlockLogEntry.TAKE -> "§eвзял";
            case BlockLogEntry.PUT -> "§bположил";
            default -> "§7?";
        };
        ChatComponentText line = new ChatComponentText(
            "§7" + DATE.format(Instant.ofEpochMilli(row.at)) + " " + actor + " " + verb + " ");
        ItemStack stack = stack(row.what, row.meta);
        String name = displayName(stack, row.what, row.meta);
        ChatComponentText item = new ChatComponentText("§f" + (row.amount > 1 ? row.amount + " × " : "") + name);
        if (stack != null) {
            item.setChatStyle(
                new ChatStyle().setChatHoverEvent(
                    new HoverEvent(
                        HoverEvent.Action.SHOW_ITEM,
                        new ChatComponentText(
                            stack.writeToNBT(new NBTTagCompound())
                                .toString()))));
        }
        line.appendSibling(item);
        boolean here = row.node.equals(BlockLog.node());
        ChatComponentText where = new ChatComponentText(
            " §8" + row.x
                + " "
                + row.y
                + " "
                + row.z
                + (row.dim != 0 ? " DIM" + row.dim : "")
                + (here ? "" : " [" + row.node + "]"));
        String tp = "/bl tp " + row.node + " " + row.dim + " " + row.x + " " + row.y + " " + row.z;
        where.setChatStyle(
            new ChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, tp))
                .setChatHoverEvent(
                    new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        new ChatComponentText(
                            here ? "Нажмите, чтобы перейти сюда"
                                : "Нажмите, чтобы перейти на сервер " + row.node + " к этому месту"))));
        line.appendSibling(where);
        return line;
    }

    private static ItemStack stack(String what, int meta) {
        if (what.startsWith("b:")) {
            return null;
        }
        Item item = GameData.getItemRegistry()
            .getObject(what);
        return item == null ? null : new ItemStack(item, 1, meta);
    }

    private static String displayName(ItemStack stack, String what, int meta) {
        if (stack != null) {
            try {
                String name = stack.getDisplayName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Named by its id below.
            }
        }
        return (what.startsWith("b:") ? what.substring(2) : what) + (meta != 0 ? ":" + meta : "");
    }

    /** 30m, 12h, 3d, or plain seconds. */
    private static long parseTime(String text) {
        String t = text.toLowerCase(Locale.ROOT)
            .trim();
        long unit = 1000;
        if (t.endsWith("m")) {
            unit = 60_000;
        } else if (t.endsWith("h")) {
            unit = 3_600_000;
        } else if (t.endsWith("d")) {
            unit = 86_400_000;
        } else if (t.endsWith("w")) {
            unit = 7 * 86_400_000L;
        }
        try {
            if (!t.matches("[0-9]+[smhdw]?")) {
                throw new NumberFormatException();
            }
            String digits = Character.isDigit(t.charAt(t.length() - 1)) ? t : t.substring(0, t.length() - 1);
            long value = Long.parseLong(digits);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return Math.multiplyExact(value, unit);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new WrongUsageException("Время: 30m, 12h, 3d");
        }
    }

    public static void reset() {
        inspecting.clear();
        lastQuery.clear();
    }
}
