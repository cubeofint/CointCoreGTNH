package coint.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import coint.poll.PollDefinition;

final class ClusterPollStore {

    private static final String LATEST = " WHERE m.meta_key = 'latest_poll'";
    private static final String FROM_LATEST = " FROM cluster_polls p JOIN cluster_meta m ON p.id = m.meta_value";
    private static final String NOW_MS = "CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)";
    private static final String SELECT_POLL = "SELECT p.definition, p.epoch, p.ends_ms - " + NOW_MS;

    private ClusterPollStore() {}

    static void createTables(Statement s) throws SQLException {
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_polls (id VARCHAR(48) NOT NULL PRIMARY KEY, "
                + "epoch VARCHAR(64) NOT NULL, definition TEXT NOT NULL, ends_ms BIGINT NOT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_poll_votes (poll_id VARCHAR(48) NOT NULL, "
                + "voter CHAR(36) NOT NULL, choice TINYINT NOT NULL, PRIMARY KEY (poll_id, voter), "
                + "FOREIGN KEY (poll_id) REFERENCES cluster_polls(id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.executeUpdate(
            "INSERT INTO cluster_meta (meta_key, meta_value) VALUES ('latest_poll', '') "
                + "ON DUPLICATE KEY UPDATE meta_key = VALUES(meta_key)");
    }

    enum Start {
        STARTED,
        ALREADY_OPEN,
        ID_USED
    }

    static Start start(Connection c, PollDefinition draft, String epoch, long durationMs) throws SQLException {
        c.setAutoCommit(false);
        try {
            // Every node locks the same row before choosing a new poll.
            try (Statement s = c.createStatement();
                ResultSet lock = s
                    .executeQuery("SELECT meta_value FROM cluster_meta WHERE meta_key = 'latest_poll' FOR UPDATE")) {
                if (!lock.next()) throw new SQLException("Missing latest_poll metadata");
            }
            Poll latest = find(c, null);
            if (latest != null && latest.timeLeft > 0) return Start.ALREADY_OPEN;
            if (find(c, draft.id) != null) return Start.ID_USED;

            try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO cluster_polls (id, epoch, definition, ends_ms) VALUES (?, ?, ?, " + NOW_MS + " + ?)")) {
                p.setString(1, draft.id);
                p.setString(2, epoch);
                p.setString(3, draft.toJson());
                p.setLong(4, durationMs);
                p.executeUpdate();
            }
            try (PreparedStatement p = c
                .prepareStatement("UPDATE cluster_meta SET meta_value = ? WHERE meta_key = 'latest_poll'")) {
                p.setString(1, draft.id);
                p.executeUpdate();
            }
            c.commit();
            return Start.STARTED;
        } catch (SQLException | RuntimeException error) {
            c.rollback();
            throw error;
        } finally {
            c.setAutoCommit(true);
        }
    }

    static boolean stop(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            return s.executeUpdate(
                "UPDATE cluster_polls p JOIN cluster_meta m ON p.id = m.meta_value" + " SET p.ends_ms = "
                    + NOW_MS
                    + LATEST
                    + " AND p.ends_ms > "
                    + NOW_MS)
                > 0;
        }
    }

    static Snapshot snapshot(Connection c, Collection<UUID> online) throws SQLException {
        Poll poll = find(c, null);
        if (poll == null || poll.timeLeft <= 0 || online.isEmpty()) {
            return new Snapshot(null, Collections.emptySet());
        }
        Set<UUID> voted = new HashSet<>();
        String placeholders = String.join(",", Collections.nCopies(online.size(), "?"));
        try (PreparedStatement p = c.prepareStatement(
            "SELECT voter FROM cluster_poll_votes WHERE poll_id = ? AND voter IN (" + placeholders + ")")) {
            p.setString(1, poll.definition.id);
            int i = 2;
            for (UUID id : online) p.setString(i++, id.toString());
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) voted.add(UUID.fromString(r.getString(1)));
            }
        }
        return new Snapshot(poll, voted);
    }

    static boolean vote(Connection c, String id, UUID voter, int choice) throws SQLException {
        // The deadline check and insert share a statement. The primary key keeps the first vote on retries.
        try (PreparedStatement p = c.prepareStatement(
            "INSERT INTO cluster_poll_votes (poll_id, voter, choice) SELECT p.id, ?, ?" + FROM_LATEST
                + LATEST
                + " AND p.id = ? AND p.ends_ms > "
                + NOW_MS
                + " ON DUPLICATE KEY UPDATE choice = cluster_poll_votes.choice")) {
            p.setString(1, voter.toString());
            p.setInt(2, choice);
            p.setString(3, id);
            p.executeUpdate();
        }
        try (PreparedStatement p = c
            .prepareStatement("SELECT 1 FROM cluster_poll_votes WHERE poll_id = ? AND voter = ?")) {
            p.setString(1, id);
            p.setString(2, voter.toString());
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }

    static Results results(Connection c, String id) throws SQLException {
        Poll poll = find(c, id);
        if (poll == null) return null;
        long[] counts = new long[poll.definition.answers.size()];
        try (PreparedStatement p = c
            .prepareStatement("SELECT choice, COUNT(*) FROM cluster_poll_votes WHERE poll_id = ? GROUP BY choice")) {
            p.setString(1, poll.definition.id);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    int choice = r.getInt(1);
                    if (choice < 0 || choice >= counts.length) throw new SQLException("Invalid recorded poll choice");
                    counts[choice] = r.getLong(2);
                }
            }
        }
        return new Results(poll, counts);
    }

    private static Poll find(Connection c, String id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
            SELECT_POLL + (id == null ? FROM_LATEST + LATEST : " FROM cluster_polls p WHERE p.id = ?"))) {
            if (id != null) p.setString(1, id);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? new Poll(PollDefinition.parse(r.getString(1)), r.getString(2), r.getLong(3)) : null;
            }
        }
    }

    static final class Poll {

        final PollDefinition definition;
        final String epoch;
        final long timeLeft;

        Poll(PollDefinition definition, String epoch, long timeLeft) {
            this.definition = definition;
            this.epoch = epoch;
            this.timeLeft = timeLeft;
        }
    }

    static final class Snapshot {

        final Poll poll;
        final Set<UUID> voted;

        Snapshot(Poll poll, Set<UUID> voted) {
            this.poll = poll;
            this.voted = voted;
        }
    }

    static final class Results {

        final Poll poll;
        final long[] counts;

        Results(Poll poll, long[] counts) {
            this.poll = poll;
            this.counts = counts;
        }
    }
}
