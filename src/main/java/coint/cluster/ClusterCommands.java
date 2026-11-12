package coint.cluster;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.event.CommandEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.ServerUtilitiesPermissions;
import serverutils.command.CmdFly;
import serverutils.command.CmdGod;
import serverutils.command.CmdHeal;
import serverutils.command.CmdNickFor;
import serverutils.command.tp.CmdTplast;
import serverutils.data.ServerUtilitiesPlayerData;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * ServerUtilities commands aimed at a player on another server of the network.
 * <ul>
 * <li>/tpl: to a player online elsewhere, the admin goes to his server, next to him; to one who is offline, to the spot
 * and server he was last seen at.</li>
 * <li>/fly, /god, /heal, /nickfor: run on the server the player is on; the answer comes back.</li>
 * <li>A nickname set on one server is set on the others too.</li>
 * </ul>
 * The rights of the admin are checked on his own server, before any of this.
 *
 * <p>
 * Server thread only.
 */
@EventBusSubscriber
public final class ClusterCommands {

    /** What may be run here for an admin of another server. */
    private static final Set<String> REMOTE = new HashSet<>(Arrays.asList("fly", "god", "heal", "nickfor"));
    private static final long TPL_MS = 30_000;
    private static final String ASK = "ask:", GO = "go:", FAIL = "fail:";

    /** /tpl asked here, waiting for the target's server: asker → lower-case target. */
    private static final Map<UUID, Pending> pending = new HashMap<>();
    /** A nickname from another server being set: not to be sent back out. */
    private static boolean applyingNick;

    private ClusterCommands() {}

    /** SU's Universe lookup otherwise accepts the first substring match, even for destructive commands. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void exactTargets(CommandEvent event) {
        if (!(event.command instanceof CmdNickFor || event.command instanceof CmdHeal
            || event.command instanceof coint.commands.CommandHeal
            || event.command instanceof CmdTplast)) {
            return;
        }
        String[] args = event.parameters;
        if (event.command instanceof CmdTplast && args.length >= 3) {
            return; // Coordinates, not names.
        }
        for (int i = 0; i < Math.min(args.length, event.command instanceof CmdTplast ? 2 : 1); i++) {
            String name = args[i];
            // Keep explicit selectors and UUIDs, but reject implicit substring matching.
            if (name.startsWith("@") || name.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
                continue;
            }
            ForgePlayer local = Universe.loaded() ? Universe.get()
                .getPlayer(name) : null;
            boolean routed = !(event.command instanceof CmdTplast) || args.length == 1;
            if (local != null && !local.getName()
                .equalsIgnoreCase(name) && (!routed || ClusterService.remotePlayerName(name) == null)) {
                event.setCanceled(true);
                event.sender.addChatMessage(new ChatComponentText("§cУкажите полное имя игрока (Tab)."));
                return;
            }
        }
    }

    /** The registered Coint heal shadows SU heal; its zero-argument cooldown behavior stays in that command. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void targetedHeal(CommandEvent event) {
        if (!(event.command instanceof coint.commands.CommandHeal) || event.parameters.length == 0) {
            return;
        }
        event.setCanceled(true);
        if (event.parameters.length != 1) {
            event.sender.addChatMessage(new ChatComponentText("§c/heal [player]"));
            return;
        }
        if (event.sender instanceof EntityPlayerMP player
            && !PermissionAPI.hasPermission(player, ServerUtilitiesPermissions.HEAL_OTHER)) {
            event.sender.addChatMessage(new ChatComponentTranslation("commands.generic.permission"));
            return;
        }
        String target = event.parameters[0];
        if (remote(event.sender, "heal", event.parameters)) {
            return;
        }
        if (!isHere(target)) {
            event.sender.addChatMessage(new ChatComponentTranslation("commands.generic.player.notFound", target));
            return;
        }
        try {
            new CmdHeal().processCommand(event.sender, event.parameters);
        } catch (CommandException e) {
            event.sender.addChatMessage(new ChatComponentTranslation(e.getMessage(), e.getErrorOjbects()));
        }
    }

    static boolean isKind(String kind) {
        return ClusterService.KIND_TPL.equals(kind) || ClusterService.KIND_COMMAND.equals(kind)
            || ClusterService.KIND_COMMAND_REPLY.equals(kind)
            || ClusterService.KIND_NICK.equals(kind);
    }

    private static boolean isHere(String name) {
        return MinecraftServer.getServer()
            .getConfigurationManager()
            .func_152612_a(name) != null;
    }

    // ---------------------------------------------------------------- /tpl

    /**
     * /tpl {@code name} by a player, when that player is not here. True when it was taken care of here; false leaves it
     * to ServerUtilities. {@code command} and {@code args} are run as usual once the network says the player was last
     * seen here (or knows nothing of him).
     */
    public static boolean tpl(EntityPlayerMP player, String name, ICommand command, String[] args) {
        if (!ClusterService.isRunning() || isHere(name)
            || name.startsWith("@")
            || name.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            return false;
        }
        String target = ClusterService.remotePlayerName(name);
        if (target != null) {
            if (!ClusterService.publishTo(
                ClusterService.KIND_TPL,
                player.getCommandSenderName(),
                player.getCommandSenderName(),
                target,
                ASK + player.getUniqueID())) {
                return false;
            }
            pending.put(player.getUniqueID(), new Pending(target, System.currentTimeMillis() + TPL_MS));
            player.addChatMessage(
                new ChatComponentText(
                    "§eТелепорт к §b" + target + "§e на сервер " + ClusterService.remotePlayerServer(target) + "..."));
            return true;
        }
        String node = ClusterService.nodeId();
        String uuid = player.getUniqueID()
            .toString();
        int timeout = CointConfig.cluster.nodeTimeoutSeconds;
        boolean started = ClusterService.query(db -> {
            ClusterDatabase.LastPlace place = db.lastPlace(name, timeout);
            if (place != null && place.online && !place.nodeId.equals(node)) {
                db.setArrival(uuid, place.nodeId, place.dim, place.x, place.y, place.z);
            }
            return place;
        }, place -> {
            if (player.playerNetServerHandler == null) {
                return;
            }
            if (place == null || place.nodeId.equals(node)) {
                runHere(player, command, args);
            } else if (!place.online) {
                player.addChatMessage(
                    new ChatComponentText(
                        "§c" + place.name
                            + " последний раз был на сервере "
                            + place.nodeName
                            + ", а он сейчас выключен."));
            } else {
                player.addChatMessage(
                    new ChatComponentText(
                        "§e" + place.name
                            + " не в сети. Последний раз его видели на сервере "
                            + place.nodeName
                            + ": переход туда, на его место."));
                transfer(player, place.nodeId, place.name);
            }
        }, error -> runHere(player, command, args));
        return started;
    }

    /** ServerUtilities' own /tpl, as the command line would have run it. */
    private static void runHere(EntityPlayerMP player, ICommand command, String[] args) {
        if (player.playerNetServerHandler == null) {
            return;
        }
        try {
            ForgePlayer target = Universe.loaded() ? Universe.get()
                .getPlayer(args[0]) : null;
            if (target == null || !target.getName()
                .equalsIgnoreCase(args[0])) {
                throw new CommandException("commands.generic.player.notFound", args[0]);
            }
            command.processCommand(player, args);
        } catch (CommandException e) {
            ChatComponentTranslation message = new ChatComponentTranslation(e.getMessage(), e.getErrorOjbects());
            message.getChatStyle()
                .setColor(EnumChatFormatting.RED);
            player.addChatMessage(message);
        } catch (RuntimeException e) {
            CointCore.LOG.warn("[Cluster] /tpl failed", e);
        }
    }

    /**
     * Takes the player to a spot on any server of the network: here at once, elsewhere by moving him to that server,
     * which puts him on the spot when he arrives.
     */
    public static void teleport(EntityPlayerMP player, String nodeId, int dim, double x, double y, double z) {
        String localNode = ClusterService.isRunning() ? ClusterService.nodeId() : CointConfig.cluster.nodeId.trim();
        if (nodeId != null && !localNode.isEmpty() && nodeId.equals(localNode)) {
            if (net.minecraftforge.common.DimensionManager.isDimensionRegistered(dim)) {
                serverutils.lib.math.TeleporterDimPos.of(x, y, z, dim)
                    .teleport(player);
            } else {
                player.addChatMessage(new ChatComponentText("§cИзмерения " + dim + " здесь нет."));
            }
            return;
        }
        if (!ClusterService.isRunning() || nodeId == null || nodeId.isEmpty()) {
            player.addChatMessage(new ChatComponentText("§cСеть серверов сейчас недоступна."));
            return;
        }
        String uuid = player.getUniqueID()
            .toString();
        int timeout = CointConfig.cluster.nodeTimeoutSeconds;
        boolean started = ClusterService.query(db -> {
            for (ClusterDatabase.NodeRow node : db.nodes(timeout)) {
                if (node.id.equals(nodeId) && node.online) {
                    db.setArrival(uuid, nodeId, dim, x, y, z);
                    return node;
                }
            }
            return null;
        }, node -> {
            if (player.playerNetServerHandler == null) {
                return;
            }
            if (node != null) {
                player.addChatMessage(new ChatComponentText("§eПереход на сервер " + nodeId + ", на место..."));
                ClusterTransfers.send(player, node);
            } else {
                player.addChatMessage(new ChatComponentText("§cСервер " + nodeId + " сейчас недоступен."));
            }
        }, error -> player.addChatMessage(new ChatComponentText("§cСеть серверов сейчас недоступна.")));
        if (!started) {
            player.addChatMessage(new ChatComponentText("§cСеть серверов сейчас недоступна."));
        }
    }

    private static void transfer(EntityPlayerMP player, String nodeId, String targetName) {
        int timeout = CointConfig.cluster.nodeTimeoutSeconds;
        ClusterService.query(db -> db.nodes(timeout), nodes -> {
            if (player.playerNetServerHandler == null) {
                return;
            }
            for (ClusterDatabase.NodeRow node : nodes) {
                if (node.id.equals(nodeId) && node.online) {
                    ClusterTransfers.send(player, node);
                    return;
                }
            }
            player.addChatMessage(new ChatComponentText("§cСервер игрока " + targetName + " сейчас недоступен."));
        }, error -> player.addChatMessage(new ChatComponentText("§cСеть серверов сейчас недоступна.")));
    }

    private static void onTpl(EntityPlayerMP player, ClusterDatabase.ChatRow row) {
        String text = row.text == null ? "" : row.text;
        if (text.startsWith(ASK)) {
            // The admin is to appear where this player stands now.
            String askerUuid = text.substring(ASK.length());
            String node = ClusterService.nodeId();
            int dim = player.dimension;
            double x = player.posX, y = player.posY, z = player.posZ;
            String me = player.getCommandSenderName();
            ClusterService.query(db -> {
                db.setArrival(askerUuid, node, dim, x, y, z);
                return Boolean.TRUE;
            },
                ok -> ClusterService.publishTo(ClusterService.KIND_TPL, me, me, row.sender, GO + node),
                error -> ClusterService
                    .publishTo(ClusterService.KIND_TPL, me, me, row.sender, FAIL + "сеть серверов недоступна"));
            return;
        }
        Pending asked = pending.get(player.getUniqueID());
        if (asked == null || !asked.target.equalsIgnoreCase(row.sender)
            || asked.expiresAt < System.currentTimeMillis()) {
            return;
        }
        pending.remove(player.getUniqueID());
        if (text.startsWith(GO)) {
            transfer(player, text.substring(GO.length()), row.sender);
        } else {
            player.addChatMessage(
                new ChatComponentText(
                    "§cТелепорт к " + row.sender
                        + " не удался: "
                        + text.substring(Math.min(text.length(), FAIL.length()))));
        }
    }

    // ---------------------------------------------------------------- commands for players elsewhere

    /**
     * /{@code name} {@code args} (fly, god, heal, nickfor) by {@code sender}, when the player in {@code args[0]} is
     * not here but on another server: sent there. True when it was.
     */
    public static boolean remote(ICommandSender sender, String name, String[] args) {
        if (args.length == 0 || !REMOTE.contains(name) || !ClusterService.isRunning() || isHere(args[0])) {
            return false;
        }
        String target = ClusterService.remotePlayerName(args[0]);
        if (target == null) {
            return false;
        }
        String[] line = args.clone();
        line[0] = target;
        String text = name + " " + String.join(" ", line);
        if (!ClusterService.publishTo(
            ClusterService.KIND_COMMAND,
            sender.getCommandSenderName(),
            sender.getCommandSenderName(),
            target,
            text)) {
            return false;
        }
        CointCore.LOG.info(
            "[Cluster] {} ran /{} for {} on {}",
            sender.getCommandSenderName(),
            text,
            target,
            ClusterService.remotePlayerServer(target));
        return true;
    }

    private static void onCommand(ClusterDatabase.ChatRow row) {
        String text = row.text == null ? "" : row.text.trim();
        String name = text.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!REMOTE.contains(name)) {
            return;
        }
        RemoteCommandSender sender = new RemoteCommandSender(row.sender);
        CointCore.LOG.info("[Cluster] {} from {} runs /{}", row.sender, row.nodeName, text);
        try {
            String[] args = text.split(" +");
            // Bind the request to its exact envelope target. Never execute selectors or arbitrary registered commands.
            if (args.length < 2 || !args[1].equalsIgnoreCase(row.target)
                || !isHere(args[1])
                || ("nickfor".equals(name) ? args.length < 3 : args.length != 2)) {
                throw new CommandException("commands.generic.player.notFound", row.target);
            }
            ICommand command;
            switch (name) {
                case "fly":
                    command = new CmdFly();
                    break;
                case "god":
                    command = new CmdGod();
                    break;
                case "heal":
                    command = new CmdHeal();
                    break;
                case "nickfor":
                    command = new CmdNickFor();
                    break;
                default:
                    return;
            }
            command.processCommand(sender, Arrays.copyOfRange(args, 1, args.length));
        } catch (CommandException e) {
            sender.addChatMessage(new ChatComponentTranslation(e.getMessage(), e.getErrorOjbects()));
        } catch (RuntimeException e) {
            CointCore.LOG.warn("[Cluster] Command from another server failed", e);
            sender.addChatMessage(new ChatComponentText("§cКоманда не выполнилась: " + e));
        }
        StringBuilder reply = new StringBuilder();
        for (IChatComponent message : sender.replies) {
            if (reply.length() > 0) {
                reply.append('\n');
            }
            reply.append(IChatComponent.Serializer.func_150696_a(message));
        }
        if (reply.length() > 0) {
            ClusterService.publishTo(
                ClusterService.KIND_COMMAND_REPLY,
                ClusterService.displayName(),
                ClusterService.displayName(),
                row.sender,
                reply.toString());
        }
    }

    private static void onReply(EntityPlayerMP player, ClusterDatabase.ChatRow row) {
        for (String line : row.text.split("\n")) {
            IChatComponent message;
            try {
                message = IChatComponent.Serializer.func_150699_a(line);
            } catch (RuntimeException e) {
                continue;
            }
            if (message == null) {
                continue;
            }
            ChatComponentText prefixed = new ChatComponentText("§8[" + row.nodeName + "]§r ");
            prefixed.appendSibling(message);
            player.addChatMessage(prefixed);
        }
    }

    // ---------------------------------------------------------------- nicknames

    /** A nickname was set here: the other servers set it too. */
    public static void nickChanged(String player, String nickname) {
        if (applyingNick || !ClusterService.isRunning()) {
            return;
        }
        ClusterService.publishTo(ClusterService.KIND_NICK, player, player, null, nickname == null ? "" : nickname);
    }

    private static void onNick(ClusterDatabase.ChatRow row) {
        Universe universe = Universe.get();
        ForgePlayer player = universe == null ? null : universe.getPlayer(row.sender);
        if (player != null && !player.getName()
            .equalsIgnoreCase(row.sender)) {
            return; // Universe.getPlayer also accepts substrings; broadcasts must never rename a different player.
        }
        if (player == null) {
            return;
        }
        applyingNick = true;
        try {
            ServerUtilitiesPlayerData.get(player)
                .setNickname(row.text == null ? "" : row.text);
        } catch (RuntimeException e) {
            CointCore.LOG.warn("[Cluster] Could not set the nickname of {}: {}", row.sender, e.toString());
        } finally {
            applyingNick = false;
        }
    }

    // ----------------------------------------------------------------

    static void onMessage(ClusterDatabase.ChatRow row) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        if (ClusterService.KIND_NICK.equals(row.kind)) {
            onNick(row);
            return;
        }
        if (row.target == null) {
            return;
        }
        EntityPlayerMP player = server.getConfigurationManager()
            .func_152612_a(row.target);
        if (player == null) {
            return;
        }
        if (ClusterService.KIND_TPL.equals(row.kind)) {
            onTpl(player, row);
        } else if (ClusterService.KIND_COMMAND.equals(row.kind)) {
            onCommand(row);
        } else if (ClusterService.KIND_COMMAND_REPLY.equals(row.kind)) {
            onReply(player, row);
        }
    }

    static void onLogin(EntityPlayerMP player) {
        pending.remove(player.getUniqueID());
    }

    static void prune() {
        long now = System.currentTimeMillis();
        ClusterCompletion.tick(now);
        pending.values()
            .removeIf(p -> p.expiresAt < now);
    }

    static void reset() {
        pending.clear();
        ClusterCompletion.reset();
    }

    private static final class Pending {

        final String target;
        final long expiresAt;

        Pending(String target, long expiresAt) {
            this.target = target;
            this.expiresAt = expiresAt;
        }
    }
}
