package coint.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

/** Durable reset barriers. Worker thread only; no leases/timeouts may release a reset lock. */
final class PlayerResetStore {

    private PlayerResetStore() {}

    static void createTables(Statement s) throws SQLException {
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_player_resets ("
                + "id CHAR(36) NOT NULL PRIMARY KEY, uuid CHAR(36) NOT NULL, name VARCHAR(32) NOT NULL, "
                + "mode VARCHAR(16) NOT NULL, successor CHAR(36) NULL, home_node VARCHAR(64) NOT NULL, "
                + "old_home VARCHAR(64) NULL, state VARCHAR(16) NOT NULL DEFAULT 'PREPARE', "
                + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "INDEX idx_player_reset_uuid (uuid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_player_reset_accounts ("
                + "uuid CHAR(36) NOT NULL PRIMARY KEY, reset_id CHAR(36) NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_player_reset_nodes ("
                + "reset_id CHAR(36) NOT NULL, node_id VARCHAR(64) NOT NULL, phase VARCHAR(16) NOT NULL DEFAULT 'WAIT', "
                + "error VARCHAR(1024) NOT NULL DEFAULT '', retained MEDIUMTEXT NULL, "
                + "PRIMARY KEY (reset_id, node_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /** Never use fuzzy names, offline UUID guesses, or LIMIT 1 on a potentially ambiguous identity. */
    static GameProfile identity(Connection c, String input, GameProfile local) throws SQLException {
        UUID explicit = null;
        try {
            if (input.length() == 36) explicit = UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {}
        Map<UUID, String> matches = new LinkedHashMap<>();
        if (local != null) matches.put(local.getId(), local.getName());
        for (String table : new String[] { "cluster_seen", "cluster_players", "cluster_homes" }) {
            try (PreparedStatement p = c.prepareStatement(
                "SELECT uuid, name FROM " + table
                    + (explicit == null ? " WHERE LOWER(name) = LOWER(?)" : " WHERE uuid = ?"))) {
                p.setString(1, explicit == null ? input : explicit.toString());
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) matches.put(UUID.fromString(r.getString(1)), r.getString(2));
                }
            }
        }
        if (matches.size() != 1) throw new SQLException(
            matches.isEmpty() ? "Игрок не найден: " + input : "Неоднозначный ник; укажите UUID: " + input);
        Map.Entry<UUID, String> entry = matches.entrySet()
            .iterator()
            .next();
        if (!entry.getValue()
            .matches("[A-Za-z0-9_.]{1,32}")) throw new SQLException("Некорректное имя в записи игрока");
        return new GameProfile(entry.getKey(), entry.getValue());
    }

    private static void lockAccount(Connection c, String uuid) throws SQLException {
        execute(c, "INSERT IGNORE INTO cluster_player_reset_accounts (uuid) VALUES (?)", uuid);
        try (PreparedStatement p = c
            .prepareStatement("SELECT uuid FROM cluster_player_reset_accounts WHERE uuid = ? FOR UPDATE")) {
            p.setString(1, uuid);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new SQLException("Missing reset account fence");
            }
        }
    }

    /** Same permanent account row as request creation: admission cannot race the reset transaction. */
    static boolean acquireSession(Connection c, String uuid, String token, String node) throws SQLException {
        c.setAutoCommit(false);
        try {
            lockAccount(c, uuid);
            boolean allowed = true;
            try (PreparedStatement p = c.prepareStatement(
                "SELECT r.state, n.phase FROM cluster_player_resets r "
                    + "LEFT JOIN cluster_player_reset_nodes n ON n.reset_id = r.id AND n.node_id = ? WHERE r.uuid = ?")) {
                p.setString(1, node);
                p.setString(2, uuid);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) allowed &= "CANCELLED".equals(r.getString(1))
                        || ("DONE".equals(r.getString(1)) && "APPLIED".equals(r.getString(2)));
                }
            }
            boolean acquired = allowed && SessionStore.acquire(c, uuid, token, node);
            c.commit();
            return acquired;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    static Job request(Connection c, GameProfile target, String mode, GameProfile successor, String local, String home,
        String authority) throws SQLException {
        c.setAutoCommit(false);
        try {
            String uuid = target.getId()
                .toString();
            lockAccount(c, uuid);
            Job previous = latest(c, uuid);
            if (previous != null && !previous.state.equals("DONE") && !previous.state.equals("CANCELLED")) {
                c.commit();
                return previous;
            }
            requireSettled(c, uuid);
            Job job = new Job();
            job.id = UUID.randomUUID()
                .toString();
            job.uuid = uuid;
            job.name = target.getName();
            job.mode = mode;
            job.successor = successor == null ? null
                : successor.getId()
                    .toString();
            job.home = home;
            job.state = "PREPARE";
            try (PreparedStatement p = c.prepareStatement("SELECT node_id FROM cluster_homes WHERE uuid = ?")) {
                p.setString(1, uuid);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) job.oldHome = r.getString(1);
                }
            }
            if (job.oldHome == null) job.oldHome = local;
            Set<String> nodes = new LinkedHashSet<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT node_id FROM cluster_nodes")) {
                while (r.next()) nodes.add(r.getString(1));
            }
            nodes.add(local);
            if (mode.equals("all")) nodes.add(home);
            if (!authority.isEmpty() && (mode.equals("all") || mode.equals("data"))) nodes.add(authority);
            if (job.oldHome != null) nodes.add(job.oldHome);
            execute(
                c,
                "INSERT INTO cluster_player_resets (id, uuid, name, mode, successor, home_node, old_home) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                job.id,
                uuid,
                job.name,
                mode,
                job.successor,
                home,
                job.oldHome);
            execute(c, "UPDATE cluster_player_reset_accounts SET reset_id = ? WHERE uuid = ?", job.id, uuid);
            for (String node : nodes)
                execute(c, "INSERT INTO cluster_player_reset_nodes (reset_id, node_id) VALUES (?, ?)", job.id, node);
            c.commit();
            return job;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    static Job latest(Connection c, String uuid) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
            "SELECT r.* FROM cluster_player_reset_accounts a "
                + "JOIN cluster_player_resets r ON r.id = a.reset_id WHERE a.uuid = ?")) {
            p.setString(1, uuid);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? read(r) : null;
            }
        }
    }

    /** A new/returning node must also apply completed tombstones before accepting this account. */
    static List<Job> poll(Connection c, String node) throws SQLException {
        execute(
            c,
            "INSERT IGNORE INTO cluster_player_reset_nodes (reset_id, node_id) "
                + "SELECT r.id, ? FROM cluster_player_resets r "
                + "WHERE r.state <> 'CANCELLED'",
            node);
        List<Job> jobs = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
            "SELECT r.*, n.phase FROM cluster_player_resets r "
                + "JOIN cluster_player_reset_nodes n ON n.reset_id = r.id AND n.node_id = ? WHERE r.state <> 'CANCELLED' "
                + "ORDER BY r.created_at, r.id")) {
            p.setString(1, node);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Job job = read(r);
                    job.phase = r.getString("phase");
                    jobs.add(job);
                }
            }
        }
        for (Job job : jobs) {
            try (PreparedStatement p = c.prepareStatement(
                "SELECT node_id, retained FROM cluster_player_reset_nodes WHERE reset_id = ? AND retained IS NOT NULL")) {
                p.setString(1, job.id);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) job.retained.put(r.getString(1), r.getString(2));
                }
            }
        }
        return jobs;
    }

    static void report(Connection c, Job job, String node, String phase, String retained, String error)
        throws SQLException {
        execute(
            c,
            "UPDATE cluster_player_reset_nodes SET phase = ?, retained = COALESCE(?, retained), error = ? "
                + "WHERE reset_id = ? AND node_id = ?",
            phase,
            retained,
            ClusterDatabase.clip(error, 1024),
            job.id,
            node);
    }

    static void cancel(Connection c, String uuid) throws SQLException {
        // Serializes with the barrier transition: an APPLY request can never be cancelled halfway through.
        execute(
            c,
            "UPDATE cluster_player_resets r JOIN cluster_player_reset_accounts a ON a.reset_id = r.id "
                + "SET r.state = 'CANCELLED' WHERE a.uuid = ? AND r.state = 'PREPARE'",
            uuid);
    }

    static void advance(Connection c, Job job) throws SQLException {
        if (job.state.equals("PREPARE")) {
            // Preserve escrow: neither deleting nor auto-delivering somebody else's in-flight items is a reset.
            requireSettled(c, job.uuid);
            requireNoSession(c, job.uuid);
            execute(
                c,
                "UPDATE cluster_player_resets r SET state = 'APPLY' WHERE id = ? AND state = 'PREPARE' "
                    + "AND NOT EXISTS (SELECT 1 FROM cluster_player_reset_nodes n "
                    + "WHERE n.reset_id = r.id AND n.phase <> 'PREPARED')",
                job.id);
        }
        if (!job.state.equals("APPLY")) return;
        c.setAutoCommit(false);
        try {
            lockAccount(c, job.uuid);
            try (PreparedStatement p = c
                .prepareStatement("SELECT state FROM cluster_player_resets WHERE id = ? FOR UPDATE")) {
                p.setString(1, job.id);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next() || !r.getString(1)
                        .equals("APPLY")) {
                        c.commit();
                        return;
                    }
                }
            }
            try (PreparedStatement p = c.prepareStatement(
                "SELECT phase FROM cluster_player_reset_nodes WHERE reset_id = ? AND phase <> 'APPLIED' LIMIT 1")) {
                p.setString(1, job.id);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        c.rollback();
                        return;
                    }
                }
            }
            requireSettled(c, job.uuid);
            requireNoSession(c, job.uuid);
            if (job.mode.equals("all") || job.mode.equals("data")) {
                // Pending travel may otherwise restore a pre-reset arrival/home on the next login.
                for (String table : new String[] { "cluster_arrivals", "cluster_returns", "cluster_moves" })
                    execute(c, "DELETE FROM " + table + " WHERE uuid = ?", job.uuid);
                scrubAccess(c, job.uuid);
            }
            if (job.mode.equals("all")) execute(
                c,
                "INSERT INTO cluster_homes (uuid, name, node_id) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE name = VALUES(name), node_id = VALUES(node_id), updated_at = NOW(3)",
                job.uuid,
                job.name,
                job.home);
            execute(c, "UPDATE cluster_player_resets SET state = 'DONE' WHERE id = ? AND state = 'APPLY'", job.id);
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private static void scrubAccess(Connection c, String uuid) throws SQLException {
        try (Statement s = c.createStatement();
            ResultSet r = s.executeQuery("SELECT authority_node, revision, payload FROM cluster_access FOR UPDATE")) {
            while (r.next()) {
                AccessSnapshot snapshot = AccessSnapshot.JSON.fromJson(r.getString(3), AccessSnapshot.class);
                snapshot.players.remove(uuid);
                snapshot.parentExpiry.remove(uuid);
                if (snapshot.temporaryRanks != null) snapshot.temporaryRanks.removeIf(
                    e -> e.playerUuid.toString()
                        .equals(uuid));
                execute(
                    c,
                    "UPDATE cluster_access SET revision = revision + 1, payload = ? WHERE authority_node = ? AND revision = ?",
                    AccessSnapshot.JSON.toJson(snapshot),
                    r.getString(1),
                    r.getString(2));
            }
        }
    }

    static void requireSettled(Connection c, String uuid) throws SQLException {
        for (String sql : new String[] {
            "SELECT 1 FROM cluster_trades WHERE (a_uuid = ? OR b_uuid = ?) AND status = 'open' LIMIT 1",
            "SELECT 1 FROM cluster_trade_deliveries WHERE uuid = ? LIMIT 1",
            "SELECT 1 FROM cluster_me_ops o JOIN cluster_me_sessions s ON s.id = o.session_id "
                + "WHERE s.player_uuid = ? AND o.status <> 'CONSUMED' LIMIT 1" }) {
            try (PreparedStatement p = c.prepareStatement(sql)) {
                p.setString(1, uuid);
                if (sql.contains("b_uuid")) p.setString(2, uuid);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) throw new SQLException("Ожидается завершение trade/ME передач; данные не удалены");
                }
            }
        }
    }

    private static void requireNoSession(Connection c, String uuid) throws SQLException {
        try (PreparedStatement p = c
            .prepareStatement("SELECT 1 FROM cluster_login_sessions WHERE uuid = ? AND expires_at > NOW(3) LIMIT 1")) {
            p.setString(1, uuid);
            try (ResultSet r = p.executeQuery()) {
                if (r.next()) throw new SQLException("Ожидается завершение сессии/выхода игрока на всех узлах");
            }
        }
    }

    static String status(Connection c, String uuid) throws SQLException {
        Job job = latest(c, uuid);
        if (job == null) return "Запросов сброса нет.";
        StringBuilder text = new StringBuilder(job.id).append(" ")
            .append(job.mode)
            .append(" ")
            .append(job.state);
        try (PreparedStatement p = c.prepareStatement(
            "SELECT node_id, phase, error FROM cluster_player_reset_nodes WHERE reset_id = ? ORDER BY node_id")) {
            p.setString(1, job.id);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) text.append("\n")
                    .append(r.getString(1))
                    .append(": ")
                    .append(r.getString(2))
                    .append(
                        r.getString(3)
                            .isEmpty() ? "" : " — " + r.getString(3));
            }
        }
        return text.toString();
    }

    private static void execute(Connection c, String sql, String... values) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) p.setString(i + 1, values[i]);
            p.executeUpdate();
        }
    }

    private static Job read(ResultSet r) throws SQLException {
        Job job = new Job();
        job.id = r.getString("id");
        job.uuid = r.getString("uuid");
        job.name = r.getString("name");
        job.mode = r.getString("mode");
        job.successor = r.getString("successor");
        job.home = r.getString("home_node");
        job.oldHome = r.getString("old_home");
        job.state = r.getString("state");
        return job;
    }

    static final class Job {

        String id, uuid, name, mode, successor, home, oldHome, state, phase;
        String currentHome;
        final Map<String, String> retained = new LinkedHashMap<>();
    }
}
