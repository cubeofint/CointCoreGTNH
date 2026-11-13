package coint.cards;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.network.WorldTravelNetwork;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The card tables in play on this server, by place. Games live in memory only: a restart or breaking the table ends
 * them. Everything a player does comes here from {@link PacketCardAction}; every change is sent to everyone seated.
 */
@EventBusSubscriber
public final class CardTables {

    /** How far a player may walk away from his table before he leaves it. */
    private static final double MAX_DISTANCE_SQ = 8 * 8;

    static final int ACTION_OPEN = 0, ACTION_LEAVE = 1, ACTION_START = 2, ACTION_PLAY = 3, ACTION_DRAW = 4,
        ACTION_PASS = 5, ACTION_ADD_BOT = 6, ACTION_REMOVE_BOT = 7, ACTION_CALL = 8, ACTION_MODE = 9, ACTION_WATCH = 10;

    private static final Map<String, CardGame> games = new HashMap<>();
    private static int tick;

    private CardTables() {}

    private static String key(int dim, int x, int y, int z) {
        return dim + ":" + x + ":" + y + ":" + z;
    }

    /** A player used a table: he sits down (if there is a seat) and its window opens. */
    static void use(EntityPlayerMP player, int x, int y, int z) {
        int dim = player.worldObj.provider.dimensionId;
        CardGame game = games.computeIfAbsent(key(dim, x, y, z), k -> new CardGame(dim, x, y, z));
        CardGame other = gameOf(player.getUniqueID());
        if (other != null && other != game) {
            other.leave(player.getUniqueID());
            changed(other);
        }
        String error = game.join(player.getUniqueID(), player.getCommandSenderName());
        if (error != null && !game.players.contains(player.getUniqueID())) {
            if (game.isEmpty()) {
                games.remove(key(dim, x, y, z));
            }
            send(player, PacketCardState.closed(error));
            return;
        }
        changed(game);
        send(player, PacketCardState.of(game, player.getUniqueID(), true, null));
    }

    /** The table at this place is gone: its game ends. */
    static void removed(int dim, int x, int y, int z) {
        CardGame game = games.remove(key(dim, x, y, z));
        if (game == null) {
            return;
        }
        for (UUID uuid : game.viewers()) {
            EntityPlayerMP player = player(uuid);
            if (player != null) {
                send(player, PacketCardState.closed("cointcore.cards.error.tableGone"));
            }
        }
    }

    /** An action sent from a player's table window; run on the server thread. */
    static void act(EntityPlayerMP player, int action, int index, int suit) {
        SupplyServerTasks.run(() -> {
            CardGame game = gameOf(player.getUniqueID());
            if (game == null) {
                send(player, PacketCardState.closed(null));
                return;
            }
            UUID id = player.getUniqueID();
            String error = switch (action) {
                case ACTION_OPEN -> null;
                case ACTION_LEAVE -> {
                    game.leave(id);
                    send(player, PacketCardState.closed(null));
                    yield null;
                }
                case ACTION_START -> game.start(id);
                case ACTION_PLAY -> game.play(id, index, suit);
                case ACTION_DRAW -> game.draw(id);
                case ACTION_PASS -> game.pass(id);
                case ACTION_ADD_BOT -> game.addBot(id);
                case ACTION_REMOVE_BOT -> game.removeBot(id);
                case ACTION_CALL -> game.call(id);
                case ACTION_MODE -> game.mode(id);
                case ACTION_WATCH -> {
                    game.watch(id);
                    yield null;
                }
                default -> null;
            };
            if (action == ACTION_OPEN || error != null) {
                send(player, PacketCardState.of(game, id, action == ACTION_OPEN, error));
                return;
            }
            changed(game);
        });
    }

    private static CardGame gameOf(UUID player) {
        for (CardGame game : games.values()) {
            if (game.players.contains(player) || game.spectators.contains(player)) {
                return game;
            }
        }
        return null;
    }

    /** Sends the table to everyone seated; an empty table is dropped. */
    private static void changed(CardGame game) {
        showOnTable(game);
        if (game.isEmpty()) {
            games.values()
                .remove(game);
            return;
        }
        for (UUID uuid : game.viewers()) {
            EntityPlayerMP player = player(uuid);
            if (player != null) {
                send(player, PacketCardState.of(game, uuid, false, null));
            }
        }
    }

    /** Lays the round out on the table block for everyone around. */
    private static void showOnTable(CardGame game) {
        net.minecraft.world.WorldServer world = net.minecraftforge.common.DimensionManager.getWorld(game.dim);
        if (world == null || !world.blockExists(game.x, game.y, game.z)
            || !(world.getTileEntity(game.x, game.y, game.z) instanceof TileCardTable tile)) {
            return;
        }
        if (game.phase != CardGame.Phase.PLAYING) {
            tile.show(-1, new int[0]);
            return;
        }
        int[] counts = new int[game.players.size()];
        for (int i = 0; i < counts.length; i++) {
            List<Integer> hand = game.hands.get(game.players.get(i));
            counts[i] = hand == null ? 0 : hand.size();
        }
        tile.show(game.top(), counts);
    }

    private static void send(EntityPlayerMP player, PacketCardState state) {
        WorldTravelNetwork.CHANNEL.sendTo(state, player);
    }

    private static EntityPlayerMP player(UUID uuid) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return null;
        }
        for (Object o : server.getConfigurationManager().playerEntityList) {
            if (o instanceof EntityPlayerMP p && p.getUniqueID()
                .equals(uuid)) {
                return p;
            }
        }
        return null;
    }

    /** Once a second: turns that ran out of time, and players gone from their tables. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || games.isEmpty() || ++tick % 5 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (tick % 20 != 0) {
            // Bots move four times a second; the rest is checked once a second.
            for (CardGame game : new ArrayList<>(games.values())) {
                if (game.botMove(now) | game.checkCall(now)) {
                    changed(game);
                }
            }
            return;
        }
        for (Iterator<CardGame> it = new ArrayList<>(games.values()).iterator(); it.hasNext();) {
            CardGame game = it.next();
            boolean changed = game.timeout(now) | game.botMove(now) | game.checkCall(now);
            List<UUID> gone = new ArrayList<>();
            for (UUID uuid : game.spectators) if (player(uuid) == null) gone.add(uuid);
            for (UUID uuid : game.players) {
                if (game.bots.contains(uuid)) {
                    continue;
                }
                EntityPlayerMP player = player(uuid);
                if (player == null || player.worldObj.provider.dimensionId != game.dim
                    || player.getDistanceSq(game.x + 0.5, game.y + 0.5, game.z + 0.5) > MAX_DISTANCE_SQ) {
                    gone.add(uuid);
                }
            }
            for (UUID uuid : gone) {
                game.leave(uuid);
                EntityPlayerMP player = player(uuid);
                if (player != null) {
                    send(player, PacketCardState.closed("cointcore.cards.error.walkedAway"));
                }
                changed = true;
            }
            if (changed) {
                changed(game);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CardGame game = gameOf(event.player.getUniqueID());
        if (game != null) {
            game.leave(event.player.getUniqueID());
            changed(game);
        }
    }

    /** All games end with the server. */
    public static void clear() {
        games.clear();
    }

    public static void list(EntityPlayerMP viewer) {
        viewer.addChatMessage(new net.minecraft.util.ChatComponentText("§eИгры для наблюдения (на этом сервере):"));
        for (Map.Entry<String, CardGame> entry : games.entrySet()) {
            CardGame game = entry.getValue();
            net.minecraft.util.ChatComponentText line = new net.minecraft.util.ChatComponentText(
                "§a[Наблюдать] " + entry.getKey()
                    + " — "
                    + String.join(
                        ", ",
                        game.players.stream()
                            .map(game.names::get)
                            .collect(java.util.stream.Collectors.toList())));
            line.getChatStyle()
                .setChatClickEvent(
                    new net.minecraft.event.ClickEvent(
                        net.minecraft.event.ClickEvent.Action.RUN_COMMAND,
                        "/cards watch " + entry.getKey()));
            viewer.addChatMessage(line);
        }
    }

    public static void watch(EntityPlayerMP viewer, String key) {
        CardGame game = games.get(key);
        if (game == null) {
            list(viewer);
            return;
        }
        CardGame old = gameOf(viewer.getUniqueID());
        if (old != null && old != game) {
            old.leave(viewer.getUniqueID());
            changed(old);
        }
        game.watch(viewer.getUniqueID());
        changed(game);
        send(viewer, PacketCardState.of(game, viewer.getUniqueID(), true, null));
    }
}
