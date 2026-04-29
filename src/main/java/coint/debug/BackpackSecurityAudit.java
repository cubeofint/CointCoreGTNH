package coint.debug;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

import coint.CointCore;

public final class BackpackSecurityAudit {

    public static final boolean ENABLED = Boolean.getBoolean("cointcore.audit.backpack.enabled");
    private static final Object LOCK = new Object();
    private static final Path LOG_PATH = Paths.get("logs", "backpack-security.log");
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private BackpackSecurityAudit() {}

    public static void logEvent(String event, String details) {
        if (!ENABLED) return;
        String line = TS.format(new Date()) + " [" + event + "] " + details + System.lineSeparator();
        synchronized (LOCK) {
            try {
                Path parent = LOG_PATH.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (Writer writer = Files.newBufferedWriter(
                    LOG_PATH,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                    writer.write(line);
                }
            } catch (IOException e) {
                CointCore.LOG.warn("Failed to write backpack security audit event", e);
            }
        }
    }
}
