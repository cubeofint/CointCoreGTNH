package coint.trade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumChatFormatting;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.cluster.ClusterTrades;
import coint.cluster.TradeDelivery;
import coint.cluster.TradeMutation;
import coint.cluster.TradeRow;
import coint.network.WorldTravelNetwork;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Trades with a player on another server of the network. The trade lives in the shared database
 * ({@link TradeRow}): each server writes its own player's offer, "ready" and final press, and reads the partner's
 * twice a second. The window and its rules are the same as for a trade on one server.
 *
 * <p>
 * The exchange: once both pressed "Обменять", each server checks its own player (items still there, room for what
 * comes, epochs), takes his items and puts them in the trade row in one transaction ("committed"). The second to
 * commit finishes the trade: each side's items wait for the other player as a delivery, given on whatever server he
 * is on. A trade called off after a side committed gives that side his items back the same way. Server thread only.
 */
@EventBusSubscriber
public final class RemoteTrades {

    private static final long PARTNER_GONE_MS = 20_000;
    private static final int SWEEP_SECONDS = 600;

    /** Asker here → whom he asked on another server. */
    private static final Map<UUID, Outgoing> outgoing = new HashMap<>();
    /** Lower-case name of the asked here → lower-case asker → request from another server. */
    private static final Map<String, Map<String, Incoming>> incoming = new HashMap<>();
    /** Player here → his trade with a player elsewhere. */
    private static final Map<UUID, Remote> sessions = new HashMap<>();
    /** Trades that ended while it was unknown whether this side's items had reached the database. */
    private static final List<Remote> limbo = new ArrayList<>();
    private static int tick;
    private static boolean claiming;

    private RemoteTrades() {}

    // ---------------------------------------------------------------- requests

    static void request(EntityPlayerMP asker, String target) {
        if (!CointConfig.trade.enabled) {
            asker.addChatMessage(TradeManager.text("§cОбмен сейчас выключен."));
            return;
        }
        if (TradeManager.busy(asker.getUniqueID())) {
            asker.addChatMessage(TradeManager.text("§cВы уже в обмене."));
            return;
        }
        if (TradeManager.hasOutgoing(asker.getUniqueID()) || outgoing.containsKey(asker.getUniqueID())) {
            asker.addChatMessage(
                TradeManager.text("§cУ вас уже есть запрос на обмен. Отмените его: ")
                    .appendSibling(TradeManager.button("[Отменить]", EnumChatFormatting.RED, "/trade cancel")));
            return;
        }
        String name = asker.getCommandSenderName();
        if (!ClusterTrades.send(
            ClusterTrades.KIND_REQUEST,
            name,
            target,
            asker.getUniqueID()
                .toString())) {
            asker.addChatMessage(TradeManager.text("§cСеть серверов сейчас недоступна."));
            return;
        }
        int seconds = CointConfig.trade.requestSeconds;
        // The answer comes from another server: a little longer than the request lives there.
        outgoing.put(asker.getUniqueID(), new Outgoing(target, System.currentTimeMillis() + seconds * 1000L + 5000));
        asker.addChatMessage(
            TradeManager
                .text(
                    "§eЗапрос на обмен отправлен §b" + target
                        + "§e (сервер "
                        + ClusterTrades.remoteServer(target)
                        + "). Он действует "
                        + seconds
                        + " сек. ")
                .appendSibling(TradeManager.button("[Отменить]", EnumChatFormatting.RED, "/trade cancel")));
    }

    /** A message about a trade from another server, for {@code target} here. */
    public static void onMessage(String kind, String sender, String target, String text, String nodeId,
        String nodeName) {
        MinecraftServer server = MinecraftServer.getServer();
        EntityPlayerMP player = server == null ? null
            : server.getConfigurationManager()
                .func_152612_a(target);
        if (player == null) {
            if (ClusterTrades.KIND_OK.equals(kind)) {
                abortById(parseId(text), "§cОбмен отменён: " + target + " уже не в сети.");
            }
            return;
        }
        switch (kind) {
            case ClusterTrades.KIND_REQUEST -> onRequest(player, sender, text, nodeId, nodeName);
            case ClusterTrades.KIND_OK -> onAccepted(player, sender, parseId(text));
            case ClusterTrades.KIND_NO -> onRefused(player, sender, text);
            default -> {}
        }
    }

    private static void onRequest(EntityPlayerMP player, String asker, String uuid, String nodeId, String nodeName) {
        if (!CointConfig.trade.enabled) {
            return;
        }
        incoming.computeIfAbsent(key(player.getCommandSenderName()), k -> new HashMap<>())
            .put(
                key(asker),
                new Incoming(
                    asker,
                    uuid,
                    nodeId,
                    nodeName,
                    System.currentTimeMillis() + CointConfig.trade.requestSeconds * 1000L));
        player.addChatMessage(
            TradeManager.text("§b" + asker + "§e с сервера " + nodeName + " предлагает вам обмен. ")
                .appendSibling(TradeManager.button("[Принять]", EnumChatFormatting.GREEN, "/trade accept " + asker))
                .appendSibling(TradeManager.text(" "))
                .appendSibling(TradeManager.button("[Отклонить]", EnumChatFormatting.RED, "/trade deny " + asker)));
    }

    private static void onAccepted(EntityPlayerMP asker, String target, long id) {
        Outgoing asked = outgoing.get(asker.getUniqueID());
        if (asked == null || !asked.target.equalsIgnoreCase(target)
            || asked.expiresAt < System.currentTimeMillis()
            || TradeManager.busy(asker.getUniqueID())) {
            abortById(id, "§cОбмен отменён: запрос уже не действует.");
            return;
        }
        outgoing.remove(asker.getUniqueID());
        asker.addChatMessage(TradeManager.text("§b" + target + "§a принял обмен."));
        open(asker, id, 0, target);
    }

    private static void onRefused(EntityPlayerMP player, String sender, String what) {
        if ("cancel".equals(what)) {
            Map<String, Incoming> mine = incoming.get(key(player.getCommandSenderName()));
            if (mine != null && mine.remove(key(sender)) != null) {
                player.addChatMessage(TradeManager.text("§b" + sender + "§e отменил запрос на обмен."));
            }
            return;
        }
        Outgoing asked = outgoing.get(player.getUniqueID());
        if (asked != null && asked.target.equalsIgnoreCase(sender)) {
            outgoing.remove(player.getUniqueID());
            player.addChatMessage(TradeManager.text("§b" + sender + "§c отклонил обмен."));
        }
    }

    /** /trade accept for a request from another server: false when there is none. */
    static boolean accept(EntityPlayerMP player, String name) {
        Incoming request = take(player, name);
        if (request == null) {
            return false;
        }
        if (TradeManager.busy(player.getUniqueID())) {
            player.addChatMessage(TradeManager.text("§cВы уже в обмене."));
            return true;
        }
        TradeRow row = new TradeRow();
        row.uuid[0] = request.uuid;
        row.name[0] = request.name;
        row.node[0] = request.nodeId;
        row.uuid[1] = player.getUniqueID()
            .toString();
        row.name[1] = player.getCommandSenderName();
        row.node[1] = ClusterTrades.nodeId();
        row.epoch[1] = TradeLimits.priority(player);
        row.chat = "§7Обмен между серверами начат. Выложите предметы и нажмите «Готов».";
        UUID uuid = player.getUniqueID();
        boolean started = ClusterTrades.create(row, created -> {
            EntityPlayerMP now = TradeManager.online(uuid);
            if (now == null || TradeManager.busy(uuid)) {
                abortById(created.id, "§cОбмен отменён.");
                return;
            }
            ClusterTrades
                .send(ClusterTrades.KIND_OK, now.getCommandSenderName(), request.name, String.valueOf(created.id));
            open(now, created.id, 1, request.name);
        }, error -> player.addChatMessage(TradeManager.text("§cСеть серверов сейчас недоступна.")));
        if (!started) {
            player.addChatMessage(TradeManager.text("§cСеть серверов сейчас недоступна."));
        }
        return true;
    }

    /** /trade deny for a request from another server: false when there is none. */
    static boolean deny(EntityPlayerMP player, String name) {
        Incoming request = take(player, name);
        if (request == null) {
            return false;
        }
        ClusterTrades.send(ClusterTrades.KIND_NO, player.getCommandSenderName(), request.name, "deny");
        player.addChatMessage(TradeManager.text("§eВы отклонили обмен с §b" + request.name + "§e."));
        return true;
    }

    /** /trade cancel: his trade with a player elsewhere, or his request to one. False when neither. */
    static boolean cancel(EntityPlayerMP player) {
        Remote trade = sessions.get(player.getUniqueID());
        if (trade != null) {
            abort(trade, "§c" + player.getCommandSenderName() + " отменил обмен.");
            return true;
        }
        Outgoing asked = outgoing.remove(player.getUniqueID());
        if (asked == null) {
            return false;
        }
        ClusterTrades.send(ClusterTrades.KIND_NO, player.getCommandSenderName(), asked.target, "cancel");
        player.addChatMessage(TradeManager.text("§eЗапрос на обмен к §b" + asked.target + "§e отменён."));
        return true;
    }

    private static Incoming take(EntityPlayerMP player, String name) {
        Map<String, Incoming> mine = incoming.get(key(player.getCommandSenderName()));
        if (mine == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        Incoming found = null;
        for (Incoming request : mine.values()) {
            if (request.expiresAt < now || name != null && !request.name.equalsIgnoreCase(name)) {
                continue;
            }
            if (found == null || request.expiresAt > found.expiresAt) {
                found = request;
            }
        }
        if (found != null) {
            mine.remove(key(found.name));
        }
        return found;
    }

    static boolean hasOutgoing(EntityPlayerMP player) {
        return outgoing.containsKey(player.getUniqueID());
    }

    static boolean busy(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    static boolean has(EntityPlayerMP player) {
        return sessions.containsKey(player.getUniqueID());
    }

    // ---------------------------------------------------------------- the trade

    private static void open(EntityPlayerMP player, long id, int me, String partnerName) {
        Remote r = new Remote(id, me, new TradeSession.Side(player), partnerName);
        sessions.put(player.getUniqueID(), r);
        int epoch = TradeLimits.priority(player);
        boolean started = change(r, (row, deliveries) -> {
            if (!row.open()) {
                return false;
            }
            row.epoch[me] = epoch;
            row.notice[me] = "";
            return true;
        }, null);
        if (!started) {
            finish(r, "§cСеть серверов сейчас недоступна.", false);
        }
    }

    static void act(EntityPlayerMP player, int action, int a, int count, String text) {
        Remote r = sessions.get(player.getUniqueID());
        if (r == null) {
            return;
        }
        TradeSession.Side me = r.side;
        me.player = player;
        me.notice = "";
        TradeRow row = r.row;
        if (row == null) {
            return;
        }
        int my = r.me, other = 1 - r.me;
        boolean locked = r.committing || TradeRow.COMMITTED.equals(row.state[my]);
        switch (action) {
            case PacketTradeAction.ADD, PacketTradeAction.REMOVE -> {
                if (locked) {
                    me.notice = "§eВаши предметы уже переданы, ждём партнёра.";
                    send(r);
                    return;
                }
                boolean changed = action == PacketTradeAction.ADD
                    ? TradeManager.addOffer(me, r.partnerName, row.epoch[other], a, count)
                    : TradeManager.removeOffer(me, a, count);
                if (changed) {
                    writeOffer(r);
                } else {
                    send(r);
                }
            }
            case PacketTradeAction.READY -> {
                boolean wantReady = !row.ready[my];
                change(r, (live, deliveries) -> {
                    if (!live.open() || live.anyCommitted()) {
                        return false;
                    }
                    if (wantReady) {
                        live.ready[my] = true;
                        live.confirm[0] = live.confirm[1] = false;
                    } else {
                        live.resetDeal();
                    }
                    live.notice[0] = live.notice[1] = "";
                    return true;
                }, "§eОбмен уже подтверждается: отменить готовность нельзя.");
            }
            case PacketTradeAction.CONFIRM -> change(r, (live, deliveries) -> {
                if (!live.open() || !live.ready[0] || !live.ready[1] || !TradeRow.OPEN.equals(live.state[my])) {
                    return false;
                }
                live.confirm[my] = true;
                return true;
            }, null);
            case PacketTradeAction.CHAT -> {
                String line = (text == null ? "" : text).replaceAll("§.?", "")
                    .trim();
                if (line.length() > 120) {
                    line = line.substring(0, 120);
                }
                if (!line.isEmpty()) {
                    String said = "§b" + me.name + "§f: " + line;
                    change(r, (live, deliveries) -> {
                        live.say(said, TradeSession.MAX_CHAT);
                        return true;
                    }, null);
                }
            }
            case PacketTradeAction.CLOSE -> abort(r, "§c" + me.name + " закрыл окно обмена.");
            default -> {}
        }
    }

    /** Writes this side's offers as they are now; what is on the table changed, so nobody is ready any more. */
    private static void writeOffer(Remote r) {
        byte[] bytes = TradeItems.write(TradeManager.offered(r.side));
        int my = r.me;
        r.pendingWrites++;
        boolean started = ClusterTrades.change(r.id, (row, deliveries) -> {
            if (!row.open() || row.anyCommitted()) {
                return false;
            }
            row.offer[my] = bytes;
            row.resetDeal();
            row.notice[0] = row.notice[1] = "";
            return true;
        }, (row, applied) -> {
            r.pendingWrites--;
            r.offerRejected = !applied;
            if (!applied && row != null && row.open()) {
                r.side.notice = "§eОбмен уже подтверждается: менять предметы нельзя.";
            }
            apply(r, row);
        }, error -> {
            r.pendingWrites--;
            r.offerRejected = true;
            r.side.notice = "§cСеть серверов не ответила. Попробуйте ещё раз.";
            send(r);
        });
        if (!started) {
            r.pendingWrites--;
            r.offerRejected = true;
            finish(r, "§cОбмен отменён: сеть серверов недоступна.", false);
        }
    }

    /** A change to the row; {@code refused}: shown to this player when it was not made. False: network down. */
    private static boolean change(Remote r, TradeMutation mutation, String refused) {
        return ClusterTrades.change(r.id, mutation, (row, applied) -> {
            if (!applied && refused != null && row != null && row.open()) {
                r.side.notice = refused;
            }
            apply(r, row);
        }, error -> {
            r.side.notice = "§cСеть серверов не ответила. Попробуйте ещё раз.";
            send(r);
        });
    }

    /** The row as the database has it now. */
    private static void apply(Remote r, TradeRow row) {
        if (r.over) {
            return;
        }
        if (row == null) {
            finish(r, "§cОбмен отменён.", false);
            return;
        }
        if (r.row != null && row.version < r.row.version) {
            return;
        }
        r.row = row;
        int my = r.me, other = 1 - r.me;
        resolveUncertain(r, row);
        List<ItemStack> items = TradeItems.read(row.offer[other]);
        r.partnerUnknown = items == null;
        r.partnerItems = items == null ? Collections.emptyList() : items;
        r.side.ready = row.ready[my];
        r.side.confirmed = row.confirm[my];
        if (TradeRow.DONE.equals(row.status)) {
            finish(r, "§aОбмен с §b" + r.partnerName + "§a завершён.", true);
            return;
        }
        if (TradeRow.ABORTED.equals(row.status)) {
            String why = row.notice[my] == null || row.notice[my].isEmpty() ? "§cОбмен отменён." : row.notice[my];
            finish(r, why, false);
            return;
        }
        send(r);
        maybeCommit(r);
    }

    private static void send(Remote r) {
        TradeRow row = r.row;
        EntityPlayerMP player = TradeManager.online(r.side.uuid);
        if (row == null || player == null || r.over) {
            return;
        }
        r.side.player = player;
        int my = r.me, other = 1 - r.me;
        String notice = r.side.notice;
        if (notice.isEmpty()) {
            if (r.partnerUnknown) {
                r.side.notice = "§cЧасть предметов партнёра не существует на этом сервере.";
            } else if (TradeRow.COMMITTED.equals(row.state[my])) {
                r.side.notice = "§aВаши предметы переданы. Ждём " + r.partnerName + "...";
            } else if (row.notice[my] != null) {
                r.side.notice = row.notice[my];
            }
        }
        String server = ClusterTrades.remoteServer(r.partnerName);
        List<String> chat = row.chat == null || row.chat.isEmpty() ? Collections.emptyList()
            : Arrays.asList(row.chat.split("\n"));
        PacketTradeState state = TradeManager.state(
            r.side,
            r.partnerName + (server == null ? "" : " §7(" + server + ")"),
            row.ready[other],
            row.confirm[other],
            r.partnerItems,
            row.epoch[other],
            chat);
        r.side.notice = notice;
        WorldTravelNetwork.CHANNEL.sendTo(state, player);
    }

    // ---------------------------------------------------------------- the exchange

    private static void maybeCommit(Remote r) {
        TradeRow row = r.row;
        int my = r.me, other = 1 - r.me;
        if (r.over || r.committing || r.uncertain != null || r.pendingWrites > 0 || row == null || !row.open()) {
            return;
        }
        if (!row.ready[0] || !row.ready[1]
            || !row.confirm[0]
            || !row.confirm[1]
            || !TradeRow.OPEN.equals(row.state[my])) {
            return;
        }
        EntityPlayerMP player = TradeManager.online(r.side.uuid);
        if (player == null || player.isDead) {
            return;
        }
        TradeSession.Side me = r.side;
        me.player = player;
        if (TradeManager.check(me) || r.offerRejected) {
            me.notice = "§cПредметы в обмене изменились. Проверьте и подтвердите снова.";
            writeOffer(r);
            return;
        }
        if (r.partnerUnknown) {
            stop(
                r,
                "§cПредметы " + r.partnerName + " не существуют на этом сервере.",
                "§cВаши предметы не существуют на сервере " + me.name + ".");
            return;
        }
        for (TradeSession.Offer offer : me.offers) {
            String epoch = TradeLimits.blockReason(offer.kind, row.epoch[other]);
            if (epoch != null) {
                stop(
                    r,
                    "§cУберите " + offer.kind
                        .getDisplayName() + ": " + r.partnerName + " ещё не достиг эпохи " + epoch + ".",
                    "§cВам нельзя получить " + offer.kind.getDisplayName() + " до эпохи " + epoch + ".");
                return;
            }
        }
        int mine = TradeLimits.priority(player);
        for (ItemStack stack : r.partnerItems) {
            String epoch = TradeLimits.blockReason(stack, mine);
            if (epoch != null) {
                stop(
                    r,
                    "§cВам нельзя получить " + stack.getDisplayName() + " до эпохи " + epoch + ".",
                    "§cУберите " + stack.getDisplayName() + ": " + me.name + " ещё не достиг эпохи " + epoch + ".");
                return;
            }
        }
        if (!TradeManager.fits(me, r.partnerItems)) {
            stop(
                r,
                "§cНе хватает места в инвентаре. Освободите слоты и подтвердите снова.",
                "§cУ " + me.name + " не хватает места в инвентаре.");
            return;
        }
        long deal = row.deal;
        List<ItemStack> taken = TradeManager.offered(me);
        byte[] bytes = TradeItems.write(taken);
        TradeManager.take(me);
        player.inventoryContainer.detectAndSendChanges();
        r.committing = true;
        CointCore.LOG.info("[Trade] {} commits trade #{} with {}", me.name, r.id, r.partnerName);
        boolean started = ClusterTrades.change(r.id, (live, deliveries) -> {
            if (!live.open() || live.deal != deal
                || !live.confirm[0]
                || !live.confirm[1]
                || !TradeRow.OPEN.equals(live.state[my])) {
                return false;
            }
            live.state[my] = TradeRow.COMMITTED;
            live.offer[my] = bytes;
            if (TradeRow.COMMITTED.equals(live.state[other])) {
                live.status = TradeRow.DONE;
                deliveries.add(
                    new TradeDelivery(
                        live.uuid[my],
                        live.name[my],
                        live.id,
                        live.offer[other],
                        "обмен с " + live.name[other]));
                deliveries.add(
                    new TradeDelivery(live.uuid[other], live.name[other], live.id, bytes, "обмен с " + live.name[my]));
            }
            return true;
        }, (live, applied) -> {
            r.committing = false;
            if (!applied) {
                refund(r, taken);
            } else if (live != null && TradeRow.DONE.equals(live.status)) {
                log(live);
            }
            apply(r, live);
        }, error -> {
            r.committing = false;
            // Unknown whether it reached the database: the next look at the row tells.
            r.uncertain = taken;
        });
        if (!started) {
            r.committing = false;
            refund(r, taken);
            finish(r, "§cОбмен отменён: сеть серверов недоступна.", false);
        }
    }

    /**
     * This side cannot finish as things are: when nobody gave anything yet, both are ready no more and see why;
     * once the partner's items are in, the trade is called off and he gets them back.
     */
    private static void stop(Remote r, String toMe, String toPartner) {
        int my = r.me, other = 1 - r.me;
        change(r, (live, deliveries) -> {
            if (!live.open()) {
                return false;
            }
            if (live.anyCommitted()) {
                live.abort(null, deliveries);
                live.notice[my] = toMe + " §7Обмен отменён, предметы вернутся.";
                live.notice[other] = toPartner + " §7Обмен отменён, предметы вернутся.";
                return true;
            }
            live.resetDeal();
            live.notice[my] = toMe;
            live.notice[other] = toPartner;
            return true;
        }, null);
    }

    /** After an unanswered commit: the row now says whether this side's items reached it. */
    private static void resolveUncertain(Remote r, TradeRow row) {
        if (r.uncertain == null || r.committing) {
            return;
        }
        if (!TradeRow.COMMITTED.equals(row.state[r.me])) {
            refund(r, r.uncertain);
        }
        r.uncertain = null;
    }

    /** Items taken from this side's player that did not go into the trade: back to him, or to him wherever he is. */
    private static void refund(Remote r, List<ItemStack> stacks) {
        if (stacks.isEmpty()) {
            return;
        }
        EntityPlayerMP player = TradeManager.online(r.side.uuid);
        if (player != null) {
            TradeManager.give(player, stacks);
            player.inventoryContainer.detectAndSendChanges();
            return;
        }
        byte[] bytes = TradeItems.write(stacks);
        TradeDelivery back = new TradeDelivery(
            r.side.uuid.toString(),
            r.side.name,
            r.id,
            bytes,
            "возврат: обмен отменён");
        boolean started = ClusterTrades.deliver(
            back,
            error -> CointCore.LOG
                .error("[Trade] Could not return the items of {} (trade #{}): {}", r.side.name, r.id, error));
        if (!started) {
            CointCore.LOG
                .error("[Trade] Could not return the items of {} (trade #{}): network down", r.side.name, r.id);
        }
    }

    private static void log(TradeRow row) {
        List<ItemStack> fromA = TradeItems.read(row.offer[0]), fromB = TradeItems.read(row.offer[1]);
        TradeLog.log(
            row.name[0] + " [" + row.node[0] + "]",
            row.name[1] + " [" + row.node[1] + "]",
            fromA == null ? Collections.emptyList() : fromA,
            fromB == null ? Collections.emptyList() : fromB);
    }

    private static void abort(Remote r, String why) {
        if (r.over) {
            return;
        }
        boolean started = ClusterTrades.change(r.id, (row, deliveries) -> {
            if (!row.open()) {
                return false;
            }
            row.abort(why, deliveries);
            return true;
        }, (row, applied) -> {
            if (row == null || !applied && row.open()) {
                finish(r, why, false);
            } else {
                apply(r, row);
            }
        }, error -> finish(r, why, false));
        if (!started) {
            finish(r, why, false);
        }
    }

    private static void abortById(long id, String why) {
        if (id <= 0) {
            return;
        }
        ClusterTrades.change(id, (row, deliveries) -> {
            if (!row.open()) {
                return false;
            }
            row.abort(why, deliveries);
            return true;
        }, (row, applied) -> {}, null);
    }

    private static void finish(Remote r, String message, boolean done) {
        if (r.over) {
            return;
        }
        r.over = true;
        sessions.remove(r.side.uuid);
        if (r.uncertain != null) {
            limbo.add(r);
        }
        EntityPlayerMP player = TradeManager.online(r.side.uuid);
        if (player != null) {
            WorldTravelNetwork.CHANNEL.sendTo(PacketTradeState.closed(), player);
            player.addChatMessage(TradeManager.text(message + (done ? "" : " §7Ничего не передано.")));
        }
    }

    // ---------------------------------------------------------------- keeping up

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClusterTrades.running()) {
            return;
        }
        tick++;
        if (tick % 10 == 0) {
            expireRequests();
            for (Remote r : new ArrayList<>(sessions.values())) {
                poll(r);
            }
        }
        if (tick % 40 == 0) {
            claimDeliveries();
            settleLimbo();
        }
        if (tick % 6000 == 200) {
            ClusterTrades.sweep(SWEEP_SECONDS);
        }
    }

    private static void poll(Remote r) {
        if (r.over) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean partnerHere = ClusterTrades.remoteName(r.partnerName) != null;
        if (partnerHere) {
            r.partnerGoneSince = 0;
        } else if (r.partnerGoneSince == 0) {
            r.partnerGoneSince = now;
        } else if (now - r.partnerGoneSince > PARTNER_GONE_MS) {
            abort(r, "§cОбмен отменён: " + r.partnerName + " не в сети.");
            return;
        }
        TradeRow row = r.row;
        if (row != null && row.open()
            && TradeRow.OPEN.equals(row.state[r.me])
            && !r.committing
            && r.uncertain == null
            && r.pendingWrites == 0) {
            EntityPlayerMP player = TradeManager.online(r.side.uuid);
            if (player != null) {
                r.side.player = player;
                if (TradeManager.check(r.side)) {
                    writeOffer(r);
                    return;
                }
            }
        }
        if (r.loading) {
            return;
        }
        r.loading = true;
        boolean started = ClusterTrades.load(r.id, row == null ? -1 : row.version, fresh -> {
            r.loading = false;
            if (fresh != null) {
                apply(r, fresh);
            }
        }, error -> r.loading = false);
        if (!started) {
            r.loading = false;
        }
    }

    private static void expireRequests() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Outgoing>> it = outgoing.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<UUID, Outgoing> e = it.next();
            if (e.getValue().expiresAt < now) {
                it.remove();
                EntityPlayerMP asker = TradeManager.online(e.getKey());
                if (asker != null) {
                    asker.addChatMessage(
                        TradeManager.text("§eЗапрос на обмен к §b" + e.getValue().target + "§e истёк."));
                }
            }
        }
        MinecraftServer server = MinecraftServer.getServer();
        for (Iterator<Map.Entry<String, Map<String, Incoming>>> it = incoming.entrySet()
            .iterator(); it.hasNext();) {
            Map.Entry<String, Map<String, Incoming>> e = it.next();
            EntityPlayerMP target = server == null ? null
                : server.getConfigurationManager()
                    .func_152612_a(e.getKey());
            for (Iterator<Incoming> requests = e.getValue()
                .values()
                .iterator(); requests.hasNext();) {
                Incoming request = requests.next();
                if (request.expiresAt < now) {
                    requests.remove();
                    if (target != null) {
                        target
                            .addChatMessage(TradeManager.text("§eЗапрос на обмен от §b" + request.name + "§e истёк."));
                    }
                }
            }
            if (e.getValue()
                .isEmpty()) {
                it.remove();
            }
        }
    }

    /** Items waiting for the players here: from finished trades, or back from trades called off. */
    private static void claimDeliveries() {
        MinecraftServer server = MinecraftServer.getServer();
        if (claiming || server == null) {
            return;
        }
        List<String> uuids = new ArrayList<>();
        for (Object o : server.getConfigurationManager().playerEntityList) {
            uuids.add(
                ((EntityPlayerMP) o).getUniqueID()
                    .toString());
        }
        if (uuids.isEmpty()) {
            return;
        }
        claiming = true;
        boolean started = ClusterTrades.claim(uuids, TradeItems::deliverable, deliveries -> {
            claiming = false;
            for (TradeDelivery d : deliveries) {
                hand(d);
            }
        });
        if (!started) {
            claiming = false;
        }
    }

    private static void hand(TradeDelivery d) {
        EntityPlayerMP player = TradeManager.online(UUID.fromString(d.uuid));
        List<ItemStack> stacks = TradeItems.read(d.payload);
        if (player == null || stacks == null) {
            // Left meanwhile: it waits for him again.
            ClusterTrades
                .deliver(d, error -> CointCore.LOG.error("[Trade] Lost delivery #{} for {}: {}", d.id, d.name, error));
            return;
        }
        TradeManager.give(player, stacks);
        player.inventoryContainer.detectAndSendChanges();
        int count = 0;
        for (ItemStack stack : stacks) {
            count += stack.stackSize;
        }
        player.addChatMessage(TradeManager.text("§aПолучено " + count + " предм. (" + d.note + ")."));
        CointCore.LOG.info("[Trade] Delivered trade #{} items to {}: {} stacks", d.tradeId, d.name, stacks.size());
    }

    /** Trades that ended with an unanswered commit: once the row is readable, give back what did not go in. */
    private static void settleLimbo() {
        for (Remote r : new ArrayList<>(limbo)) {
            if (r.loading) {
                continue;
            }
            r.loading = true;
            ClusterTrades.load(r.id, -1, row -> {
                r.loading = false;
                limbo.remove(r);
                if (r.uncertain != null && (row == null || !TradeRow.COMMITTED.equals(row.state[r.me]))) {
                    refund(r, r.uncertain);
                }
                r.uncertain = null;
            }, error -> r.loading = false);
        }
    }

    /** The player left or died: his trade with a player elsewhere is called off. */
    static void onGone(EntityPlayerMP player, String what) {
        Remote r = sessions.get(player.getUniqueID());
        if (r != null) {
            abort(r, "§cОбмен отменён: " + player.getCommandSenderName() + " " + what + ".");
        }
        Outgoing asked = outgoing.remove(player.getUniqueID());
        if (asked != null) {
            ClusterTrades.send(ClusterTrades.KIND_NO, player.getCommandSenderName(), asked.target, "cancel");
        }
    }

    static void clear() {
        outgoing.clear();
        incoming.clear();
        sessions.clear();
        limbo.clear();
        claiming = false;
    }

    // ---------------------------------------------------------------- helpers

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static long parseId(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static final class Remote {

        final long id;
        /** 0: this player asked; 1: he accepted. */
        final int me;
        final TradeSession.Side side;
        final String partnerName;
        TradeRow row;
        List<ItemStack> partnerItems = Collections.emptyList();
        boolean partnerUnknown;
        boolean loading, committing, over, offerRejected;
        int pendingWrites;
        /** Taken for a commit whose answer never came. */
        List<ItemStack> uncertain;
        long partnerGoneSince;

        Remote(long id, int me, TradeSession.Side side, String partnerName) {
            this.id = id;
            this.me = me;
            this.side = side;
            this.partnerName = partnerName;
        }
    }

    private static final class Outgoing {

        final String target;
        final long expiresAt;

        Outgoing(String target, long expiresAt) {
            this.target = target;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Incoming {

        final String name, uuid, nodeId, nodeName;
        final long expiresAt;

        Incoming(String name, String uuid, String nodeId, String nodeName, long expiresAt) {
            this.name = name;
            this.uuid = uuid;
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.expiresAt = expiresAt;
        }
    }
}
