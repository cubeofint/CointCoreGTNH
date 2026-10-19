package coint.cluster;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import coint.CointConfig;

/**
 * What the supply buffer blocks ask of the cluster database. Each call runs on the cluster worker and answers on the
 * server thread; false when the cluster is off.
 */
public final class SupplyLink {

    private SupplyLink() {}

    public static boolean enabled() {
        return ClusterService.isRunning();
    }

    public static String nodeId() {
        return ClusterService.nodeId();
    }

    public static boolean touchProvider(String link, String location, boolean aeOnline) {
        String node = ClusterService.nodeId();
        return ClusterService.query(db -> {
            SupplyStore.touchProvider(db, link, node, location, aeOnline);
            return Boolean.TRUE;
        }, ok -> {}, null);
    }

    public static boolean claim(String link, int limit, Consumer<List<SupplyStore.Op>> done, Consumer<String> failed) {
        String node = ClusterService.nodeId();
        int timeout = CointConfig.cluster.nodeTimeoutSeconds;
        return ClusterService.query(db -> SupplyStore.claim(db, link, node, limit, timeout), done, failed);
    }

    public static boolean applied(UUID op, long delivered, Runnable done) {
        String node = ClusterService.nodeId();
        return ClusterService.query(db -> {
            SupplyStore.applied(db, op, node, delivered);
            return Boolean.TRUE;
        }, ok -> done.run(), null);
    }

    public static boolean release(UUID op, String reason) {
        String node = ClusterService.nodeId();
        return ClusterService.query(db -> {
            SupplyStore.release(db, op, node, reason);
            return Boolean.TRUE;
        }, ok -> {}, null);
    }

    public static boolean failed(UUID op, String reason) {
        String node = ClusterService.nodeId();
        return ClusterService.query(db -> {
            SupplyStore.failed(db, op, node, reason);
            return Boolean.TRUE;
        }, ok -> {}, null);
    }

    public static boolean syncRemote(String link, Collection<SupplyStore.Op> ops, Collection<UUID> acknowledged,
        Consumer<SupplyStore.SyncResult> done, Consumer<String> failed) {
        String node = ClusterService.nodeId();
        int timeout = CointConfig.cluster.nodeTimeoutSeconds;
        return ClusterService
            .query(db -> SupplyStore.syncRemote(db, link, node, ops, acknowledged, timeout), done, failed);
    }

    public static boolean prune() {
        return ClusterService.query(db -> {
            SupplyStore.prune(db);
            return Boolean.TRUE;
        }, ok -> {}, null);
    }
}
