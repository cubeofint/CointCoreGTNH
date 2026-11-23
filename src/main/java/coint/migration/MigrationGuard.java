package coint.migration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.util.IntHashMap;
import net.minecraft.util.LongHashMap;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;

import com.google.gson.Gson;

import coint.CointConfig;
import coint.CointCore;

/** Fail-closed local freeze, restored before world loading; retired source areas stay inaccessible. */
public final class MigrationGuard {

    private static final Gson JSON = new Gson();
    private static final Map<String, MoveRecord> records = new HashMap<>();
    private static volatile Index index = new Index();
    private static boolean broken;

    private MigrationGuard() {}

    public static void load() {
        records.clear();
        broken = false;
        File[] files;
        try {
            Files.readAttributes(directory().toPath(), java.nio.file.attribute.BasicFileAttributes.class);
            files = directory().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) throw new IOException("Cannot list migration freezes");
        } catch (NoSuchFileException missing) {
            files = null;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration freezes before world loading", e);
        }
        if (files != null) {
            for (File file : files) {
                try {
                    MoveRecord record = JSON.fromJson(
                        new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8),
                        MoveRecord.class);
                    record.validate();
                    if (!file.getName()
                        .equals(record.id + ".json")) throw new IOException("Move ID mismatch");
                    records.put(record.id, record);
                } catch (IOException | RuntimeException e) {
                    broken = true;
                    CointCore.LOG
                        .error("[TeamMove] Cannot restore freeze {}; logins and world mutations blocked", file, e);
                }
            }
        }
        rebuild();
        if (broken)
            throw new IllegalStateException("Cannot safely load worlds: corrupt cointcore/migration-freezes journal");
    }

    public static void freeze(MoveRecord record) throws IOException {
        record.validate();
        Path directory = directory().toPath();
        Files.createDirectories(directory);
        Path temporary = directory.resolve(record.id + ".tmp");
        Files.write(
            temporary,
            JSON.toJson(record)
                .getBytes(StandardCharsets.UTF_8));
        Files.move(
            temporary,
            directory.resolve(record.id + ".json"),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
        records.put(record.id, record);
        rebuild();
    }

    public static void release(String id) throws IOException {
        Files.deleteIfExists(
            directory().toPath()
                .resolve(id + ".json"));
        records.remove(id);
        rebuild();
    }

    /** Keep whole retired dimensions blocked, but allow erased external chunks to generate again. */
    static void releaseExternalSource(MoveRecord move) throws IOException {
        MoveRecord retained = JSON.fromJson(JSON.toJson(move), MoveRecord.class);
        retained.claims.removeIf(c -> !retained.dimensions.contains(c[0]));
        freeze(retained);
    }

    private static File directory() {
        return new File("cointcore/migration-freezes");
    }

    private static void rebuild() {
        Index next = new Index();
        for (MoveRecord record : records.values()) {
            boolean source = record.source.equals(CointConfig.cluster.nodeId);
            for (int dim : record.dimensions) {
                next.dimensions.addKey(dim, Boolean.TRUE);
                if (source) next.sourceDimensions.addKey(dim, Boolean.TRUE);
            }
            for (int[] c : record.claims) {
                next.add(next.chunks, c);
                if (source) next.add(next.sourceChunks, c);
            }
            next.empty = false;
        }
        index = next;
    }

    public static boolean dimension(int dim) {
        return broken || index.dimensions.lookup(dim) != null;
    }

    public static boolean sourceDimension(int dim) {
        return index.sourceDimensions.lookup(dim) != null;
    }

    static void checkConflict(MoveRecord candidate) throws IOException {
        if (broken) throw new IOException("Повреждён журнал блокировок миграции");
        Set<String> positions = new HashSet<>();
        Set<Integer> candidateDimensions = new HashSet<>(candidate.dimensions);
        for (int[] c : candidate.claims) positions.add(MoveRecord.key(c));
        for (MoveRecord old : records.values()) {
            if (old.id.equals(candidate.id)) continue;
            if (completedReturn(old, candidate)) continue;
            for (int dim : old.dimensions) {
                if (candidateDimensions.contains(dim))
                    throw new IOException("Измерение " + dim + " заблокировано переносом " + old.id);
                for (int[] c : candidate.claims) if (c[0] == dim)
                    throw new IOException("Чанк " + MoveRecord.key(c) + " заблокирован переносом " + old.id);
            }
            for (int[] c : old.claims) if (candidateDimensions.contains(c[0]) || positions.contains(MoveRecord.key(c)))
                throw new IOException("Чанк " + MoveRecord.key(c) + " заблокирован переносом " + old.id);
        }
    }

    /** A completed retirement can be replaced only by its own whole team returning from the receiving node. */
    private static boolean completedReturn(MoveRecord old, MoveRecord candidate) {
        if (old.joining() || candidate.joining()
            || !candidate.target.equals(CointConfig.cluster.nodeId)
            || !old.source.equals(candidate.target)
            || !old.target.equals(candidate.source)
            || !old.team.equals(candidate.team)
            || !new HashSet<>(old.players).equals(new HashSet<>(candidate.players))
            || !candidate.dimensions.containsAll(old.dimensions)) return false;
        Set<String> claims = new HashSet<>();
        for (int[] c : candidate.claims) claims.add(MoveRecord.key(c));
        for (int[] c : old.claims)
            if (!candidate.dimensions.contains(c[0]) && !claims.contains(MoveRecord.key(c))) return false;
        try {
            Path journal = MigrationStorage.archives()
                .toPath()
                .resolve("team-moves/" + old.id);
            Path finished = MigrationStorage.serverDirectory()
                .toPath()
                .resolve("cointcore/finished-team-moves/" + old.id);
            // The old completion handler must already have run locally, otherwise it could reinstate the retirement.
            if (!Files.isRegularFile(finished) || !text(finished).equals(old.target)
                || Files.exists(journal.resolve("error"))) return false;
            MoveRecord request = JSON.fromJson(text(journal.resolve("request.json")), MoveRecord.class);
            request.validate();
            if (!request.id.equals(old.id) || !request.source.equals(old.source)
                || !request.target.equals(old.target)
                || !request.team.equals(old.team)
                || !new HashSet<>(request.players).equals(new HashSet<>(old.players))) return false;
            if (!request.dimensions.containsAll(old.dimensions)) return false;
            Set<String> originalClaims = new HashSet<>();
            for (int[] c : request.claims) originalClaims.add(MoveRecord.key(c));
            for (int[] c : old.claims) if (!originalClaims.contains(MoveRecord.key(c))) return false;
            Receipt archive = JSON.fromJson(text(journal.resolve("archive.json")), Receipt.class);
            if (archive == null || !MigrationStorage.validArchiveName(archive.name)
                || archive.sha256 == null
                || !archive.sha256.matches("[a-f0-9]{64}")) return false;
            return text(journal.resolve("done")).equals(old.target + ":" + archive.sha256)
                && text(journal.resolve("imported")).equals(archive.sha256)
                && text(journal.resolve("retired")).equals(archive.sha256);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static final class Receipt {

        String name, sha256;
    }

    private static String text(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * Persist the new receiving freeze before archiving and removing a superseded source freeze. Crash-safe on retry.
     */
    static void freezeReceiving(MoveRecord candidate) throws IOException {
        if (!candidate.target.equals(CointConfig.cluster.nodeId)) throw new IOException("Not the receiving node");
        checkConflict(candidate);
        freeze(candidate);
        for (MoveRecord old : new ArrayList<>(records.values())) {
            if (old.id.equals(candidate.id) || !completedReturn(old, candidate)) continue;
            Path backup = MigrationStorage.serverDirectory()
                .toPath()
                .resolve("cointcore/migration-return-backups/" + candidate.id + "/" + old.id + ".json");
            Files.createDirectories(backup.getParent());
            String data = JSON.toJson(old);
            if (!Files.exists(backup)) {
                Path temp = backup.resolveSibling(old.id + ".tmp");
                Files.write(temp, data.getBytes(StandardCharsets.UTF_8));
                Files.move(temp, backup, StandardCopyOption.ATOMIC_MOVE);
            }
            if (!text(backup).equals(data)) throw new IOException("Return freeze backup mismatch: " + old.id);
            release(old.id);
            CointCore.LOG.info("[TeamMove] Return {} supersedes completed source freeze {}", candidate.id, old.id);
        }
    }

    public static boolean chunk(World world, int x, int z) {
        Index snapshot = index;
        if (snapshot.empty) return false;
        return world != null && !world.isRemote
            && world.provider != null
            && snapshot.contains(snapshot.dimensions, snapshot.chunks, world.provider.dimensionId, x, z);
    }

    static boolean chunk(int dimension, int x, int z) {
        Index snapshot = index;
        return !snapshot.empty && snapshot.contains(snapshot.dimensions, snapshot.chunks, dimension, x, z);
    }

    public static boolean block(World world, int x, int z) {
        return chunk(world, x >> 4, z >> 4);
    }

    public static boolean sourceChunk(World world, int x, int z) {
        Index snapshot = index;
        return !snapshot.empty && world != null
            && !world.isRemote
            && world.provider != null
            && snapshot.contains(snapshot.sourceDimensions, snapshot.sourceChunks, world.provider.dimensionId, x, z);
    }

    static boolean sourceChunk(int dimension, int x, int z) {
        Index snapshot = index;
        return !snapshot.empty && snapshot.contains(snapshot.sourceDimensions, snapshot.sourceChunks, dimension, x, z);
    }

    private static final class Index {

        final IntHashMap dimensions = new IntHashMap(), chunks = new IntHashMap();
        final IntHashMap sourceDimensions = new IntHashMap(), sourceChunks = new IntHashMap();
        boolean empty = true;

        void add(IntHashMap map, int[] c) {
            LongHashMap positions = (LongHashMap) map.lookup(c[0]);
            if (positions == null) {
                positions = new LongHashMap();
                map.addKey(c[0], positions);
            }
            positions.add(ChunkCoordIntPair.chunkXZ2Int(c[1], c[2]), Boolean.TRUE);
        }

        boolean contains(IntHashMap dimensions, IntHashMap chunks, int dim, int x, int z) {
            if (dimensions.lookup(dim) != null) return true;
            LongHashMap positions = (LongHashMap) chunks.lookup(dim);
            return positions != null && positions.containsItem(ChunkCoordIntPair.chunkXZ2Int(x, z));
        }
    }

    public static String loginReason(UUID player) {
        if (broken) return "Миграция: повреждён журнал блокировки. Обратитесь к администратору.";
        if (player != null) for (MoveRecord record : records.values()) {
            if (!record.players.contains(player.toString())) continue;
            if (record.target.equals(CointConfig.cluster.nodeId) || !Files.isRegularFile(
                MigrationStorage.archives()
                    .toPath()
                    .resolve("team-moves/" + record.id + "/done")))
                return "Перенос команды " + record.id + " ещё не завершён. Обратитесь к администратору.";
        }
        if (player == null || !CointConfig.cluster.enabled || !CointConfig.cluster.migrationEnabled) return null;
        // The shared lock also covers the interval before the receiving node next polls its queue.
        Path lock = MigrationStorage.archives()
            .toPath()
            .resolve("team-moves/players/" + player + ".lock");
        try {
            String id = new String(Files.readAllBytes(lock), StandardCharsets.UTF_8);
            return "Перенос команды между серверами (" + id + "). Дождитесь завершения.";
        } catch (NoSuchFileException absent) {
            return MigrationConsent.loginReason(player);
        } catch (IOException e) {
            return "Не удалось проверить состояние переноса команды. Повторите вход позже.";
        }
    }

    public static String mutationReason(String name) {
        for (MoveRecord record : records.values()) for (int i = 0; i < record.names.size(); i++) {
            if (record.names.get(i)
                .equalsIgnoreCase(name)) {
                String reason = loginReason(UUID.fromString(record.players.get(i)));
                if (reason != null) return reason;
            }
        }
        serverutils.lib.data.ForgePlayer player = coint.player.PlayerLookup.exact(name);
        return player == null ? null : loginReason(player.getId());
    }
}
