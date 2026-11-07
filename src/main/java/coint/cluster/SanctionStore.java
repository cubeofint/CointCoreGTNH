package coint.cluster;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * The bans, IP bans and mutes of the whole network: one row per player (or IP) and kind, the latest word on it. A
 * pardon keeps its row, marked removed, so a server that was down hears of it too. {@code updated_ms} is the database's
 * clock, which every server reads after its own last look.
 */
final class SanctionStore {

    /** The database's clock in milliseconds. */
    private static final String NOW_MS = "CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS SIGNED)";

    private SanctionStore() {}

    static void createTables(Statement s) throws SQLException {
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_sanctions (" + "kind VARCHAR(8) NOT NULL, "
                + "target VARCHAR(64) NOT NULL, "
                + "name VARCHAR(32) NOT NULL DEFAULT '', "
                + "issuer VARCHAR(64) NOT NULL DEFAULT '', "
                + "reason VARCHAR(512) NOT NULL DEFAULT '', "
                + "created_ms BIGINT NOT NULL, "
                + "expires_ms BIGINT NULL, "
                + "removed BOOLEAN NOT NULL DEFAULT FALSE, "
                + "node_id VARCHAR(64) NOT NULL, "
                + "updated_ms BIGINT NOT NULL, "
                + "PRIMARY KEY (kind, target), "
                + "INDEX idx_cluster_sanctions_updated (updated_ms)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /**
     * Stores a ban, pardon, mute or unmute made on this server. A pardon keeps what the row said of the ban. One that
     * is {@link ClusterSanctions.Row#onlyIfMissing} only goes in when the network knows nothing of that player yet.
     */
    static void write(ClusterDatabase db, ClusterSanctions.Row row, String nodeId) throws SQLException {
        String sql = "INSERT " + (row.onlyIfMissing ? "IGNORE " : "")
            + "INTO cluster_sanctions (kind, target, name, issuer, reason, created_ms, expires_ms, removed, node_id, updated_ms) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + NOW_MS
            + ")";
        if (!row.onlyIfMissing) {
            sql += " ON DUPLICATE KEY UPDATE " + keepIfRemoved("name")
                + ", "
                + keepIfRemoved("issuer")
                + ", "
                + keepIfRemoved("reason")
                + ", "
                + keepIfRemoved("created_ms")
                + ", "
                + keepIfRemoved("expires_ms")
                + ", removed = VALUES(removed), node_id = VALUES(node_id), updated_ms = VALUES(updated_ms)";
        }
        try (PreparedStatement p = db.connection()
            .prepareStatement(sql)) {
            p.setString(1, row.kind);
            p.setString(2, ClusterDatabase.clip(row.target, 64));
            p.setString(3, ClusterDatabase.clip(row.name, 32));
            p.setString(4, ClusterDatabase.clip(row.issuer, 64));
            p.setString(5, ClusterDatabase.clip(row.reason, 512));
            p.setLong(6, row.createdMs);
            if (row.expiresMs == null) {
                p.setNull(7, Types.BIGINT);
            } else {
                p.setLong(7, row.expiresMs);
            }
            p.setBoolean(8, row.removed);
            p.setString(9, nodeId);
            p.executeUpdate();
        }
    }

    private static String keepIfRemoved(String column) {
        return column + " = IF(VALUES(removed), " + column + ", VALUES(" + column + "))";
    }

    /** Every row changed after {@code afterMs}, oldest first. */
    static List<ClusterSanctions.Row> since(ClusterDatabase db, long afterMs) throws SQLException {
        List<ClusterSanctions.Row> result = new ArrayList<>();
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "SELECT kind, target, name, issuer, reason, created_ms, expires_ms, removed, node_id, updated_ms "
                    + "FROM cluster_sanctions WHERE updated_ms > ? ORDER BY updated_ms")) {
            p.setLong(1, afterMs);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    long expires = r.getLong(7);
                    Long expiresMs = r.wasNull() ? null : expires;
                    ClusterSanctions.Row row = new ClusterSanctions.Row(
                        r.getString(1),
                        r.getString(2),
                        r.getString(3),
                        r.getString(4),
                        r.getString(5),
                        r.getLong(6),
                        expiresMs,
                        r.getBoolean(8),
                        false);
                    row.nodeId = r.getString(9);
                    row.updatedMs = r.getLong(10);
                    result.add(row);
                }
            }
        }
        return result;
    }

    /** Pardons and run-out bans nobody has touched for {@code days} days. */
    static void prune(ClusterDatabase db, int days) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "DELETE FROM cluster_sanctions WHERE (removed OR (expires_ms IS NOT NULL AND expires_ms < " + NOW_MS
                    + ")) AND updated_ms < "
                    + NOW_MS
                    + " - ? * 86400000")) {
            p.setInt(1, days);
            p.executeUpdate();
        }
    }
}
