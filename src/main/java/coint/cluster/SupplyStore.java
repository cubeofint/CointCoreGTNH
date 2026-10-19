package coint.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tables and statements of the cross-server supply buffer: what remote buffers send to a provider's ME network, and
 * what they ask of it.
 *
 * <p>
 * An operation goes PENDING (written by the remote, its id first saved in the remote's world) → CLAIMED (a provider
 * took it) → APPLIED (the provider's world was saved with it done) → CONSUMED (the remote saw the result and let it
 * go), or FAILED. Claims of a provider that stopped or restarted since go back to PENDING: what it did unsaved was
 * rolled back with its world. Every step can be repeated without doing anything twice.
 */
public final class SupplyStore {

    static final String PENDING = "PENDING", CLAIMED = "CLAIMED", APPLIED = "APPLIED", CONSUMED = "CONSUMED",
        FAILED = "FAILED";

    private SupplyStore() {}

    static void createTables(Statement s) throws SQLException {
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_supply_providers (" + "link_id VARCHAR(64) NOT NULL PRIMARY KEY, "
                + "node_id VARCHAR(64) NOT NULL, "
                + "location VARCHAR(96) NOT NULL, "
                + "ae_online BOOLEAN NOT NULL DEFAULT FALSE, "
                + "last_seen TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_supply_operations (" + "operation_id CHAR(36) NOT NULL PRIMARY KEY, "
                + "link_id VARCHAR(64) NOT NULL, "
                + "source_node VARCHAR(64) NOT NULL, "
                + "direction VARCHAR(16) NOT NULL, "
                + "resource_type VARCHAR(8) NOT NULL, "
                + "payload TEXT NOT NULL, "
                + "amount BIGINT NOT NULL, "
                + "delivered BIGINT NOT NULL DEFAULT 0, "
                + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING', "
                + "claimed_by VARCHAR(64) NULL, "
                + "claimed_at TIMESTAMP(3) NULL, "
                + "error_text VARCHAR(255) NULL, "
                + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "INDEX idx_supply_ops_link (link_id, status, created_at), "
                + "INDEX idx_supply_ops_updated (status, updated_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /** A provider says it is there, and whether its ME network is up. */
    static void touchProvider(ClusterDatabase db, String link, String node, String location, boolean aeOnline)
        throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "INSERT INTO cluster_supply_providers (link_id, node_id, location, ae_online, last_seen) "
                    + "VALUES (?, ?, ?, ?, NOW(3)) ON DUPLICATE KEY UPDATE node_id = VALUES(node_id), "
                    + "location = VALUES(location), ae_online = VALUES(ae_online), last_seen = NOW(3)")) {
            p.setString(1, link);
            p.setString(2, node);
            p.setString(3, location);
            p.setBoolean(4, aeOnline);
            p.executeUpdate();
        }
    }

    /**
     * Claims up to {@code limit} pending operations of a link for this provider, oldest first, after giving back the
     * claims of providers that are gone or have restarted since they claimed.
     */
    static List<Op> claim(ClusterDatabase db, String link, String node, int limit, int timeoutSeconds)
        throws SQLException {
        Connection c = db.connection();
        List<Op> claimed = new ArrayList<>();
        c.setAutoCommit(false);
        try {
            try (PreparedStatement p = c.prepareStatement(
                "UPDATE cluster_supply_operations o LEFT JOIN cluster_nodes n ON n.node_id = o.claimed_by "
                    + "SET o.status = 'PENDING', o.claimed_by = NULL, o.claimed_at = NULL, o.updated_at = NOW(3) "
                    + "WHERE o.link_id = ? AND o.status = 'CLAIMED' AND (n.node_id IS NULL OR n.stopped "
                    + "OR n.last_seen < NOW(3) - INTERVAL ? SECOND OR n.started_at > o.claimed_at)")) {
                p.setString(1, link);
                p.setInt(2, timeoutSeconds);
                p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement(
                "SELECT operation_id, direction, resource_type, payload, amount FROM cluster_supply_operations "
                    + "WHERE link_id = ? AND status = 'PENDING' ORDER BY created_at LIMIT ? FOR UPDATE")) {
                p.setString(1, link);
                p.setInt(2, limit);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        claimed.add(
                            new Op(
                                UUID.fromString(r.getString(1)),
                                r.getString(2),
                                r.getString(3),
                                r.getString(4),
                                r.getLong(5)));
                    }
                }
            }
            if (!claimed.isEmpty()) {
                try (PreparedStatement p = c.prepareStatement(
                    "UPDATE cluster_supply_operations SET status = 'CLAIMED', claimed_by = ?, claimed_at = NOW(3), "
                        + "updated_at = NOW(3) WHERE operation_id = ? AND status = 'PENDING'")) {
                    for (Op op : claimed) {
                        p.setString(1, node);
                        p.setString(2, op.id.toString());
                        p.addBatch();
                    }
                    p.executeBatch();
                }
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
        return claimed;
    }

    /** Done and saved by this provider. Repeating it is harmless. */
    static void applied(ClusterDatabase db, UUID op, String node, long delivered) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "UPDATE cluster_supply_operations SET status = 'APPLIED', delivered = ?, error_text = NULL, "
                    + "updated_at = NOW(3) WHERE operation_id = ? AND (status = 'PENDING' "
                    + "OR (status = 'CLAIMED' AND claimed_by = ?))")) {
            p.setLong(1, Math.max(0, delivered));
            p.setString(2, op.toString());
            p.setString(3, node);
            p.executeUpdate();
        }
    }

    /** Not done now (the ME network is full or away): back to pending, to be tried again. */
    static void release(ClusterDatabase db, UUID op, String node, String reason) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "UPDATE cluster_supply_operations SET status = 'PENDING', claimed_by = NULL, claimed_at = NULL, "
                    + "error_text = ?, updated_at = NOW(3) WHERE operation_id = ? AND status = 'CLAIMED' AND claimed_by = ?")) {
            p.setString(1, clip(reason));
            p.setString(2, op.toString());
            p.setString(3, node);
            p.executeUpdate();
        }
    }

    /** Can never be done (an item this server doesn't know): the remote takes its things back. */
    static void failed(ClusterDatabase db, UUID op, String node, String reason) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "UPDATE cluster_supply_operations SET status = 'FAILED', error_text = ?, updated_at = NOW(3) "
                    + "WHERE operation_id = ? AND status = 'CLAIMED' AND claimed_by = ?")) {
            p.setString(1, clip(reason));
            p.setString(2, op.toString());
            p.setString(3, node);
            p.executeUpdate();
        }
    }

    /**
     * A remote's round: lets go of the results it has taken in, writes its new operations (again, harmlessly, if
     * written before) and reads where each one stands.
     */
    static SyncResult syncRemote(ClusterDatabase db, String link, String node, Collection<Op> ops,
        Collection<UUID> acknowledged, int timeoutSeconds) throws SQLException {
        Connection c = db.connection();
        Map<UUID, Result> results = new HashMap<>();
        boolean providerOnline = false;
        c.setAutoCommit(false);
        try {
            if (!acknowledged.isEmpty()) {
                try (PreparedStatement p = c.prepareStatement(
                    "UPDATE cluster_supply_operations SET status = 'CONSUMED', updated_at = NOW(3) "
                        + "WHERE operation_id = ? AND source_node = ? AND status IN ('APPLIED', 'FAILED')")) {
                    for (UUID id : acknowledged) {
                        p.setString(1, id.toString());
                        p.setString(2, node);
                        p.addBatch();
                    }
                    p.executeBatch();
                }
            }
            if (!ops.isEmpty()) {
                try (PreparedStatement p = c.prepareStatement(
                    "INSERT IGNORE INTO cluster_supply_operations (operation_id, link_id, source_node, direction, "
                        + "resource_type, payload, amount) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (Op op : ops) {
                        p.setString(1, op.id.toString());
                        p.setString(2, link);
                        p.setString(3, node);
                        p.setString(4, op.direction);
                        p.setString(5, op.type);
                        p.setString(6, op.payload);
                        p.setLong(7, op.amount);
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                StringBuilder in = new StringBuilder();
                for (int i = 0; i < ops.size(); i++) {
                    in.append(i == 0 ? "?" : ",?");
                }
                try (PreparedStatement p = c.prepareStatement(
                    "SELECT operation_id, status, delivered, error_text FROM cluster_supply_operations "
                        + "WHERE operation_id IN ("
                        + in
                        + ")")) {
                    int i = 1;
                    for (Op op : ops) {
                        p.setString(i++, op.id.toString());
                    }
                    try (ResultSet r = p.executeQuery()) {
                        while (r.next()) {
                            results.put(
                                UUID.fromString(r.getString(1)),
                                new Result(r.getString(2), r.getLong(3), r.getString(4)));
                        }
                    }
                }
            }
            try (PreparedStatement p = c.prepareStatement(
                "SELECT ae_online AND last_seen > NOW(3) - INTERVAL ? SECOND FROM cluster_supply_providers WHERE link_id = ?")) {
                p.setInt(1, timeoutSeconds);
                p.setString(2, link);
                try (ResultSet r = p.executeQuery()) {
                    providerOnline = r.next() && r.getBoolean(1);
                }
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
        return new SyncResult(providerOnline, results);
    }

    /** Old finished operations: nothing will ask for them again. */
    static void prune(ClusterDatabase db) throws SQLException {
        try (Statement s = db.connection()
            .createStatement()) {
            s.executeUpdate(
                "DELETE FROM cluster_supply_operations WHERE status = 'CONSUMED' AND updated_at < NOW(3) - INTERVAL 7 DAY");
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 255 ? text.substring(0, 255) : text;
    }

    /** One operation as the remote wrote it. */
    public static final class Op {

        public final UUID id;
        public final String direction, type, payload;
        public final long amount;

        public Op(UUID id, String direction, String type, String payload, long amount) {
            this.id = id;
            this.direction = direction;
            this.type = type;
            this.payload = payload;
            this.amount = amount;
        }

        /** The same kind and way, another amount. */
        public Op withAmount(long newAmount) {
            return new Op(id, direction, type, payload, newAmount);
        }
    }

    public static final class Result {

        public final String status;
        public final long delivered;
        public final String error;

        Result(String status, long delivered, String error) {
            this.status = status;
            this.delivered = delivered;
            this.error = error;
        }

        public boolean done() {
            return APPLIED.equals(status) || CONSUMED.equals(status);
        }

        public boolean failed() {
            return FAILED.equals(status);
        }
    }

    public static final class SyncResult {

        public final boolean providerOnline;
        public final Map<UUID, Result> results;

        SyncResult(boolean providerOnline, Map<UUID, Result> results) {
            this.providerOnline = providerOnline;
            this.results = results;
        }
    }
}
