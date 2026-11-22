package coint.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import coint.teamhistory.HistoryEvent;

/** Only accessed on the existing cluster SQL worker. */
final class TeamHistoryStore {

    static final int PAGE_SIZE = 12;

    static Map<String, String> homes(Connection connection) throws SQLException {
        Map<String, String> homes = new java.util.HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid,node_id FROM cluster_homes");
            ResultSet rows = statement.executeQuery()) {
            while (rows.next()) homes.put(rows.getString(1), rows.getString(2));
        }
        return homes;
    }

    static void createTables(Statement statement) throws SQLException {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_team_histories ("
                + "history_id CHAR(36) NOT NULL PRIMARY KEY, title VARCHAR(256) NOT NULL, since_ms BIGINT NOT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_team_history ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, event_uuid CHAR(36) NOT NULL UNIQUE, "
                + "history_id CHAR(36) NOT NULL, time_ms BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, "
                + "label VARCHAR(128) NOT NULL, actor VARCHAR(64) NOT NULL, node_id VARCHAR(64) NOT NULL, "
                + "target_node VARCHAR(64) NOT NULL, INDEX idx_team_history_page (history_id, time_ms, id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_team_history_members ("
                + "uuid CHAR(36) NOT NULL PRIMARY KEY, node_id VARCHAR(64) NOT NULL, history_id CHAR(36) NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    static final class Team {

        String history, title;
        long since;
        List<HistoryEvent> events = new ArrayList<>();
        serverutils.lib.data.ForgeTeam local;
    }

    static final class Member {

        final String uuid, node, history;

        Member(String uuid, String node, String history) {
            this.uuid = uuid;
            this.node = node;
            this.history = history;
        }

        String signature() {
            return node + ":" + history;
        }
    }

    static final class Batch {

        final List<Team> teams = new ArrayList<>();
        final List<Member> members = new ArrayList<>();
        final java.util.Set<String> savedMembers = new java.util.HashSet<>();

        boolean empty() {
            return teams.isEmpty() && members.isEmpty();
        }
    }

    static void save(Connection connection, Batch batch) throws SQLException {
        if (batch.empty()) return;
        batch.savedMembers.clear();
        connection.setAutoCommit(false);
        try {
            try (
                PreparedStatement teams = connection.prepareStatement(
                    "INSERT INTO cluster_team_histories (history_id, title, since_ms) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE title=VALUES(title)");
                PreparedStatement events = connection.prepareStatement(
                    "INSERT INTO cluster_team_history (event_uuid, history_id, time_ms, kind, label, actor, node_id, target_node) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE event_uuid=VALUES(event_uuid)");
                PreparedStatement members = connection.prepareStatement(
                    "INSERT INTO cluster_team_history_members (uuid, node_id, history_id) "
                        + "SELECT ?, ?, ? FROM cluster_homes WHERE uuid=? AND node_id=? "
                        + "ON DUPLICATE KEY UPDATE node_id=VALUES(node_id), history_id=VALUES(history_id)")) {
                for (Team team : batch.teams) {
                    teams.setString(1, team.history);
                    teams.setString(2, team.title);
                    teams.setLong(3, team.since);
                    teams.addBatch();
                    for (HistoryEvent event : team.events) {
                        events.setString(1, event.uuid);
                        events.setString(2, team.history);
                        events.setLong(3, event.time);
                        events.setString(4, event.kind);
                        events.setString(5, event.label);
                        events.setString(6, event.actor);
                        events.setString(7, event.node);
                        events.setString(8, event.target);
                        events.addBatch();
                    }
                }
                for (Member member : batch.members) {
                    members.setString(1, member.uuid);
                    members.setString(2, member.node);
                    members.setString(3, member.history);
                    members.setString(4, member.uuid);
                    members.setString(5, member.node);
                    members.addBatch();
                }
                teams.executeBatch();
                events.executeBatch();
                members.executeBatch();
            }
            // A home may change between the server snapshot and this transaction. Only cache confirmed writes;
            // skipped INSERT...SELECT entries must be retried after the home assignment becomes visible.
            if (!batch.members.isEmpty()) {
                String placeholders = String.join(",", java.util.Collections.nCopies(batch.members.size(), "?"));
                Map<String, String> expected = new java.util.HashMap<>();
                for (Member member : batch.members) expected.put(member.uuid, member.signature());
                try (PreparedStatement p = connection.prepareStatement(
                    "SELECT m.uuid,m.node_id,m.history_id FROM cluster_team_history_members m "
                        + "JOIN cluster_homes h ON h.uuid=m.uuid AND h.node_id=m.node_id WHERE m.uuid IN ("
                        + placeholders
                        + ")")) {
                    for (int i = 0; i < batch.members.size(); i++) p.setString(i + 1, batch.members.get(i).uuid);
                    try (ResultSet rows = p.executeQuery()) {
                        while (rows.next()) {
                            String uuid = rows.getString(1);
                            if ((rows.getString(2) + ":" + rows.getString(3)).equals(expected.get(uuid)))
                                batch.savedMembers.add(uuid);
                        }
                    }
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static PacketTeamHistory page(Connection connection, String uuid, long before) throws SQLException {
        PacketTeamHistory result = new PacketTeamHistory();
        String history;
        // The caller provides only its own authenticated UUID, never a team ID from the client.
        try (PreparedStatement p = connection.prepareStatement(
            "SELECT t.history_id,t.title,t.since_ms FROM cluster_team_history_members m "
                + "JOIN cluster_homes h ON h.uuid=m.uuid AND h.node_id=m.node_id "
                + "JOIN cluster_team_histories t ON t.history_id=m.history_id WHERE m.uuid=?")) {
            p.setString(1, uuid);
            try (ResultSet rows = p.executeQuery()) {
                if (!rows.next()) {
                    result.error = "cointcore.history.no_team";
                    return result;
                }
                history = rows.getString(1);
                result.title = rows.getString(2);
                result.since = rows.getLong(3);
            }
        }
        long beforeTime = Long.MAX_VALUE;
        if (before > 0) {
            try (PreparedStatement p = connection
                .prepareStatement("SELECT time_ms FROM cluster_team_history WHERE history_id=? AND id=?")) {
                p.setString(1, history);
                p.setLong(2, before);
                try (ResultSet rows = p.executeQuery()) {
                    if (!rows.next()) {
                        result.error = "cointcore.history.changed";
                        return result;
                    }
                    beforeTime = rows.getLong(1);
                }
            }
        }
        try (PreparedStatement p = connection.prepareStatement(
            "SELECT id,event_uuid,time_ms,kind,label,actor,node_id,target_node FROM cluster_team_history "
                + "WHERE history_id=? AND (time_ms<? OR (time_ms=? AND id<?)) ORDER BY time_ms DESC,id DESC LIMIT ?")) {
            p.setString(1, history);
            p.setLong(2, beforeTime);
            p.setLong(3, beforeTime);
            p.setLong(4, before == 0 ? Long.MAX_VALUE : before);
            p.setInt(5, PAGE_SIZE + 1);
            try (ResultSet rows = p.executeQuery()) {
                while (rows.next()) {
                    if (result.entries.size() == PAGE_SIZE) {
                        result.older = true;
                        break;
                    }
                    HistoryEvent event = new HistoryEvent();
                    event.row = rows.getLong(1);
                    event.uuid = rows.getString(2);
                    event.time = rows.getLong(3);
                    event.kind = rows.getString(4);
                    event.label = rows.getString(5);
                    event.actor = rows.getString(6);
                    event.node = rows.getString(7);
                    event.target = rows.getString(8);
                    result.entries.add(event);
                }
            }
        }
        return result;
    }
}
