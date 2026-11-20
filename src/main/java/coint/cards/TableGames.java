package coint.cards;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.WorldServer;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.network.WorldTravelNetwork;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Table selection and lifecycle for ordinary-deck games; UNO keeps its existing engine and packets. */
@EventBusSubscriber
public final class TableGames {

    private static final Map<String, Session> games = new HashMap<>();
    private static int ticks;
    private static long nextSession;

    private TableGames() {}

    static String key(int dim, int x, int y, int z) {
        return dim + ":" + x + ":" + y + ":" + z;
    }

    private static final class Session {

        final int dim, x, y, z, kind;
        final long id = ++nextSession;
        final Set<UUID> viewers = new LinkedHashSet<>(), watchers = new LinkedHashSet<>();
        final SolitaireGame solitaire;
        final PokerGame poker;
        final BlackjackGame blackjack;
        UUID owner;

        Session(int kind, EntityPlayerMP owner, int x, int y, int z) {
            this.kind = kind;
            this.owner = owner.getUniqueID();
            dim = owner.dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            solitaire = kind == PacketTableState.SOLITAIRE ? new SolitaireGame() : null;
            poker = kind == PacketTableState.POKER ? new PokerGame() : null;
            blackjack = kind == PacketTableState.BLACKJACK ? new BlackjackGame() : null;
        }
    }

    public static void use(EntityPlayerMP player, int x, int y, int z) {
        int dim = player.dimension;
        Session session = games.get(key(dim, x, y, z));
        if (session != null) {
            join(session, player, false);
        } else if (CardTables.at(dim, x, y, z)) {
            CardTables.useUno(player, x, y, z);
        } else {
            PacketTableState state = new PacketTableState();
            state.dim = dim;
            state.x = x;
            state.y = y;
            state.z = z;
            state.open = true;
            send(player, state);
        }
    }

    private static boolean near(EntityPlayerMP player, int dim, int x, int y, int z) {
        return player.dimension == dim && player.getDistanceSq(x + 0.5, y + 0.5, z + 0.5) <= 64;
    }

    private static boolean exists(int dim, int x, int y, int z) {
        WorldServer world = net.minecraftforge.common.DimensionManager.getWorld(dim);
        return world != null && world.blockExists(x, y, z) && world.getTileEntity(x, y, z) instanceof TileCardTable;
    }

    static void act(EntityPlayerMP player, PacketTableAction action) {
        SupplyServerTasks.run(() -> apply(player, action));
    }

    private static void apply(EntityPlayerMP player, PacketTableAction action) {
        UUID id = player.getUniqueID();
        Session session = games.get(key(action.dim, action.x, action.y, action.z));
        if (action.action == PacketTableAction.SELECT) {
            if (!(action.a == 0 || action.a == PacketTableState.SOLITAIRE
                || action.a == PacketTableState.POKER
                || action.a == PacketTableState.BLACKJACK) || !near(player, action.dim, action.x, action.y, action.z)
                || !exists(action.dim, action.x, action.y, action.z)) return;
            if (session != null || CardTables.at(action.dim, action.x, action.y, action.z)) {
                use(player, action.x, action.y, action.z);
                return;
            }
            leave(id);
            CardTables.leaveCurrent(id);
            if (action.a == 0) {
                CardTables.useUno(player, action.x, action.y, action.z);
                return;
            }
            session = new Session(action.a, player, action.x, action.y, action.z);
            games.put(key(session.dim, session.x, session.y, session.z), session);
            join(session, player, false);
            return;
        }
        if (session == null || session.id != action.session || !session.viewers.contains(id)) return;
        if (action.action == PacketTableAction.LEAVE) {
            leave(id);
            closed(player, null);
            return;
        }
        if (!near(player, session.dim, session.x, session.y, session.z)
            || !exists(session.dim, session.x, session.y, session.z)) {
            leave(id);
            closed(player, null);
            return;
        }
        if (session.watchers.contains(id)) {
            send(player, state(session, id, false, "cointcore.table.error.spectator"));
            return;
        }
        String error = null;
        int revision = session.solitaire != null ? session.solitaire.revision
            : session.blackjack != null ? session.blackjack.revision : session.poker.revision;
        if (action.revision != revision) error = "cointcore.table.error.stale";
        else if (session.solitaire != null) {
            SolitaireGame game = session.solitaire;
            if (!id.equals(session.owner)) error = "cointcore.table.error.spectator";
            else error = switch (action.action) {
                case PacketTableAction.DEAL -> {
                    game.deal();
                    yield null;
                }
                case PacketTableAction.DRAW -> game.draw(action.revision);
                case PacketTableAction.MOVE -> game.move(action.a, action.b, action.c, action.revision);
                case PacketTableAction.UNDO -> game.undo(action.revision);
                default -> "cointcore.table.error.move";
            };
        } else if (session.blackjack != null) {
            error = !id.equals(session.owner) ? "cointcore.table.error.spectator"
                : session.blackjack.act(action.action, action.revision);
        } else {
            PokerGame game = session.poker;
            if (action.action == PacketTableAction.DEAL || action.action == PacketTableAction.ADD_BOT
                || action.action == PacketTableAction.REMOVE_BOT) {
                if (!id.equals(session.owner)) error = "cointcore.cards.error.notHost";
                else if (game.playing()) error = "cointcore.cards.error.running";
                else if (action.action == PacketTableAction.DEAL) error = game.start(System.currentTimeMillis());
                else if (action.action == PacketTableAction.ADD_BOT)
                    error = game.join(UUID.randomUUID(), "Bot " + (game.seats.size() + 1), true);
                else {
                    PokerGame.Seat bot = null;
                    for (PokerGame.Seat seat : game.seats) if (seat.bot) bot = seat;
                    if (bot != null) game.leave(bot.id);
                }
            } else error = game.act(id, action.action, action.a, action.revision, System.currentTimeMillis());
        }
        if (error != null) send(player, state(session, id, false, error));
        else broadcast(session);
    }

    private static void join(Session session, EntityPlayerMP player, boolean watch) {
        UUID id = player.getUniqueID();
        Session previous = of(id);
        if (previous != session) {
            leave(id);
            CardTables.leaveCurrent(id);
        }
        if (session.solitaire != null || session.blackjack != null) watch |= !id.equals(session.owner);
        else if (!watch && session.poker.join(id, player.getCommandSenderName(), false) != null) watch = true;
        if (watch) session.watchers.add(id);
        else session.watchers.remove(id);
        session.viewers.add(id);
        broadcast(session);
        send(player, state(session, id, true, null));
    }

    static PacketTableState pokerState(PokerGame game, UUID viewer, boolean watcher) {
        PacketTableState state = new PacketTableState();
        state.game = PacketTableState.POKER;
        state.phase = game.phase;
        state.revision = game.revision;
        state.turn = game.turn;
        state.dealer = game.dealer;
        state.pot = game.pot();
        state.msLeft = (int) Math.max(0, game.deadline - System.currentTimeMillis());
        state.result = game.result;
        PokerGame.Seat mine = watcher ? null : game.seat(viewer);
        state.you = mine == null ? -1 : game.seats.indexOf(mine);
        if (mine != null) {
            state.call = Math.min(mine.chips, game.toCall(mine));
            state.minRaise = game.bet + game.lastRaise;
            state.maxRaise = mine.street + mine.chips;
            state.canRaise = game.mayRaise(mine);
        }
        for (PokerGame.Seat seat : game.seats) {
            state.names.add(seat.name);
            state.chips.add(seat.chips);
            state.wagers.add(seat.street);
            state.folded.add(seat.folded);
            state.bots.add(seat.bot);
            state.hands.add(game.visibleHand(seat, watcher ? null : viewer));
        }
        state.board = new int[game.board.size()];
        for (int i = 0; i < state.board.length; i++) state.board[i] = game.board.get(i);
        return state;
    }

    static PacketTableState blackjackState(BlackjackGame game, String name) {
        PacketTableState state = new PacketTableState();
        state.game = PacketTableState.BLACKJACK;
        state.phase = game.phase;
        state.revision = game.revision;
        state.result = game.result;
        for (int i = 0; i < 2; i++) {
            state.names.add(i == 0 ? name : "Dealer");
            state.chips.add(i == 0 ? game.chips : 0);
            state.wagers.add(i == 0 ? BlackjackGame.BET : 0);
            state.folded.add(false);
            state.bots.add(i == 1);
            state.hands.add(game.visibleHand(i == 1));
        }
        return state;
    }

    private static PacketTableState state(Session session, UUID viewer, boolean open, String error) {
        PacketTableState state;
        if (session.poker != null) state = pokerState(session.poker, viewer, session.watchers.contains(viewer));
        else if (session.blackjack != null) {
            EntityPlayerMP owner = player(session.owner);
            state = blackjackState(session.blackjack, owner == null ? "" : owner.getCommandSenderName());
        } else {
            state = new PacketTableState();
            state.game = PacketTableState.SOLITAIRE;
            state.revision = session.solitaire.revision;
            state.moves = session.solitaire.moves;
            state.won = session.solitaire.won;
            state.canUndo = session.solitaire.canUndo();
            state.piles.addAll(session.solitaire.visiblePiles());
        }
        state.dim = session.dim;
        state.session = session.id;
        state.x = session.x;
        state.y = session.y;
        state.z = session.z;
        state.open = open;
        state.owner = viewer.equals(session.owner) && !session.watchers.contains(viewer);
        state.error = error == null ? "" : error;
        return state;
    }

    private static Session of(UUID id) {
        for (Session session : games.values()) if (session.viewers.contains(id)) return session;
        return null;
    }

    static void leave(UUID id) {
        Session session = of(id);
        if (session == null) return;
        session.viewers.remove(id);
        session.watchers.remove(id);
        if (session.poker != null) session.poker.leave(id);
        if (id.equals(session.owner)) {
            if (session.solitaire != null || session.blackjack != null) {
                end(session);
                return;
            }
            session.owner = null;
            for (UUID candidate : session.viewers) {
                if (!session.watchers.contains(candidate)) {
                    session.owner = candidate;
                    break;
                }
            }
            if (session.owner == null) {
                end(session);
                return;
            }
        }
        if (session.viewers.isEmpty()) end(session);
        else broadcast(session);
    }

    private static void end(Session session) {
        games.remove(key(session.dim, session.x, session.y, session.z));
        for (UUID id : session.viewers) {
            EntityPlayerMP player = player(id);
            if (player != null) closed(player, "cointcore.table.ended");
        }
    }

    static void removed(int dim, int x, int y, int z) {
        Session session = games.get(key(dim, x, y, z));
        if (session != null) end(session);
    }

    static void clear() {
        games.clear();
    }

    private static void broadcast(Session session) {
        for (UUID id : session.viewers) {
            EntityPlayerMP player = player(id);
            if (player != null) send(player, state(session, id, false, null));
        }
    }

    private static void send(EntityPlayerMP player, PacketTableState state) {
        WorldTravelNetwork.CHANNEL.sendTo(state, player);
    }

    private static void closed(EntityPlayerMP player, String error) {
        PacketTableState state = new PacketTableState();
        state.closed = true;
        state.error = error == null ? "" : error;
        send(player, state);
    }

    private static EntityPlayerMP player(UUID id) {
        return CardTables.player(id);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || games.isEmpty() || ++ticks % 5 != 0) return;
        long now = System.currentTimeMillis();
        for (Session session : new ArrayList<>(games.values())) {
            if (ticks % 20 == 0) {
                for (UUID id : new ArrayList<>(session.viewers)) {
                    EntityPlayerMP player = player(id);
                    if (player == null || !session.watchers.contains(id)
                        && !near(player, session.dim, session.x, session.y, session.z)) {
                        leave(id);
                        if (player != null) closed(player, "cointcore.cards.error.walkedAway");
                    }
                }
            }
            if (games.containsValue(session) && session.poker != null && session.poker.tick(now)) broadcast(session);
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        leave(event.player.getUniqueID());
    }

    static void list(EntityPlayerMP viewer) {
        for (Map.Entry<String, Session> entry : games.entrySet()) {
            ChatComponentText line = new ChatComponentText("§a[" + entry.getKey() + "] ");
            line.appendSibling(new ChatComponentTranslation("cointcore.table.game." + entry.getValue().kind));
            line.getChatStyle()
                .setChatClickEvent(
                    new net.minecraft.event.ClickEvent(
                        net.minecraft.event.ClickEvent.Action.RUN_COMMAND,
                        "/cards watch " + entry.getKey()));
            viewer.addChatMessage(line);
        }
    }

    static boolean watch(EntityPlayerMP viewer, String key) {
        Session session = games.get(key);
        if (session == null) return false;
        if (viewer.getUniqueID()
            .equals(session.owner)) {
            join(session, viewer, false);
            return true;
        }
        if (session.poker != null && session.poker.seat(viewer.getUniqueID()) != null)
            session.poker.leave(viewer.getUniqueID());
        join(session, viewer, true);
        return true;
    }
}
