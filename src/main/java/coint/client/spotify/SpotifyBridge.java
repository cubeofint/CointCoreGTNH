package coint.client.spotify;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Local Windows media session bridge. Never starts until the client explicitly enables it. */
public final class SpotifyBridge {

    public static final class Snapshot {

        public final String status, title, artist, coverId, repeat;
        public final boolean playing, shuffle, canShuffle, canRepeat, canSeek;
        public final int volume;
        public final long position, duration;
        private final long received = System.nanoTime();

        private Snapshot(String status, String title, String artist, boolean playing, int volume, long position,
            long duration, String coverId, String repeat, boolean shuffle, boolean canShuffle, boolean canRepeat,
            boolean canSeek) {
            this.status = status;
            this.title = title;
            this.artist = artist;
            this.playing = playing;
            this.volume = volume;
            this.duration = Math.max(0, duration);
            this.position = Math.max(0, Math.min(position, this.duration));
            this.coverId = coverId;
            this.repeat = repeat;
            this.shuffle = shuffle;
            this.canShuffle = canShuffle;
            this.canRepeat = canRepeat;
            this.canSeek = canSeek;
        }

        static Snapshot state(String state) {
            return new Snapshot(state, "", "", false, -1, 0, 0, "", "none", false, false, false, false);
        }

        static Snapshot parse(String line) {
            JsonObject data = new JsonParser().parse(line)
                .getAsJsonObject();
            String status = data.get("status")
                .getAsString();
            if (!"ready".equals(status)) return state("waiting".equals(status) ? "waiting" : "error");
            return new Snapshot(
                "ready",
                clean(
                    data.get("title")
                        .getAsString()),
                clean(
                    data.get("artist")
                        .getAsString()),
                data.get("playing")
                    .getAsBoolean(),
                Math.max(
                    -1,
                    Math.min(
                        100,
                        data.get("volume")
                            .getAsInt())),
                data.get("position")
                    .getAsLong(),
                data.get("duration")
                    .getAsLong(),
                data.has("coverId") ? data.get("coverId")
                    .getAsString() : "",
                data.has("repeat") ? data.get("repeat")
                    .getAsString() : "none",
                flag(data, "shuffle"),
                flag(data, "canShuffle"),
                flag(data, "canRepeat"),
                !data.has("canSeek") || flag(data, "canSeek"));
        }

        private static boolean flag(JsonObject data, String key) {
            return data.has(key) && data.get(key)
                .getAsBoolean();
        }

        public long positionNow() {
            long elapsed = playing ? Math.max(0, (System.nanoTime() - received) / 1_000_000L) : 0;
            return Math.min(duration, position + Math.min(elapsed, 10000));
        }

        private static String clean(String value) {
            return value.replaceAll("[\\p{Cntrl}§]", " ")
                .substring(0, Math.min(256, value.length()));
        }
    }

    private final BlockingQueue<String> commands = new ArrayBlockingQueue<>(16);
    private volatile Snapshot snapshot = Snapshot.state("disabled");
    private volatile long lastUpdate;
    private volatile int generation;
    private volatile Process process;
    private volatile boolean enabled;
    private volatile SpotifyCover cover = SpotifyCover.EMPTY;

    SpotifyCover cover() {
        return cover;
    }

    public static boolean supported() {
        return System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .startsWith("windows");
    }

    public synchronized void start() {
        if (enabled) return;
        enabled = true;
        int run = ++generation;
        snapshot = Snapshot.state(supported() ? "starting" : "unsupported");
        lastUpdate = System.nanoTime();
        if (!supported()) return;
        Thread worker = new Thread(() -> run(run), "CointCore-Spotify");
        worker.setDaemon(true);
        worker.start();
    }

    public synchronized void stop() {
        enabled = false;
        generation++;
        commands.clear();
        cover = SpotifyCover.EMPTY;
        Process current = process;
        process = null;
        if (current != null) current.destroy();
        snapshot = Snapshot.state("disabled");
    }

    public Snapshot snapshot() {
        if (enabled && supported() && System.nanoTime() - lastUpdate > 10_000_000_000L) return Snapshot.state("error");
        return snapshot;
    }

    public void command(String command) {
        if (!enabled || !"ready".equals(snapshot().status)) return;
        if (command.matches("toggle|next|previous|shuffle|repeat|volume (100|[0-9]{1,2})|seek [0-9]{1,10}")) {
            if (command.startsWith("volume ")) commands.removeIf(pending -> pending.startsWith("volume "));
            if (command.startsWith("seek ")) commands.removeIf(pending -> pending.startsWith("seek "));
            commands.offer(command);
        }
    }

    private void run(int run) {
        Path script = null;
        Process child = null;
        try {
            if (generation != run) return;
            script = Files.createTempFile("cointcore-spotify-", ".ps1");
            try (InputStream source = SpotifyBridge.class
                .getResourceAsStream("/assets/cointcore/spotify/windows-spotify.ps1")) {
                if (source == null) throw new IOException("Missing bundled media bridge");
                Files.copy(source, script, StandardCopyOption.REPLACE_EXISTING);
            }
            String windows = System.getenv("SystemRoot");
            if (windows == null) throw new IOException("Windows directory unavailable");
            child = new ProcessBuilder(
                windows + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Mta",
                "-WindowStyle",
                "Hidden",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString()).redirectErrorStream(true)
                    .start();
            synchronized (this) {
                if (generation != run) return;
                process = child;
            }
            try (
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8))) {
                while (generation == run && child.isAlive()) {
                    String command;
                    while ((command = commands.poll()) != null) {
                        writer.write(command);
                        writer.newLine();
                        writer.flush();
                    }
                    while (reader.ready()) {
                        String line = reader.readLine();
                        if (line == null) break;
                        if (!line.startsWith("{") || line.length() > 720000) continue;
                        JsonObject message = new JsonParser().parse(line)
                            .getAsJsonObject();
                        if (message.has("status") && "artwork".equals(
                            message.get("status")
                                .getAsString())) {
                            SpotifyCover decoded = SpotifyCover.decode(
                                message.get("id")
                                    .getAsString(),
                                message.get("data")
                                    .getAsString());
                            if (generation == run) cover = decoded;
                            continue;
                        }
                        Snapshot next = Snapshot.parse(line);
                        if (generation == run) {
                            snapshot = next;
                            lastUpdate = System.nanoTime();
                        }
                    }
                    Thread.sleep(100);
                }
            }
        } catch (Exception ignored) {
            // An optional local player must never interrupt Minecraft or flood the game log.
        } finally {
            if (child != null) child.destroy();
            if (script != null) {
                try {
                    Files.deleteIfExists(script);
                } catch (IOException ignored) {}
            }
            synchronized (this) {
                if (generation == run) {
                    process = null;
                    snapshot = Snapshot.state("error");
                }
            }
        }
    }
}
