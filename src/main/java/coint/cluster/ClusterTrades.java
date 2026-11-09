package coint.cluster;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

import coint.trade.RemoteTrades;

/**
 * What the trade code ({@code coint.trade}) needs from the network: requests as messages to another server, and the
 * shared trade rows and deliveries. Callbacks run on the server thread; every call is false when the network is down
 * (nothing runs then).
 */
public final class ClusterTrades {

    public static final String KIND_REQUEST = "trade", KIND_OK = "tradeok", KIND_NO = "tradeno";

    private ClusterTrades() {}

    public static boolean running() {
        return ClusterService.isRunning();
    }

    public static String nodeId() {
        return ClusterService.nodeId();
    }

    /** The exact name of a player online on another server, or null. */
    public static String remoteName(String name) {
        return ClusterService.remotePlayerName(name);
    }

    /** The display name of the server a remote player is on, or null. */
    public static String remoteServer(String name) {
        return ClusterService.remotePlayerServer(name);
    }

    /** A message about a trade request to {@code target}, a player on another server. */
    public static boolean send(String kind, String sender, String target, String text) {
        return ClusterService.publishTo(kind, sender, sender, target, text);
    }

    static boolean isTradeKind(String kind) {
        return KIND_REQUEST.equals(kind) || KIND_OK.equals(kind) || KIND_NO.equals(kind);
    }

    static void onMessage(ClusterDatabase.ChatRow row) {
        if (row.target != null) {
            RemoteTrades.onMessage(row.kind, row.sender, row.target, row.text, row.nodeId, row.nodeName);
        }
    }

    public static boolean create(TradeRow row, Consumer<TradeRow> done, Consumer<String> failed) {
        return ClusterService.query(db -> TradeStore.create(db, row), done, failed);
    }

    /** {@code done} gets null when the row did not change since {@code version}. */
    public static boolean load(long id, long version, Consumer<TradeRow> done, Consumer<String> failed) {
        return ClusterService.query(db -> TradeStore.load(db, id, version), done, failed);
    }

    /** {@code done} gets the row as it is now (null: no such trade) and whether the change was made. */
    public static boolean change(long id, TradeMutation mutation, BiConsumer<TradeRow, Boolean> done,
        Consumer<String> failed) {
        return ClusterService.query(
            db -> TradeStore.change(db, id, mutation),
            result -> done.accept(result.row(), result.applied()),
            failed);
    }

    public static boolean claim(List<String> uuids, Predicate<byte[]> deliverable, Consumer<List<TradeDelivery>> done) {
        return ClusterService.query(db -> TradeStore.claim(db, uuids, deliverable), done, null);
    }

    /** Items for a player to get wherever he is next. */
    public static boolean deliver(TradeDelivery delivery, Consumer<String> failed) {
        return ClusterService.query(db -> {
            TradeStore.deliver(db, delivery);
            return Boolean.TRUE;
        }, ok -> {}, failed);
    }

    /** Calls off trades nobody touched for {@code seconds}. */
    public static boolean sweep(int seconds) {
        return ClusterService.query(db -> TradeStore.sweep(db, seconds), n -> {}, null);
    }
}
