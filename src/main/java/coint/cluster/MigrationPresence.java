package coint.cluster;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Uses the same cluster presence records that block an ordinary move. */
public final class MigrationPresence {

    private MigrationPresence() {}

    public static final class Snapshot {

        public final Set<String> players = new HashSet<>();
        public final Set<String> nodes = new HashSet<>();
    }

    public static void online(Consumer<Snapshot> result, Consumer<String> failed) {
        if (!ClusterService.query(db -> {
            Snapshot snapshot = new Snapshot();
            for (ClusterDatabase.NodeRow node : db.nodes(coint.CointConfig.cluster.nodeTimeoutSeconds))
                if (node.online) snapshot.nodes.add(node.id);
            try (PreparedStatement p = db.connection()
                .prepareStatement("SELECT uuid FROM cluster_players"); ResultSet rows = p.executeQuery()) {
                while (rows.next()) snapshot.players.add(rows.getString(1));
            }
            return snapshot;
        }, result, failed)) failed.accept("Кластер выключен");
    }
}
