package coint.cluster;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.event.ClickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import coint.CointConfig;
import coint.CointCore;
import coint.commands.CommandMigrate;
import coint.migration.MigrationImporter;
import coint.migration.MigrationNetwork;
import coint.migration.MigrationStorage;
import coint.supply.SupplyServerTasks;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.Universe;

/** Admin-driven handoff. No polling, automatic imports, automatic retries, or source cleanup. */
public final class MigrationCoordinator {

    private static boolean enabled;
    private static long generation;
    private static List<String> knownNodes = Collections.emptyList();

    private MigrationCoordinator() {}

    public static void configure(boolean value, String sharedDirectory) {
        generation++;
        MigrationStorage.configure(sharedDirectory);
        enabled = value;
        knownNodes = Collections.emptyList();
    }

    /** Main integrates this into schema 13, alongside the access schema. */
    public static void createTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            createTables(statement);
        }
    }

    public static void createTables(Statement statement) throws SQLException {
        statement.executeUpdate(
            "CREATE TABLE IF NOT EXISTS cluster_migrations ("
                + "id CHAR(36) NOT NULL PRIMARY KEY, archive VARCHAR(255) NOT NULL, archive_sha256 CHAR(64) NOT NULL, "
                + "source_node VARCHAR(64) NOT NULL, target_node VARCHAR(64) NOT NULL, "
                + "requested_by VARCHAR(64) NOT NULL, accepted_by VARCHAR(64) NULL, "
                + "status VARCHAR(16) NOT NULL DEFAULT 'pending', "
                + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "UNIQUE KEY uq_cluster_migrations_archive (archive), "
                + "INDEX idx_cluster_migrations_target (target_node, status)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    public static List<String> nodeSuggestions() {
        return new ArrayList<>(knownNodes);
    }

    public static void open(EntityPlayerMP player) {
        if (!CommandMigrate.canOpen(player)) return;
        MigrationNetwork.State state = new MigrationNetwork.State();
        state.current = ClusterService.isRunning() ? ClusterService.nodeId() : "local";
        state.nodes.add(state.current);
        for (ForgePlayer candidate : Universe.get()
            .getPlayers()) {
            if (!candidate.isFake()) state.names.add(candidate.getName());
        }
        Collections.sort(state.names, String.CASE_INSENSITIVE_ORDER);
        state.archives.addAll(MigrationImporter.available());
        if (!enabled || !ClusterService.isRunning()) {
            MigrationNetwork.open(player, state);
            return;
        }
        ClusterService.query(db -> {
            List<ClusterDatabase.NodeRow> nodes = db.nodes(CointConfig.cluster.nodeTimeoutSeconds);
            for (ClusterDatabase.NodeRow node : nodes) {
                if (node.online && !state.nodes.contains(node.id)) state.nodes.add(node.id);
            }
            try (PreparedStatement p = db.connection()
                .prepareStatement(
                    "SELECT id, archive, source_node, status FROM cluster_migrations WHERE target_node=? "
                        + "ORDER BY created_at DESC LIMIT 30")) {
                p.setString(1, state.current);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) state.requests
                        .add(new String[] { r.getString(1), r.getString(2), r.getString(3), r.getString(4) });
                }
            }
            return state;
        }, result -> {
            if (CommandMigrate.canOpen(player) && player.playerNetServerHandler != null) {
                knownNodes = new ArrayList<>(result.nodes);
                MigrationNetwork.open(player, result);
            }
        }, error -> {
            tell(player, "§cОчередь недоступна: " + error);
            if (CommandMigrate.canOpen(player)) MigrationNetwork.open(player, state);
        });
    }

    private static boolean ready(ICommandSender sender) {
        if (!CommandMigrate.allowed(sender)) return false;
        if (enabled && ClusterService.isRunning()) return true;
        tell(sender, "§eМежузловая очередь выключена; локальные export/import доступны.");
        return false;
    }

    public static void nodes(ICommandSender sender) {
        if (!ready(sender)) return;
        ClusterService.query(db -> db.nodes(CointConfig.cluster.nodeTimeoutSeconds), rows -> {
            if (!CommandMigrate.allowed(sender)) return;
            List<String> names = new ArrayList<>();
            for (ClusterDatabase.NodeRow row : rows) {
                names.add(row.id);
                suggest(sender, "§b" + row.id + (row.online ? " §aonline" : " §7offline"), "/server " + row.id);
            }
            knownNodes = names;
            tell(
                sender,
                "§7Источник — текущий узел " + ClusterService.nodeId()
                    + ". Для другого источника перейдите на него; цель: /migrate queue <file.zip> <node>.");
        }, error -> tell(sender, "§cНе удалось получить узлы: " + error));
    }

    public static void queue(ICommandSender sender, String archive, String target) {
        if (!ready(sender)) return;
        if (!MigrationStorage.validArchiveName(archive) || target.length() > 64
            || target.trim()
                .isEmpty()) {
            tell(sender, "§cНедопустимое имя архива или узла.");
            return;
        }
        String source = ClusterService.nodeId();
        if (source.equals(target)) {
            tell(sender, "§cВыберите другой целевой узел.");
            return;
        }
        String id = UUID.randomUUID()
            .toString();
        String actor = sender.getCommandSenderName();
        long session = generation;
        MinecraftServer server = MinecraftServer.getServer();
        File file = new File(MigrationStorage.archives(), archive);
        tell(sender, "§eПроверяю архив перед отправкой...");
        Thread verifier = new Thread(() -> {
            final String hash;
            // A completed archive from this node only, never an export still being compressed.
            try (ZipFile zip = new ZipFile(file)) {
                ZipEntry entry = zip.getEntry("migration-manifest.json");
                if (entry == null || entry.getSize() < 0 || entry.getSize() > 4 * 1024 * 1024) {
                    throw new SQLException("Нет корректного manifest");
                }
                try (InputStreamReader reader = new InputStreamReader(
                    zip.getInputStream(entry),
                    StandardCharsets.UTF_8)) {
                    JsonObject manifest = new JsonParser().parse(reader)
                        .getAsJsonObject();
                    if (!manifest.has("sourceNode") || !source.equals(
                        manifest.get("sourceNode")
                            .getAsString())) {
                        throw new SQLException("Архив должен быть экспортирован на текущем узле новой версией мода");
                    }
                }
                hash = MigrationStorage.sha256(file.toPath());
            } catch (java.io.IOException | SQLException | RuntimeException e) {
                SupplyServerTasks.run(() -> {
                    if (session == generation && server == MinecraftServer.getServer()) {
                        tell(sender, "§cАрхив недоступен или повреждён: " + e.getMessage());
                    }
                });
                return;
            }
            SupplyServerTasks.run(() -> {
                if (session != generation || server != MinecraftServer.getServer() || !ready(sender)) return;
                queueVerified(sender, archive, target, source, id, actor, hash);
            });
        }, "CointCore migration archive check");
        verifier.setDaemon(true);
        verifier.start();
    }

    private static void queueVerified(ICommandSender sender, String archive, String target, String source, String id,
        String actor, String hash) {
        ClusterService.query(db -> {
            boolean found = false;
            for (ClusterDatabase.NodeRow node : db.nodes(CointConfig.cluster.nodeTimeoutSeconds)) {
                if (node.id.equals(target) && node.online) found = true;
            }
            if (!found) throw new SQLException("Целевой узел не готов: " + target);
            try (PreparedStatement p = db.connection()
                .prepareStatement(
                    "INSERT INTO cluster_migrations (id, archive, source_node, target_node, requested_by, archive_sha256) VALUES (?,?,?,?,?,?)")) {
                p.setString(1, id);
                p.setString(2, archive);
                p.setString(3, source);
                p.setString(4, target);
                p.setString(5, actor);
                p.setString(6, hash);
                p.executeUpdate();
            }
            return id;
        },
            result -> tell(
                sender,
                "§aЗапрос " + result
                    + " ожидает явного принятия на "
                    + target
                    + ". Там выполните /migrate requests. Импорт ещё не запускался."),
            error -> tell(sender, "§cЗапрос не создан (возможно, архив уже в очереди): " + error));
    }

    public static void requests(ICommandSender sender) {
        if (!ready(sender)) return;
        String node = ClusterService.nodeId();
        ClusterService.query(db -> {
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement p = db.connection()
                .prepareStatement(
                    "SELECT id, archive, source_node, status FROM cluster_migrations WHERE target_node=? "
                        + "ORDER BY created_at DESC LIMIT 30")) {
                p.setString(1, node);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next())
                        rows.add(new String[] { r.getString(1), r.getString(2), r.getString(3), r.getString(4) });
                }
            }
            return rows;
        }, rows -> {
            if (!CommandMigrate.allowed(sender)) return;
            tell(sender, "§eПоследние 30 запросов на " + node + ": " + rows.size());
            for (String[] row : rows) {
                tell(sender, "§7" + row[0] + " | " + row[2] + " -> " + node + " | " + row[3] + " | " + row[1]);
                if ("pending".equals(row[3]))
                    suggest(sender, "§e[Проверить перед импортом]", "/migrate accept " + row[0]);
            }
        }, error -> tell(sender, "§cОчередь недоступна: " + error));
    }

    public static void accept(ICommandSender sender, String id, boolean confirm) {
        if (!ready(sender)) return;
        try {
            if (!UUID.fromString(id)
                .toString()
                .equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            tell(sender, "§cНекорректный ID запроса.");
            return;
        }
        String node = ClusterService.nodeId();
        String actor = sender.getCommandSenderName();
        ClusterService.query(db -> {
            String[] row;
            try (PreparedStatement p = db.connection()
                .prepareStatement(
                    "SELECT archive, source_node, archive_sha256 FROM cluster_migrations WHERE id=? AND target_node=? AND status='pending'")) {
                p.setString(1, id);
                p.setString(2, node);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw new SQLException("Нет ожидающего запроса на этом узле");
                    row = new String[] { r.getString(1), r.getString(2), r.getString(3) };
                }
            }
            if (confirm) {
                try (PreparedStatement p = db.connection()
                    .prepareStatement(
                        "UPDATE cluster_migrations SET status='importing', accepted_by=?, updated_at=CURRENT_TIMESTAMP(3) "
                            + "WHERE id=? AND target_node=? AND status='pending'")) {
                    p.setString(1, actor);
                    p.setString(2, id);
                    p.setString(3, node);
                    if (p.executeUpdate() != 1) throw new SQLException("Запрос уже принят другим администратором");
                }
            }
            return row;
        }, row -> {
            if (!CommandMigrate.allowed(sender)) {
                if (confirm) finish(id, false);
                return;
            }
            if (!confirm) {
                tell(
                    sender,
                    "§e" + row[1]
                        + " -> "
                        + node
                        + ": "
                        + row[0]
                        + ". Импорт заменяет данные; вся команда должна быть офлайн. Backup остаётся локально.");
                suggest(sender, "§c[Подтвердить импорт командой]", "/migrate accept " + id + " confirm");
                return;
            }
            String error = MigrationImporter.start(sender, row[0], row[2], row[1], success -> finish(id, success));
            if (error != null) {
                finish(id, false);
                tell(sender, "§cИмпорт не начат: " + error);
            }
        }, error -> tell(sender, "§cЗапрос не принят: " + error));
    }

    private static void finish(String id, boolean success) {
        boolean submitted = ClusterService.query(db -> {
            try (PreparedStatement p = db.connection()
                .prepareStatement(
                    "UPDATE cluster_migrations SET status=?, updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='importing'")) {
                p.setString(1, success ? "done" : "failed");
                p.setString(2, id);
                return p.executeUpdate();
            }
        }, ignored -> {}, error -> CointCore.LOG.error("[Migrate] Cannot finish request {}: {}", id, error));
        if (!submitted)
            CointCore.LOG.error("[Migrate] Cluster stopped; request {} remains importing (success={})", id, success);
    }

    public static void tell(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text));
    }

    public static void suggest(ICommandSender sender, String text, String command) {
        ChatComponentText message = new ChatComponentText(text);
        message.getChatStyle()
            .setChatClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command));
        sender.addChatMessage(message);
    }
}
