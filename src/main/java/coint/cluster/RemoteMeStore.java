package coint.cluster;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Tables of the remote ME terminal: a player on one server looks into his ME network on another.
 *
 * <p>
 * A session is opened by the viewer's server with the serial of the wireless access point his terminal is linked to;
 * the server that has that access point claims it, writes what the network holds while the session is touched, and
 * carries out the takes and puts the viewer's server queues: PENDING → TAKEN (the provider is on it) → DONE (with
 * what came out or went in) → CONSUMED once the viewer's server has handed it to the player. An operation nobody took
 * in time is set DONE with nothing done by the viewer's server, so items put in come back; the status checks make
 * each step happen once.
 */
final class RemoteMeStore {

    static final String OPEN = "OPEN", CLOSED = "CLOSED";
    static final String EXTRACT = "EXTRACT", INSERT = "INSERT", CRAFT = "CRAFT";
    /** Plan a craft (the plan comes back in the data), then start a planned one on a chosen processor. */
    static final String PLAN = "PLAN", START = "START";
    /** Status of the crafting processors (amount: the one looked at, or -1) and an action on one (data). */
    static final String CPUS = "CPUS", CPU_ACT = "CPUACT";
    /** Results of a craft order. */
    static final long CRAFT_FAILED = 0, CRAFT_STARTED = 1, CRAFT_NO_CPU = 2, CRAFT_NO_NETWORK = 3, CRAFT_PLAN_GONE = 4;
    static final String PENDING = "PENDING", TAKEN = "TAKEN", DONE = "DONE", CONSUMED = "CONSUMED";

    private RemoteMeStore() {}

    static void createTables(Statement s) throws SQLException {
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_me_sessions (" + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "serial BIGINT NOT NULL, "
                + "player_uuid CHAR(36) NOT NULL, "
                + "player_name VARCHAR(32) NOT NULL, "
                + "viewer_node VARCHAR(64) NOT NULL, "
                + "provider_node VARCHAR(64) NULL, "
                + "status VARCHAR(16) NOT NULL DEFAULT 'OPEN', "
                + "snapshot MEDIUMBLOB NULL, "
                + "snapshot_version BIGINT NOT NULL DEFAULT 0, "
                + "touched_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "INDEX idx_me_sessions_open (status, provider_node)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_me_ops (" + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "session_id BIGINT NOT NULL, "
                + "kind VARCHAR(8) NOT NULL, "
                + "item TEXT NOT NULL, "
                + "amount BIGINT NOT NULL, "
                + "result BIGINT NOT NULL DEFAULT 0, "
                + "status VARCHAR(10) NOT NULL DEFAULT 'PENDING', "
                + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "INDEX idx_me_ops_session (session_id, status)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /** Schema 9: operations carry data (a craft plan, the processor picked). */
    static void addData(Statement s) throws SQLException {
        s.executeUpdate("ALTER TABLE cluster_me_ops ADD COLUMN data MEDIUMBLOB NULL");
    }

    // ---- viewer's server

    static long openSession(ClusterDatabase db, long serial, String uuid, String name, String node)
        throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "INSERT INTO cluster_me_sessions (serial, player_uuid, player_name, viewer_node) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, serial);
            p.setString(2, uuid);
            p.setString(3, ClusterDatabase.clip(name, 32));
            p.setString(4, node);
            p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    /**
     * The state of a session for its viewer: who serves it, and the snapshot when newer than {@code knownVersion}. Also
     * marks it still in use.
     */
    static SessionView view(ClusterDatabase db, long id, long knownVersion) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement("UPDATE cluster_me_sessions SET touched_at = NOW(3) WHERE id = ?")) {
            p.setLong(1, id);
            p.executeUpdate();
        }
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "SELECT s.provider_node, COALESCE(n.display_name, s.provider_node), s.snapshot_version, "
                    + "IF(s.snapshot_version > ?, s.snapshot, NULL), TIMESTAMPDIFF(SECOND, s.created_at, NOW(3)) "
                    + "FROM cluster_me_sessions s LEFT JOIN cluster_nodes n ON n.node_id = s.provider_node WHERE s.id = ?")) {
            p.setLong(1, knownVersion);
            p.setLong(2, id);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                return new SessionView(r.getString(1), r.getString(2), r.getLong(3), r.getBytes(4), r.getLong(5));
            }
        }
    }

    static void closeSession(ClusterDatabase db, long id) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement("UPDATE cluster_me_sessions SET status = 'CLOSED', snapshot = NULL WHERE id = ?")) {
            p.setLong(1, id);
            p.executeUpdate();
        }
    }

    static long queueOp(ClusterDatabase db, long session, String kind, String item, long amount) throws SQLException {
        return queueOp(db, session, kind, item, amount, null);
    }

    static long queueOp(ClusterDatabase db, long session, String kind, String item, long amount, byte[] data)
        throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "INSERT INTO cluster_me_ops (session_id, kind, item, amount, data) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, session);
            p.setString(2, kind);
            p.setString(3, item);
            p.setLong(4, amount);
            p.setBytes(5, data);
            p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    /** Sessions of a player opened on this server that still have results to hand over (he left meanwhile). */
    static List<Long> sessionsWithResults(ClusterDatabase db, String uuid, String node) throws SQLException {
        List<Long> result = new ArrayList<>();
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "SELECT DISTINCT s.id FROM cluster_me_sessions s JOIN cluster_me_ops o ON o.session_id = s.id "
                    + "WHERE s.player_uuid = ? AND s.viewer_node = ? AND o.status IN ('PENDING', 'TAKEN', 'DONE')")) {
            p.setString(1, uuid);
            p.setString(2, node);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(r.getLong(1));
                }
            }
        }
        return result;
    }

    /**
     * Marks the carried-out operations of these sessions handed over and returns them: each comes back from here
     * once, so the caller hands it over (or gives it back with {@link #unconsume}).
     */
    static List<Op> consumeDone(ClusterDatabase db, List<Long> sessions) throws SQLException {
        List<Op> result = new ArrayList<>();
        for (Op op : ops(db, sessions, DONE)) {
            try (PreparedStatement p = db.connection()
                .prepareStatement("UPDATE cluster_me_ops SET status = 'CONSUMED' WHERE id = ? AND status = 'DONE'")) {
                p.setLong(1, op.id);
                if (p.executeUpdate() == 1) {
                    result.add(op);
                }
            }
        }
        return result;
    }

    /** An operation that could not be handed over after all (the player left): it waits for him again. */
    static void unconsume(ClusterDatabase db, long opId) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement("UPDATE cluster_me_ops SET status = 'DONE' WHERE id = ? AND status = 'CONSUMED'")) {
            p.setLong(1, opId);
            p.executeUpdate();
        }
    }

    /**
     * Operations of these sessions nobody took in time count as done with nothing done: put items come back. A craft
     * order the provider never answered (it restarted while planning) is given up after five minutes.
     */
    static void expire(ClusterDatabase db, List<Long> sessions, int seconds) throws SQLException {
        if (sessions.isEmpty()) {
            return;
        }
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "UPDATE cluster_me_ops SET status = 'DONE', result = 0 WHERE (status = 'PENDING' "
                    + "AND created_at < NOW(3) - INTERVAL ? SECOND OR status = 'TAKEN' AND kind IN ('CRAFT', 'PLAN') "
                    + "AND created_at < NOW(3) - INTERVAL 5 MINUTE) AND session_id IN ("
                    + marks(sessions.size())
                    + ")")) {
            p.setInt(1, seconds);
            for (int i = 0; i < sessions.size(); i++) {
                p.setLong(i + 2, sessions.get(i));
            }
            p.executeUpdate();
        }
    }

    /** How many operations of these sessions are not handed over yet. */
    static int unsettled(ClusterDatabase db, List<Long> sessions) throws SQLException {
        if (sessions.isEmpty()) {
            return 0;
        }
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "SELECT COUNT(*) FROM cluster_me_ops WHERE status IN ('PENDING', 'TAKEN', 'DONE') AND session_id IN ("
                    + marks(sessions.size())
                    + ")")) {
            for (int i = 0; i < sessions.size(); i++) {
                p.setLong(i + 1, sessions.get(i));
            }
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    private static String marks(int count) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < count; i++) {
            in.append(i == 0 ? "?" : ", ?");
        }
        return in.toString();
    }

    // ---- provider's server

    /** Fresh sessions nobody serves yet. */
    static List<long[]> unclaimed(ClusterDatabase db, int maxAgeSeconds) throws SQLException {
        List<long[]> result = new ArrayList<>();
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "SELECT id, serial FROM cluster_me_sessions WHERE status = 'OPEN' AND provider_node IS NULL "
                    + "AND created_at > NOW(3) - INTERVAL ? SECOND")) {
            p.setInt(1, maxAgeSeconds);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(new long[] { r.getLong(1), r.getLong(2) });
                }
            }
        }
        return result;
    }

    /** True when this server got the session (another may have been first). */
    static boolean claim(ClusterDatabase db, long id, String node) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "UPDATE cluster_me_sessions SET provider_node = ? WHERE id = ? AND provider_node IS NULL")) {
            p.setString(1, node);
            p.setLong(2, id);
            return p.executeUpdate() == 1;
        }
    }

    /**
     * The sessions this server serves that their viewer still looks at (live: 1) or that still have operations to
     * carry out (live: 0): id, serial, live.
     */
    static List<long[]> served(ClusterDatabase db, String node, int idleSeconds) throws SQLException {
        List<long[]> result = new ArrayList<>();
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "SELECT id, serial, (status = 'OPEN' AND touched_at > NOW(3) - INTERVAL ? SECOND) AS live "
                    + "FROM cluster_me_sessions WHERE provider_node = ? AND ((status = 'OPEN' "
                    + "AND touched_at > NOW(3) - INTERVAL ? SECOND) OR id IN "
                    + "(SELECT session_id FROM cluster_me_ops WHERE status = 'PENDING'))")) {
            p.setInt(1, idleSeconds);
            p.setString(2, node);
            p.setInt(3, idleSeconds);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(new long[] { r.getLong(1), r.getLong(2), r.getInt(3) });
                }
            }
        }
        return result;
    }

    /** Takes the waiting operations of these sessions for carrying out: each comes back from here once. */
    static List<Op> takeOps(ClusterDatabase db, List<Long> sessions) throws SQLException {
        List<Op> result = new ArrayList<>();
        for (Op op : ops(db, sessions, PENDING)) {
            try (PreparedStatement p = db.connection()
                .prepareStatement("UPDATE cluster_me_ops SET status = 'TAKEN' WHERE id = ? AND status = 'PENDING'")) {
                p.setLong(1, op.id);
                if (p.executeUpdate() == 1) {
                    result.add(op);
                }
            }
        }
        return result;
    }

    static void writeSnapshot(ClusterDatabase db, long id, byte[] snapshot) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "UPDATE cluster_me_sessions SET snapshot = ?, snapshot_version = snapshot_version + 1 WHERE id = ?")) {
            p.setBytes(1, snapshot);
            p.setLong(2, id);
            p.executeUpdate();
        }
    }

    static void done(ClusterDatabase db, long opId, long result) throws SQLException {
        done(db, opId, result, null);
    }

    static void done(ClusterDatabase db, long opId, long result, byte[] data) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "UPDATE cluster_me_ops SET status = 'DONE', result = ?, data = COALESCE(?, data) "
                    + "WHERE id = ? AND status = 'TAKEN'")) {
            p.setLong(1, result);
            p.setBytes(2, data);
            p.setLong(3, opId);
            p.executeUpdate();
        }
    }

    /** Closed sessions and handed-over operations an hour old. */
    static void prune(ClusterDatabase db) throws SQLException {
        try (Statement s = db.connection()
            .createStatement()) {
            s.executeUpdate(
                "DELETE FROM cluster_me_ops WHERE status = 'CONSUMED' AND created_at < NOW(3) - INTERVAL 1 HOUR");
            s.executeUpdate(
                "DELETE FROM cluster_me_sessions WHERE created_at < NOW(3) - INTERVAL 1 DAY "
                    + "AND id NOT IN (SELECT session_id FROM cluster_me_ops WHERE status <> 'CONSUMED')");
        }
    }

    private static List<Op> ops(ClusterDatabase db, List<Long> sessions, String status) throws SQLException {
        List<Op> result = new ArrayList<>();
        if (sessions.isEmpty()) {
            return result;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < sessions.size(); i++) {
            in.append(i == 0 ? "?" : ", ?");
        }
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "SELECT id, session_id, kind, item, amount, result, data FROM cluster_me_ops WHERE status = ? "
                    + "AND session_id IN ("
                    + in
                    + ") ORDER BY id")) {
            p.setString(1, status);
            for (int i = 0; i < sessions.size(); i++) {
                p.setLong(i + 2, sessions.get(i));
            }
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    result.add(
                        new Op(
                            r.getLong(1),
                            r.getLong(2),
                            r.getString(3),
                            r.getString(4),
                            r.getLong(5),
                            r.getLong(6),
                            r.getBytes(7)));
                }
            }
        }
        return result;
    }

    static final class SessionView {

        final String providerNode, providerName;
        final long snapshotVersion, ageSeconds;
        /** Null when not newer than what the viewer has. */
        final byte[] snapshot;

        SessionView(String providerNode, String providerName, long snapshotVersion, byte[] snapshot, long ageSeconds) {
            this.providerNode = providerNode;
            this.providerName = providerName;
            this.snapshotVersion = snapshotVersion;
            this.snapshot = snapshot;
            this.ageSeconds = ageSeconds;
        }
    }

    static final class Op {

        final long id, session;
        final String kind, item;
        final long amount, result;
        /** A craft plan (PLAN, when done) or the processor picked (START); else null. */
        final byte[] data;

        Op(long id, long session, String kind, String item, long amount, long result, byte[] data) {
            this.data = data;
            this.id = id;
            this.session = session;
            this.kind = kind;
            this.item = item;
            this.amount = amount;
            this.result = result;
        }
    }
}
