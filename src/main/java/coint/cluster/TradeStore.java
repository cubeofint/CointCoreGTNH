package coint.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Trades between servers and the items waiting for players, in the shared database. Worker thread only. */
final class TradeStore {

    private static final String COLUMNS = "id, a_uuid, a_name, a_node, b_uuid, b_name, b_node, a_offer, b_offer, "
        + "a_epoch, b_epoch, a_ready, b_ready, a_confirm, b_confirm, a_state, b_state, a_notice, b_notice, status, "
        + "chat, deal, version";

    private TradeStore() {}

    static void createTables(Statement s) throws SQLException {
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_trades (" + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "a_uuid CHAR(36) NOT NULL, a_name VARCHAR(32) NOT NULL, a_node VARCHAR(64) NOT NULL, "
                + "b_uuid CHAR(36) NOT NULL, b_name VARCHAR(32) NOT NULL, b_node VARCHAR(64) NOT NULL, "
                + "a_offer MEDIUMBLOB NULL, b_offer MEDIUMBLOB NULL, "
                + "a_epoch INT NOT NULL DEFAULT -1, b_epoch INT NOT NULL DEFAULT -1, "
                + "a_ready BOOLEAN NOT NULL DEFAULT FALSE, b_ready BOOLEAN NOT NULL DEFAULT FALSE, "
                + "a_confirm BOOLEAN NOT NULL DEFAULT FALSE, b_confirm BOOLEAN NOT NULL DEFAULT FALSE, "
                + "a_state VARCHAR(16) NOT NULL DEFAULT 'open', b_state VARCHAR(16) NOT NULL DEFAULT 'open', "
                + "a_notice VARCHAR(255) NOT NULL DEFAULT '', b_notice VARCHAR(255) NOT NULL DEFAULT '', "
                + "status VARCHAR(16) NOT NULL DEFAULT 'open', "
                + "chat MEDIUMTEXT NULL, "
                + "deal BIGINT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0, "
                + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "INDEX idx_cluster_trades_status (status, updated_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_trade_deliveries (" + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "uuid CHAR(36) NOT NULL, "
                + "name VARCHAR(32) NOT NULL, "
                + "trade_id BIGINT NOT NULL, "
                + "payload MEDIUMBLOB NOT NULL, "
                + "note VARCHAR(255) NOT NULL, "
                + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "INDEX idx_cluster_trade_deliveries_uuid (uuid)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    static TradeRow create(ClusterDatabase db, TradeRow row) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "INSERT INTO cluster_trades (a_uuid, a_name, a_node, b_uuid, b_name, b_node, a_epoch, b_epoch, chat, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3), NOW(3))",
                Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, row.uuid[0]);
            p.setString(2, ClusterDatabase.clip(row.name[0], 32));
            p.setString(3, row.node[0]);
            p.setString(4, row.uuid[1]);
            p.setString(5, ClusterDatabase.clip(row.name[1], 32));
            p.setString(6, row.node[1]);
            p.setInt(7, row.epoch[0]);
            p.setInt(8, row.epoch[1]);
            p.setString(9, row.chat);
            p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) {
                if (!r.next()) {
                    throw new SQLException("No id for the new trade");
                }
                row.id = r.getLong(1);
            }
        }
        return row;
    }

    /** The trade if it changed since {@code version}; null when it did not (or is gone). */
    static TradeRow load(ClusterDatabase db, long id, long version) throws SQLException {
        try (PreparedStatement p = db.connection()
            .prepareStatement("SELECT " + COLUMNS + " FROM cluster_trades WHERE id = ? AND version > ?")) {
            p.setLong(1, id);
            p.setLong(2, version);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? read(r) : null;
            }
        }
    }

    /** Result of {@link #change}: the row as it is now, and whether the change was made. Null row: no such trade. */
    static final class Change {

        private final TradeRow row;
        private final boolean applied;

        Change(TradeRow row, boolean applied) {
            this.row = row;
            this.applied = applied;
        }

        TradeRow row() {
            return row;
        }

        boolean applied() {
            return applied;
        }
    }

    static Change change(ClusterDatabase db, long id, TradeMutation mutation) throws SQLException {
        Connection c = db.connection();
        c.setAutoCommit(false);
        try {
            TradeRow row;
            try (PreparedStatement p = c
                .prepareStatement("SELECT " + COLUMNS + " FROM cluster_trades WHERE id = ? FOR UPDATE")) {
                p.setLong(1, id);
                try (ResultSet r = p.executeQuery()) {
                    row = r.next() ? read(r) : null;
                }
            }
            if (row == null) {
                c.commit();
                return new Change(null, false);
            }
            List<TradeDelivery> deliveries = new ArrayList<>();
            boolean applied = mutation.apply(row, deliveries);
            if (applied) {
                row.version++;
                write(c, row);
                for (TradeDelivery delivery : deliveries) {
                    insert(c, delivery);
                }
            }
            c.commit();
            return new Change(row, applied);
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** Open trades nobody touched for {@code seconds}: called off, what was taken goes back. Returns how many. */
    static int sweep(ClusterDatabase db, int seconds) throws SQLException {
        List<Long> stale = new ArrayList<>();
        try (PreparedStatement p = db.connection()
            .prepareStatement(
                "SELECT id FROM cluster_trades WHERE status = 'open' AND updated_at < NOW(3) - INTERVAL ? SECOND")) {
            p.setInt(1, seconds);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    stale.add(r.getLong(1));
                }
            }
        }
        int swept = 0;
        for (long id : stale) {
            Change change = change(db, id, (row, deliveries) -> {
                if (!row.open()) {
                    return false;
                }
                row.abort("Обмен отменён: он слишком долго не двигался.", deliveries);
                return true;
            });
            if (change.applied()) {
                swept++;
            }
        }
        return swept;
    }

    /**
     * Takes the items waiting for these players that this server knows ({@code deliverable}): they are gone from the
     * database once returned.
     */
    static List<TradeDelivery> claim(ClusterDatabase db, List<String> uuids, Predicate<byte[]> deliverable)
        throws SQLException {
        List<TradeDelivery> found = new ArrayList<>();
        if (uuids.isEmpty()) {
            return found;
        }
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < uuids.size(); i++) {
            marks.append(i == 0 ? "?" : ", ?");
        }
        Connection c = db.connection();
        try (PreparedStatement p = c.prepareStatement(
            "SELECT id, uuid, name, trade_id, payload, note FROM cluster_trade_deliveries WHERE uuid IN (" + marks
                + ") ORDER BY id")) {
            for (int i = 0; i < uuids.size(); i++) {
                p.setString(i + 1, uuids.get(i));
            }
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    TradeDelivery d = new TradeDelivery(
                        r.getString(2),
                        r.getString(3),
                        r.getLong(4),
                        r.getBytes(5),
                        r.getString(6));
                    d.id = r.getLong(1);
                    found.add(d);
                }
            }
        }
        List<TradeDelivery> claimed = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("DELETE FROM cluster_trade_deliveries WHERE id = ?")) {
            for (TradeDelivery d : found) {
                if (!deliverable.test(d.payload)) {
                    continue;
                }
                p.setLong(1, d.id);
                // Another server may have taken it first (the player moved): only one gives it.
                if (p.executeUpdate() == 1) {
                    claimed.add(d);
                }
            }
        }
        return claimed;
    }

    static void deliver(ClusterDatabase db, TradeDelivery delivery) throws SQLException {
        insert(db.connection(), delivery);
    }

    private static void insert(Connection c, TradeDelivery d) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
            "INSERT INTO cluster_trade_deliveries (uuid, name, trade_id, payload, note, created_at) "
                + "VALUES (?, ?, ?, ?, ?, NOW(3))")) {
            p.setString(1, d.uuid);
            p.setString(2, ClusterDatabase.clip(d.name, 32));
            p.setLong(3, d.tradeId);
            p.setBytes(4, d.payload);
            p.setString(5, ClusterDatabase.clip(d.note, 255));
            p.executeUpdate();
        }
    }

    private static void write(Connection c, TradeRow row) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
            "UPDATE cluster_trades SET a_offer = ?, b_offer = ?, a_epoch = ?, b_epoch = ?, a_ready = ?, b_ready = ?, "
                + "a_confirm = ?, b_confirm = ?, a_state = ?, b_state = ?, a_notice = ?, b_notice = ?, status = ?, "
                + "chat = ?, deal = ?, version = ?, updated_at = NOW(3) WHERE id = ?")) {
            p.setBytes(1, row.offer[0]);
            p.setBytes(2, row.offer[1]);
            p.setInt(3, row.epoch[0]);
            p.setInt(4, row.epoch[1]);
            p.setBoolean(5, row.ready[0]);
            p.setBoolean(6, row.ready[1]);
            p.setBoolean(7, row.confirm[0]);
            p.setBoolean(8, row.confirm[1]);
            p.setString(9, row.state[0]);
            p.setString(10, row.state[1]);
            p.setString(11, ClusterDatabase.clip(row.notice[0], 255));
            p.setString(12, ClusterDatabase.clip(row.notice[1], 255));
            p.setString(13, row.status);
            p.setString(14, row.chat);
            p.setLong(15, row.deal);
            p.setLong(16, row.version);
            p.setLong(17, row.id);
            p.executeUpdate();
        }
    }

    private static TradeRow read(ResultSet r) throws SQLException {
        TradeRow row = new TradeRow();
        row.id = r.getLong("id");
        row.uuid[0] = r.getString("a_uuid");
        row.name[0] = r.getString("a_name");
        row.node[0] = r.getString("a_node");
        row.uuid[1] = r.getString("b_uuid");
        row.name[1] = r.getString("b_name");
        row.node[1] = r.getString("b_node");
        row.offer[0] = r.getBytes("a_offer");
        row.offer[1] = r.getBytes("b_offer");
        row.epoch[0] = r.getInt("a_epoch");
        row.epoch[1] = r.getInt("b_epoch");
        row.ready[0] = r.getBoolean("a_ready");
        row.ready[1] = r.getBoolean("b_ready");
        row.confirm[0] = r.getBoolean("a_confirm");
        row.confirm[1] = r.getBoolean("b_confirm");
        row.state[0] = r.getString("a_state");
        row.state[1] = r.getString("b_state");
        row.notice[0] = r.getString("a_notice");
        row.notice[1] = r.getString("b_notice");
        row.status = r.getString("status");
        String chat = r.getString("chat");
        row.chat = chat == null ? "" : chat;
        row.deal = r.getLong("deal");
        row.version = r.getLong("version");
        return row;
    }
}
