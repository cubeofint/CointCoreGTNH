package coint.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The shared database of the server network. Used from the cluster worker thread only: one connection, opened again
 * when it breaks.
 */
final class ClusterDatabase {

    /** Bumped with every change of the tables; {@link #migrate} brings an older database up to it. */
    private static final int SCHEMA_VERSION = 3;
    private static final int VALID_CHECK_SECONDS = 2;

    private final String url, username, password;
    private Connection connection;

    ClusterDatabase(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    /** The connection, opened (again) if there is none or it broke. */
    Connection connection() throws SQLException {
        if (connection != null) {
            try {
                if (connection.isValid(VALID_CHECK_SECONDS)) {
                    return connection;
                }
            } catch (SQLException ignored) {
                // Opened again below.
            }
            close();
        }
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "15000");
        properties.setProperty("tcpKeepAlive", "true");
        properties.setProperty("characterEncoding", "UTF-8");
        // The driver is created directly: DriverManager does not see drivers loaded by the mod class loader.
        Connection opened = new com.mysql.cj.jdbc.Driver().connect(url, properties);
        if (opened == null) {
            throw new SQLException("Not a MySQL url: " + url);
        }
        opened.setAutoCommit(true);
        connection = opened;
        migrate(opened);
        return opened;
    }

    void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Nothing left to do with it.
            }
            connection = null;
        }
    }

    private static void migrate(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cluster_meta (meta_key VARCHAR(64) NOT NULL PRIMARY KEY, "
                    + "meta_value VARCHAR(255) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        int version = 0;
        try (PreparedStatement p = c.prepareStatement("SELECT meta_value FROM cluster_meta WHERE meta_key = ?")) {
            p.setString(1, "schema_version");
            try (ResultSet r = p.executeQuery()) {
                if (r.next()) {
                    version = Integer.parseInt(r.getString(1));
                }
            }
        }
        if (version >= SCHEMA_VERSION) {
            return;
        }
        try (Statement s = c.createStatement()) {
            if (version < 1) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cluster_nodes (" + "node_id VARCHAR(64) NOT NULL PRIMARY KEY, "
                        + "display_name VARCHAR(64) NOT NULL, "
                        + "address VARCHAR(128) NOT NULL, "
                        + "online_players INT NOT NULL DEFAULT 0, "
                        + "tps DOUBLE NOT NULL DEFAULT 0, "
                        + "started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                        + "last_seen TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                        + "stopped BOOLEAN NOT NULL DEFAULT FALSE"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cluster_players (" + "uuid CHAR(36) NOT NULL PRIMARY KEY, "
                        + "name VARCHAR(32) NOT NULL, "
                        + "node_id VARCHAR(64) NOT NULL, "
                        + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                        + "INDEX idx_cluster_players_node (node_id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cluster_chat (" + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "node_id VARCHAR(64) NOT NULL, "
                        + "kind VARCHAR(16) NOT NULL, "
                        + "sender VARCHAR(64) NOT NULL, "
                        + "sender_formatted VARCHAR(512) NOT NULL, "
                        + "text TEXT NOT NULL, "
                        + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                        + "INDEX idx_cluster_chat_created (created_at)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
            if (version < 2) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cluster_homes (" + "uuid CHAR(36) NOT NULL PRIMARY KEY, "
                        + "name VARCHAR(32) NOT NULL, "
                        + "node_id VARCHAR(64) NOT NULL, "
                        + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
            if (version < 3) {
                SupplyStore.createTables(s);
            }
        }
        try (PreparedStatement p = c.prepareStatement(
            "INSERT INTO cluster_meta (meta_key, meta_value) VALUES ('schema_version', ?) "
                + "ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value)")) {
            p.setString(1, String.valueOf(SCHEMA_VERSION));
            p.executeUpdate();
        }
    }

    /** Says this server is alive, and who plays on it now (its earlier list is replaced). */
    /** {@code first}: the first since this server started, which marks its start (claims older than it are void). */
    void heartbeat(String nodeId, String displayName, String address, double tps, List<PlayerRow> players,
        boolean first) throws SQLException {
        Connection c = connection();
        try (PreparedStatement p = c.prepareStatement(
            "INSERT INTO cluster_nodes (node_id, display_name, address, online_players, tps, started_at, last_seen, stopped) "
                + "VALUES (?, ?, ?, ?, ?, NOW(3), NOW(3), FALSE) ON DUPLICATE KEY UPDATE "
                + "display_name = VALUES(display_name), address = VALUES(address), "
                + "online_players = VALUES(online_players), tps = VALUES(tps), "
                + "started_at = IF(? OR stopped, NOW(3), started_at), last_seen = NOW(3), stopped = FALSE")) {
            p.setString(1, nodeId);
            p.setString(2, displayName);
            p.setString(3, address);
            p.setInt(4, players.size());
            p.setDouble(5, tps);
            p.setBoolean(6, first);
            p.executeUpdate();
        }
        c.setAutoCommit(false);
        try {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM cluster_players WHERE node_id = ?")) {
                p.setString(1, nodeId);
                p.executeUpdate();
            }
            if (!players.isEmpty()) {
                // A player seen here last is here now: a row left by another server is taken over.
                try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO cluster_players (uuid, name, node_id, updated_at) VALUES (?, ?, ?, NOW(3)) "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name), node_id = VALUES(node_id), updated_at = NOW(3)")) {
                    for (PlayerRow player : players) {
                        p.setString(1, player.uuid);
                        p.setString(2, player.name);
                        p.setString(3, nodeId);
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
    }

    /** This server going down: its players leave the list, and it shows as stopped at once. */
    void stopped(String nodeId) throws SQLException {
        Connection c = connection();
        try (PreparedStatement p = c.prepareStatement("DELETE FROM cluster_players WHERE node_id = ?")) {
            p.setString(1, nodeId);
            p.executeUpdate();
        }
        try (PreparedStatement p = c.prepareStatement("UPDATE cluster_nodes SET stopped = TRUE WHERE node_id = ?")) {
            p.setString(1, nodeId);
            p.executeUpdate();
        }
    }

    /** The players of the other servers that are alive. */
    List<RemotePlayer> remotePlayers(String nodeId, int timeoutSeconds) throws SQLException {
        List<RemotePlayer> result = new ArrayList<>();
        try (PreparedStatement p = connection().prepareStatement(
            "SELECT p.name, p.node_id, n.display_name FROM cluster_players p "
                + "JOIN cluster_nodes n ON n.node_id = p.node_id "
                + "WHERE p.node_id <> ? AND NOT n.stopped AND n.last_seen > NOW(3) - INTERVAL ? SECOND")) {
            p.setString(1, nodeId);
            p.setInt(2, timeoutSeconds);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(new RemotePlayer(r.getString(1), r.getString(2), r.getString(3)));
                }
            }
        }
        return result;
    }

    /** The newest chat line: where a server that just started begins to read. */
    long lastChatId() throws SQLException {
        try (Statement s = connection().createStatement();
            ResultSet r = s.executeQuery("SELECT COALESCE(MAX(id), 0) FROM cluster_chat")) {
            r.next();
            return r.getLong(1);
        }
    }

    void publishChat(String nodeId, String kind, String sender, String senderFormatted, String text)
        throws SQLException {
        try (PreparedStatement p = connection().prepareStatement(
            "INSERT INTO cluster_chat (node_id, kind, sender, sender_formatted, text) VALUES (?, ?, ?, ?, ?)")) {
            p.setString(1, nodeId);
            p.setString(2, kind);
            p.setString(3, clip(sender, 64));
            p.setString(4, clip(senderFormatted, 512));
            p.setString(5, clip(text, 4000));
            p.executeUpdate();
        }
    }

    /**
     * Chat lines after {@code afterId}, oldest first, with the display name of the server of each. Ids are handed out
     * before commit, so a line can show up after a higher one: the caller reads again from a little below its last id
     * and drops those it has already shown.
     */
    List<ChatRow> chatSince(String nodeId, long afterId, int limit) throws SQLException {
        List<ChatRow> result = new ArrayList<>();
        try (PreparedStatement p = connection().prepareStatement(
            "SELECT c.id, c.node_id, COALESCE(n.display_name, c.node_id), c.kind, c.sender, c.sender_formatted, c.text "
                + "FROM cluster_chat c LEFT JOIN cluster_nodes n ON n.node_id = c.node_id "
                + "WHERE c.id > ? ORDER BY c.id LIMIT ?")) {
            p.setLong(1, afterId);
            p.setInt(2, limit);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(
                        new ChatRow(
                            r.getLong(1),
                            r.getString(2),
                            r.getString(3),
                            r.getString(4),
                            r.getString(5),
                            r.getString(6),
                            r.getString(7),
                            nodeId.equals(r.getString(2))));
                }
            }
        }
        return result;
    }

    void pruneChat(int keepMinutes) throws SQLException {
        try (PreparedStatement p = connection()
            .prepareStatement("DELETE FROM cluster_chat WHERE created_at < NOW(3) - INTERVAL ? MINUTE")) {
            p.setInt(1, keepMinutes);
            p.executeUpdate();
        }
    }

    /**
     * The home server of a player. When he has none yet and {@code assignTo} is set, it becomes his home: the first
     * server that may be home and that he played on. Null when he has none and none was given.
     */
    String home(String uuid, String name, String assignTo) throws SQLException {
        Connection c = connection();
        if (assignTo != null) {
            try (PreparedStatement p = c.prepareStatement(
                "INSERT IGNORE INTO cluster_homes (uuid, name, node_id, updated_at) VALUES (?, ?, ?, NOW(3))")) {
                p.setString(1, uuid);
                p.setString(2, clip(name, 32));
                p.setString(3, assignTo);
                p.executeUpdate();
            }
        }
        try (PreparedStatement p = c.prepareStatement("SELECT node_id FROM cluster_homes WHERE uuid = ?")) {
            p.setString(1, uuid);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    /** Sets the home server of a player (by name, as last seen), returning his uuid, or null when never seen. */
    String setHome(String name, String nodeId) throws SQLException {
        Connection c = connection();
        String uuid = null;
        try (PreparedStatement p = c.prepareStatement(
            "SELECT uuid FROM cluster_homes WHERE name = ? UNION SELECT uuid FROM cluster_players WHERE name = ? LIMIT 1")) {
            p.setString(1, name);
            p.setString(2, name);
            try (ResultSet r = p.executeQuery()) {
                if (r.next()) {
                    uuid = r.getString(1);
                }
            }
        }
        if (uuid == null) {
            return null;
        }
        try (PreparedStatement p = c.prepareStatement(
            "INSERT INTO cluster_homes (uuid, name, node_id, updated_at) VALUES (?, ?, ?, NOW(3)) "
                + "ON DUPLICATE KEY UPDATE node_id = VALUES(node_id), updated_at = NOW(3)")) {
            p.setString(1, uuid);
            p.setString(2, clip(name, 32));
            p.setString(3, nodeId);
            p.executeUpdate();
        }
        return uuid;
    }

    /** All servers of the network, alive or not, by id. */
    List<NodeRow> nodes(int timeoutSeconds) throws SQLException {
        List<NodeRow> result = new ArrayList<>();
        try (PreparedStatement p = connection().prepareStatement(
            "SELECT node_id, display_name, address, online_players, tps, "
                + "(NOT stopped AND last_seen > NOW(3) - INTERVAL ? SECOND) FROM cluster_nodes ORDER BY node_id")) {
            p.setInt(1, timeoutSeconds);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(
                        new NodeRow(
                            r.getString(1),
                            r.getString(2),
                            r.getString(3),
                            r.getInt(4),
                            r.getDouble(5),
                            r.getBoolean(6)));
                }
            }
        }
        return result;
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    static final class PlayerRow {

        final String uuid, name;

        PlayerRow(String uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    static final class NodeRow {

        final String id, name, address;
        final int players;
        final double tps;
        final boolean online;

        NodeRow(String id, String name, String address, int players, double tps, boolean online) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.players = players;
            this.tps = tps;
            this.online = online;
        }
    }

    static final class RemotePlayer {

        final String name, nodeId, nodeName;

        RemotePlayer(String name, String nodeId, String nodeName) {
            this.name = name;
            this.nodeId = nodeId;
            this.nodeName = nodeName;
        }
    }

    static final class ChatRow {

        final long id;
        final String nodeId, nodeName, kind, sender, senderFormatted, text;
        /** Written by this server: only moves the read position on. */
        final boolean own;

        ChatRow(long id, String nodeId, String nodeName, String kind, String sender, String senderFormatted,
            String text, boolean own) {
            this.id = id;
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.kind = kind;
            this.sender = sender;
            this.senderFormatted = senderFormatted;
            this.text = text;
            this.own = own;
        }
    }
}
