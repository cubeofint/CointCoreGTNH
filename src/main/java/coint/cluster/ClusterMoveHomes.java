package coint.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Consumer;

import coint.CointConfig;
import coint.migration.MoveRecord;

/** Routing changes only after source retirement; all team members commit in one transaction. */
public final class ClusterMoveHomes {

    private ClusterMoveHomes() {}

    /** Read-only routing check while the migrating team is still online. */
    public static void inspect(MoveRecord move, Consumer<java.util.List<String>> result) {
        if (!ClusterService.query(db -> {
            java.util.List<String> issues = new java.util.ArrayList<>();
            boolean online = false;
            for (ClusterDatabase.NodeRow node : db.nodes(CointConfig.cluster.nodeTimeoutSeconds))
                if (node.id.equals(move.target) && node.online) online = true;
            if (!online) issues.add("Целевая нода недоступна: " + move.target);
            for (int i = 0; i < move.players.size(); i++) try (PreparedStatement p = db.connection()
                .prepareStatement("SELECT node_id FROM cluster_homes WHERE uuid=?")) {
                    p.setString(1, move.players.get(i));
                    try (ResultSet r = p.executeQuery()) {
                        if (r.next() && !move.source.equals(r.getString(1)))
                            issues.add("Домашний сервер " + move.names.get(i) + " уже " + r.getString(1));
                    }
                }
            return issues;
        },
            result,
            error -> result
                .accept(java.util.Collections.singletonList("Не удалось проверить маршрутизацию: " + error))))
            result.accept(java.util.Collections.singletonList("Кластер выключен"));
    }

    public static void check(MoveRecord move, Runnable ready, Consumer<String> failed) {
        if (!ClusterService.query(db -> {
            boolean online = false;
            for (ClusterDatabase.NodeRow node : db.nodes(CointConfig.cluster.nodeTimeoutSeconds)) {
                if (node.id.equals(move.target) && node.online) online = true;
            }
            if (!online) throw new SQLException("Целевой сервер недоступен: " + move.target);
            for (int i = 0; i < move.players.size(); i++) {
                String home = db.home(move.players.get(i), move.names.get(i), null);
                if (home != null && !home.equals(move.source))
                    throw new SQLException("Домашний сервер " + move.names.get(i) + " уже " + home);
                try (PreparedStatement p = db.connection()
                    .prepareStatement("SELECT node_id FROM cluster_players WHERE uuid=?")) {
                    p.setString(1, move.players.get(i));
                    try (ResultSet r = p.executeQuery()) {
                        if (r.next()) throw new SQLException(
                            "Игрок " + move.names.get(i) + " ещё числится в сети; дождитесь heartbeat после выхода");
                    }
                }
            }
            return true;
        }, result -> ready.run(), failed)) failed.accept("Кластер выключен");
    }

    public static void commit(MoveRecord move, Runnable ready, Consumer<String> failed) {
        if (!ClusterService.query(db -> {
            Connection c = db.connection();
            c.setAutoCommit(false);
            try {
                for (int i = 0; i < move.players.size(); i++) {
                    String uuid = move.players.get(i);
                    try (PreparedStatement p = c
                        .prepareStatement("SELECT node_id FROM cluster_homes WHERE uuid=? FOR UPDATE")) {
                        p.setString(1, uuid);
                        try (ResultSet r = p.executeQuery()) {
                            if (r.next() && !move.source.equals(r.getString(1)) && !move.target.equals(r.getString(1)))
                                throw new SQLException("Домашний сервер изменён вручную: " + uuid);
                        }
                    }
                    try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO cluster_homes (uuid,name,node_id,updated_at) VALUES (?,?,?,NOW(3)) ON DUPLICATE KEY UPDATE name=VALUES(name),node_id=VALUES(node_id),updated_at=NOW(3)")) {
                        p.setString(1, uuid);
                        p.setString(2, move.names.get(i));
                        p.setString(3, move.target);
                        p.executeUpdate();
                    }
                    for (String table : new String[] { "cluster_returns", "cluster_moves", "cluster_arrivals" }) {
                        try (PreparedStatement p = c.prepareStatement("DELETE FROM " + table + " WHERE uuid=?")) {
                            p.setString(1, uuid);
                            p.executeUpdate();
                        }
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
            return true;
        }, result -> {
            refresh(move);
            ready.run();
        }, failed)) failed.accept("Кластер выключен");
    }

    public static void refresh(MoveRecord move) {
        for (String uuid : move.players) ClusterHomes.homeChanged(uuid, move.target);
    }
}
