package coint.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Durable, atomic snapshots. All methods except DDL are called by the cluster worker only. */
public final class AccessStore {

    private AccessStore() {}

    private static Path baselinePath(String authority) {
        String key = UUID.nameUUIDFromBytes(authority.getBytes(StandardCharsets.UTF_8))
            .toString();
        return Paths.get("cointcore", "access-authority-" + key + ".json");
    }

    static boolean hasBaseline(String authority) throws SQLException {
        Path path = baselinePath(authority);
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try {
            AccessSnapshot snapshot = AccessSnapshot.JSON
                .fromJson(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), AccessSnapshot.class);
            snapshot.validate(authority);
            return true;
        } catch (IOException | RuntimeException e) {
            throw new SQLException("Invalid local access authority baseline", e);
        }
    }

    static void saveBaseline(String authority, String payload) throws SQLException {
        Path path = baselinePath(authority);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.write(temporary, payload.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SQLException("Cannot persist local access authority baseline", e);
        }
    }

    /** Called by the shared schema migration; deliberately does not migrate ClusterDatabase itself. */
    public static void createTables(Statement statement) throws SQLException {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_access ("
                + "authority_node VARCHAR(64) NOT NULL PRIMARY KEY, revision BIGINT NOT NULL, "
                + "payload MEDIUMTEXT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    static Row read(Connection connection, String authority) throws SQLException {
        try (PreparedStatement statement = connection
            .prepareStatement("SELECT revision, payload FROM cluster_access WHERE authority_node = ?")) {
            statement.setString(1, authority);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new Row(result.getLong(1), result.getString(2)) : null;
            }
        }
    }

    /** Compare-and-set also fences duplicate authority instances; a stale writer must first restore the DB. */
    static boolean write(Connection connection, String authority, String localNode, long revision, String payload)
        throws SQLException {
        if (!authority.equals(localNode)) {
            throw new SQLException("Only the configured access authority may publish access snapshots");
        }
        if (revision == 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                "INSERT IGNORE INTO cluster_access (authority_node, revision, payload) VALUES (?, 1, ?)")) {
                statement.setString(1, authority);
                statement.setString(2, payload);
                return statement.executeUpdate() == 1;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE cluster_access SET revision = revision + 1, payload = ? "
                + "WHERE authority_node = ? AND revision = ?")) {
            statement.setString(1, payload);
            statement.setString(2, authority);
            statement.setLong(3, revision);
            return statement.executeUpdate() == 1;
        }
    }

    static final class Row {

        final long revision;
        final String payload;

        Row(long revision, String payload) {
            this.revision = revision;
            this.payload = payload;
        }
    }
}
