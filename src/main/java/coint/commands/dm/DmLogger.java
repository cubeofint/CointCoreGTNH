package coint.commands.dm;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import coint.CointCore;

/**
 * Writes all DM traffic ({@code /tell}, {@code /msg}, {@code /w}, {@code /reply})
 * to a dedicated {@code logs/dm-chat.log} file, separate from the main server log.
 *
 * <p>
 * Call {@link #init(File)} on server start and {@link #close()} on server stop. All methods are
 * {@code synchronized} — safe to call from any thread.
 */
public final class DmLogger {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static PrintWriter writer;

    private DmLogger() {}

    /**
     * Opens (or creates) {@code <serverDir>/logs/dm-chat.log} in append mode and writes a
     * session-start marker.
     */
    public static synchronized void init(File serverDir) {
        File logsDir = new File(serverDir, "logs");
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            CointCore.LOG.error("[DmLogger] Failed to create logs directory: {}", logsDir);
            return;
        }
        File logFile = new File(logsDir, "dm-chat.log");
        try {
            // true = append; auto-flush on println
            writer = new PrintWriter(new FileWriter(logFile, true), true);
            writer.printf("[%s] ==================== Server started ====================%n", now());
        } catch (IOException e) {
            CointCore.LOG.error("[DmLogger] Failed to open dm-chat.log: {}", e.getMessage());
        }
    }

    /**
     * Appends one DM entry.
     *
     * @param type       {@code "TELL"} or {@code "REPLY"}
     * @param senderName raw (unformatted) sender name
     * @param targetName raw (unformatted) recipient name
     * @param message    unformatted message text
     */
    public static synchronized void log(String type, String senderName, String targetName, String message) {
        if (writer == null) return;
        writer.printf("[%s] [%s] %s → %s: %s%n", now(), type, senderName, targetName, message);
    }

    /**
     * Flushes, writes a session-end marker, and closes the log file. Safe to call multiple times.
     */
    public static synchronized void close() {
        if (writer != null) {
            writer.printf("[%s] ==================== Server stopped ====================%n", now());
            writer.flush();
            writer.close();
            writer = null;
        }
    }

    private static String now() {
        return LocalDateTime.now()
            .format(TIMESTAMP);
    }
}
