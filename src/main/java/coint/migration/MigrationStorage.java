package coint.migration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.DimensionManager;

/** Shared archives only; staging and replacement backups always belong to the local server. */
public final class MigrationStorage {

    private static volatile String sharedDirectory = "migrations";
    private static volatile long generation;
    private static volatile boolean active;

    private MigrationStorage() {}

    /** Call at server startup, before accepting migration commands. Never change during a migration. */
    public static void configure(String directory) {
        if (MigrationExporter.isRunning() || MigrationImporter.isRunning()) {
            throw new IllegalStateException("Migration is running");
        }
        sharedDirectory = directory == null || directory.trim()
            .isEmpty() ? "migrations" : directory.trim();
        generation++;
        active = true;
    }

    /** Main calls before ClusterService.stop(). Old workers may finish I/O, but cannot mutate another session. */
    public static void stop() {
        MigrationPreflight.reset();
        MigrationMove.reset();
        active = false;
        generation++;
        MigrationExporter.stop();
        MigrationImporter.stop();
        MigrationPending.reset();
    }

    static Session session() throws IOException {
        if (!active) throw new IOException("Migration session is stopped");
        return new Session(
            generation,
            MinecraftServer.getServer(),
            DimensionManager.getCurrentSaveRootDirectory()
                .getCanonicalFile());
    }

    static final class Session {

        private final long id;
        private final MinecraftServer server;
        final File world;

        private Session(long id, MinecraftServer server, File world) {
            this.id = id;
            this.server = server;
            this.world = world;
        }

        boolean current() {
            if (!active || id != generation || server == null || server != MinecraftServer.getServer()) return false;
            File root = DimensionManager.getCurrentSaveRootDirectory();
            try {
                return root != null && world.equals(root.getCanonicalFile());
            } catch (IOException e) {
                return false;
            }
        }

        void check() throws IOException {
            if (!current() || Thread.currentThread()
                .isInterrupted()) throw new IOException("Migration session ended");
        }
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread()
                        .isInterrupted()) throw new IOException("Hash interrupted");
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Checks both lexical traversal and filesystem aliases/junctions before a planned move. */
    static File child(File root, String relative) throws IOException {
        if (relative == null || relative.isEmpty()
            || new File(relative).isAbsolute()
            || relative.indexOf(':') >= 0
            || relative.indexOf('\\') >= 0) throw new IOException("Invalid relative path: " + relative);
        for (String component : relative.split("/", -1)) {
            if (component.isEmpty() || component.equals(".")
                || component.equals("..")
                || component.endsWith(".")
                || component.endsWith(" ")) throw new IOException("Invalid path: " + relative);
        }
        Path base = root.getCanonicalFile()
            .toPath();
        File result = new File(root, relative).getCanonicalFile();
        if (!result.toPath()
            .startsWith(base) || result.toPath()
                .equals(base)) {
            throw new IOException("Path escapes root: " + relative);
        }
        return result;
    }

    public static File localDirectory() {
        return new File(serverDirectory(), "migrations");
    }

    public static File serverDirectory() {
        return DimensionManager.getCurrentSaveRootDirectory()
            .getAbsoluteFile()
            .getParentFile();
    }

    public static File archives() {
        File directory = new File(sharedDirectory);
        return (directory.isAbsolute() ? directory : new File(serverDirectory(), sharedDirectory)).toPath()
            .toAbsolutePath()
            .normalize()
            .toFile();
    }

    public static boolean validArchiveName(String name) {
        return name != null && name.length() <= 255 && name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.zip");
    }

    /** Fail closed after a process crash: an operator must inspect the import before removing a stale lock. */
    public static Path lock() throws IOException {
        Path directory = archives().toPath();
        Files.createDirectories(directory);
        Path lock = directory.resolve(".import.lock");
        Files.write(
            lock,
            ("Import on " + serverDirectory() + " at " + new java.util.Date()).getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE);
        return lock;
    }
}
