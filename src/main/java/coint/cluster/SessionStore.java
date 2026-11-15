package coint.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** One account lease shared by all nodes. Every mutation is fenced by a random connection token. */
final class SessionStore {

    static final int LEASE_SECONDS = 120;

    private SessionStore() {}

    static void createTables(Statement s) throws SQLException {
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_login_sessions (uuid CHAR(36) NOT NULL PRIMARY KEY, "
                + "token CHAR(36) NOT NULL, node_id VARCHAR(64) NOT NULL, expires_at TIMESTAMP(3) NOT NULL) "
                + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    static boolean acquire(Connection c, String uuid, String token, String node) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
            "INSERT IGNORE INTO cluster_login_sessions "
                + "(uuid, token, node_id, expires_at) VALUES (?, ?, ?, TIMESTAMPADD(SECOND, 120, NOW(3)))")) {
            p.setString(1, uuid);
            p.setString(2, token);
            p.setString(3, node);
            if (p.executeUpdate() == 1) return true;
        }
        try (PreparedStatement p = c.prepareStatement(
            "UPDATE cluster_login_sessions SET token = ?, node_id = ?, "
                + "expires_at = TIMESTAMPADD(SECOND, 120, NOW(3)) WHERE uuid = ? AND expires_at <= NOW(3)")) {
            p.setString(1, token);
            p.setString(2, node);
            p.setString(3, uuid);
            return p.executeUpdate() == 1;
        }
    }

    static boolean renew(Connection c, String uuid, String token) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
            "UPDATE cluster_login_sessions SET expires_at = TIMESTAMPADD(SECOND, 120, NOW(3)) "
                + "WHERE uuid = ? AND token = ? AND expires_at > NOW(3)")) {
            p.setString(1, uuid);
            p.setString(2, token);
            return p.executeUpdate() == 1;
        }
    }

    static void release(Connection c, String uuid, String token) throws SQLException {
        try (PreparedStatement p = c
            .prepareStatement("DELETE FROM cluster_login_sessions WHERE uuid = ? AND token = ?")) {
            p.setString(1, uuid);
            p.setString(2, token);
            p.executeUpdate();
        }
    }
}
