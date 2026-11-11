package coint.blocklog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import coint.CointConfig;
import coint.CointCore;

/**
 * The block log in the shared MySQL database. Records are queued on the server thread and written in batches on a
 * worker thread; lookups also run there and answer on the server thread, so the database never holds a tick.
 */
final class BlockLogStore {

    /** Records waiting beyond this are dropped: the database is gone and memory matters more. */
    private static final int QUEUE_LIMIT = 200_000;
    private static final int BATCH = 2_000;
    private static final long FLUSH_MS = 2_000;
    private static final long CLEANUP_MS = 60 * 60_000L;
    private static final int DELETE_CHUNK = 20_000;
    private static final long ERROR_LOG_EVERY_MS = 60_000L;

    private static final Queue<BlockLogEntry> pending = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger pendingCount = new AtomicInteger();
    /** Answers of lookups, run on the server thread. */
    private static final Queue<Runnable> results = new ConcurrentLinkedQueue<>();

    private static ScheduledExecutorService worker;
    // Worker thread only.
    private static Connection connection;
    private static boolean tableReady;
    private static long lastErrorLogAt;
    private static long dropped;

    private BlockLogStore() {}

    interface Lookup<T> {

        T run(Connection c) throws SQLException;
    }

    static synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CointCore-BlockLog");
            t.setDaemon(true);
            return t;
        });
        worker.scheduleWithFixedDelay(BlockLogStore::flushSafely, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(BlockLogStore::cleanupSafely, 60_000L, CLEANUP_MS, TimeUnit.MILLISECONDS);
        CointCore.LOG.info("[BlockLog] Started");
    }

    /** Writes what is left and stops. */
    static synchronized void stop() {
        ScheduledExecutorService w = worker;
        if (w == null) {
            return;
        }
        worker = null;
        w.shutdown();
        try {
            w.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        // The last records, written here: the worker is gone.
        flushSafely();
        closeConnection();
        results.clear();
    }

    static boolean isRunning() {
        return worker != null;
    }

    static void add(BlockLogEntry entry) {
        if (worker == null) {
            return;
        }
        if (pendingCount.incrementAndGet() > QUEUE_LIMIT) {
            pendingCount.decrementAndGet();
            dropped++;
            return;
        }
        pending.add(entry);
    }

    /** Runs {@code lookup} on the worker, then {@code done} on the server thread (with null when it failed). */
    static <T> boolean lookup(Lookup<T> lookup, Consumer<T> done) {
        ScheduledExecutorService w = worker;
        if (w == null) {
            return false;
        }
        try {
            w.execute(() -> {
                T value = null;
                try {
                    // What was done a moment ago is to be found too.
                    flush();
                    value = lookup.run(connection());
                } catch (SQLException e) {
                    logError("lookup", e);
                } catch (RuntimeException e) {
                    CointCore.LOG.warn("[BlockLog] Lookup failed", e);
                }
                T result = value;
                results.add(() -> done.accept(result));
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /** Server thread: hands over what the lookups found. */
    static void runResults() {
        Runnable r;
        while ((r = results.poll()) != null) {
            r.run();
        }
    }

    private static void flushSafely() {
        try {
            flush();
        } catch (SQLException e) {
            logError("write", e);
        } catch (RuntimeException e) {
            CointCore.LOG.warn("[BlockLog] Write failed", e);
        }
    }

    private static void flush() throws SQLException {
        if (pending.isEmpty()) {
            return;
        }
        Connection c = connection();
        List<BlockLogEntry> batch = new ArrayList<>(BATCH);
        while (true) {
            batch.clear();
            BlockLogEntry e;
            while (batch.size() < BATCH && (e = pending.peek()) != null) {
                batch.add(e);
                pending.poll();
            }
            if (batch.isEmpty()) {
                break;
            }
            try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO block_log (at, node_id, dim, x, y, z, action, actor, what, meta, amount) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (BlockLogEntry entry : batch) {
                    p.setTimestamp(1, new Timestamp(entry.at));
                    p.setString(2, entry.node);
                    p.setInt(3, entry.dim);
                    p.setInt(4, entry.x);
                    p.setInt(5, entry.y);
                    p.setInt(6, entry.z);
                    p.setInt(7, entry.action);
                    p.setString(8, clip(entry.actor, 64));
                    p.setString(9, clip(entry.what, 160));
                    p.setInt(10, entry.meta);
                    p.setInt(11, entry.amount);
                    p.addBatch();
                }
                p.executeBatch();
            } catch (SQLException ex) {
                // Kept for the next try, in front of the newer ones.
                List<BlockLogEntry> rest = new ArrayList<>(batch);
                BlockLogEntry e2;
                while ((e2 = pending.poll()) != null) {
                    rest.add(e2);
                }
                pending.addAll(rest);
                throw ex;
            }
            pendingCount.addAndGet(-batch.size());
        }
        if (dropped > 0) {
            CointCore.LOG.warn("[BlockLog] {} records were dropped while the database was unavailable", dropped);
            dropped = 0;
        }
    }

    private static void cleanupSafely() {
        try {
            long before = System.currentTimeMillis() - CointConfig.blockLog.retentionDays * 86_400_000L;
            Connection c = connection();
            for (int i = 0; i < 500; i++) {
                int removed;
                try (PreparedStatement p = c
                    .prepareStatement("DELETE FROM block_log WHERE at < ? LIMIT " + DELETE_CHUNK)) {
                    p.setTimestamp(1, new Timestamp(before));
                    removed = p.executeUpdate();
                }
                if (removed < DELETE_CHUNK) {
                    break;
                }
            }
        } catch (SQLException e) {
            logError("cleanup", e);
        } catch (RuntimeException e) {
            CointCore.LOG.warn("[BlockLog] Cleanup failed", e);
        }
    }

    private static Connection connection() throws SQLException {
        if (connection != null) {
            try {
                if (connection.isValid(2)) {
                    return connection;
                }
            } catch (SQLException ignored) {
                // Opened again below.
            }
            closeConnection();
        }
        CointConfig.Cluster config = CointConfig.cluster;
        Properties properties = new Properties();
        properties.setProperty("user", config.username);
        properties.setProperty("password", config.password);
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "60000");
        properties.setProperty("tcpKeepAlive", "true");
        properties.setProperty("characterEncoding", "UTF-8");
        properties.setProperty("rewriteBatchedStatements", "true");
        Connection opened = new com.mysql.cj.jdbc.Driver().connect(config.jdbcUrl, properties);
        if (opened == null) {
            throw new SQLException("Not a MySQL url: " + config.jdbcUrl);
        }
        opened.setAutoCommit(true);
        connection = opened;
        if (!tableReady) {
            try (Statement s = opened.createStatement()) {
                s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS block_log (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "at TIMESTAMP(3) NOT NULL, "
                        + "node_id VARCHAR(64) NOT NULL, "
                        + "dim INT NOT NULL, x INT NOT NULL, y SMALLINT NOT NULL, z INT NOT NULL, "
                        + "action TINYINT NOT NULL, "
                        + "actor VARCHAR(64) NOT NULL, "
                        + "what VARCHAR(160) NOT NULL, "
                        + "meta INT NOT NULL, "
                        + "amount INT NOT NULL, "
                        + "INDEX idx_block_log_pos (node_id, dim, x, z, y, at), "
                        + "INDEX idx_block_log_actor (actor, at), "
                        + "INDEX idx_block_log_at (at)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
            tableReady = true;
            CointCore.LOG.info("[BlockLog] Connected to the database");
        }
        return opened;
    }

    private static void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Nothing left to do with it.
            }
            connection = null;
        }
    }

    private static void logError(String what, SQLException e) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogAt >= ERROR_LOG_EVERY_MS) {
            lastErrorLogAt = now;
            CointCore.LOG.warn("[BlockLog] Database error ({}): {}", what, e.getMessage());
        }
        closeConnection();
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    // ---------------------------------------------------------------- lookups

    static final int PAGE = 10;

    /** What happened at one block, newest first. */
    static List<BlockLogRow> at(Connection c, String node, int dim, int x, int y, int z, int page) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
            SELECT
                + "WHERE node_id = ? AND dim = ? AND x = ? AND z = ? AND y = ? ORDER BY at DESC, id DESC LIMIT ? OFFSET ?")) {
            p.setString(1, node);
            p.setInt(2, dim);
            p.setInt(3, x);
            p.setInt(4, z);
            p.setInt(5, y);
            p.setInt(6, PAGE + 1);
            p.setInt(7, page * PAGE);
            return rows(p);
        }
    }

    /** What happened within {@code radius} blocks since {@code since}, newest first. */
    static List<BlockLogRow> near(Connection c, String node, int dim, int x, int y, int z, int radius, long since,
        String actor, int page) throws SQLException {
        String sql = SELECT + "WHERE node_id = ? AND dim = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? "
            + "AND y BETWEEN ? AND ? AND at > ?"
            + (actor == null ? "" : " AND actor = ?")
            + " ORDER BY at DESC, id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            int i = 1;
            p.setString(i++, node);
            p.setInt(i++, dim);
            p.setInt(i++, x - radius);
            p.setInt(i++, x + radius);
            p.setInt(i++, z - radius);
            p.setInt(i++, z + radius);
            p.setInt(i++, y - radius);
            p.setInt(i++, y + radius);
            p.setTimestamp(i++, new Timestamp(since));
            if (actor != null) {
                p.setString(i++, actor);
            }
            p.setInt(i++, PAGE + 1);
            p.setInt(i, page * PAGE);
            return rows(p);
        }
    }

    /** What one player did since {@code since}, on every server, newest first. */
    static List<BlockLogRow> byActor(Connection c, String actor, long since, int page) throws SQLException {
        try (PreparedStatement p = c
            .prepareStatement(SELECT + "WHERE actor = ? AND at > ? ORDER BY at DESC, id DESC LIMIT ? OFFSET ?")) {
            p.setString(1, actor);
            p.setTimestamp(2, new Timestamp(since));
            p.setInt(3, PAGE + 1);
            p.setInt(4, page * PAGE);
            return rows(p);
        }
    }

    private static final String SELECT = "SELECT at, node_id, dim, x, y, z, action, actor, what, meta, amount FROM block_log ";

    private static List<BlockLogRow> rows(PreparedStatement p) throws SQLException {
        List<BlockLogRow> result = new ArrayList<>();
        try (ResultSet r = p.executeQuery()) {
            while (r.next()) {
                result.add(
                    new BlockLogRow(
                        r.getTimestamp(1)
                            .getTime(),
                        r.getString(2),
                        r.getInt(3),
                        r.getInt(4),
                        r.getInt(5),
                        r.getInt(6),
                        r.getInt(7),
                        r.getString(8),
                        r.getString(9),
                        r.getInt(10),
                        r.getInt(11)));
            }
        }
        return result;
    }
}
