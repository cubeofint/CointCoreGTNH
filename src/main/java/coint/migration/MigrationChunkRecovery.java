package coint.migration;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraftforge.common.DimensionManager;

/** Read-only proof of an already installed external chunk. Never open live region files in write mode. */
final class MigrationChunkRecovery {

    private static final int MAX_CHUNK_BYTES = 64 * 1024 * 1024;

    private final Path region;
    private final int[] position;
    private final int location, timestamp, compression;
    private final byte[] digest;

    private MigrationChunkRecovery(Path region, int[] position, Saved saved) {
        this.region = region;
        this.position = position.clone();
        location = saved.location;
        timestamp = saved.timestamp;
        compression = saved.compression;
        digest = hash(saved.payload);
    }

    /** Main thread only: inspect loaded state without loading, saving or unloading any dimension/chunk. */
    static void checkFrozen(MoveRecord move) throws IOException {
        for (int[] c : move.claims) {
            if (move.dimensions.contains(c[0])) continue;
            if (!MigrationGuard.chunk(c[0], c[1], c[2]))
                throw new IOException("Внешний чанк не заморожен: " + MoveRecord.key(c));
            WorldServer world = DimensionManager.getWorld(c[0]);
            if (world != null && (!(world.theChunkProviderServer.currentChunkLoader instanceof AnvilChunkLoader)
                || world.theChunkProviderServer.loadedChunkHashMap
                    .getValueByKey(ChunkCoordIntPair.chunkXZ2Int(c[1], c[2])) != null))
                throw new IOException("Внешний чанк должен оставаться выгруженным: " + MoveRecord.key(c));
        }
    }

    /** Resolve provider save folders on the main thread, not from the archive worker. */
    static Map<String, Path> targets(MoveRecord move, File root) throws IOException {
        checkFrozen(move);
        Map<String, Path> targets = new HashMap<>();
        for (int[] c : move.claims) {
            if (move.dimensions.contains(c[0])) continue;
            if (!DimensionManager.isDimensionRegistered(c[0]))
                throw new IOException("Нет дополнительного измерения " + c[0]);
            WorldServer world = DimensionManager.getWorld(c[0]);
            WorldProvider provider = world == null ? DimensionManager.createProviderFor(c[0]) : world.provider;
            if (provider == null || provider.dimensionId != c[0])
                throw new IOException("Не удалось определить папку измерения " + c[0]);
            File folder = provider.getSaveFolder() == null ? root
                : MigrationStorage.child(root, provider.getSaveFolder());
            File region = MigrationStorage.child(folder, "region/r." + (c[1] >> 5) + "." + (c[2] >> 5) + ".mca");
            targets.put(MoveRecord.key(c), region.toPath());
        }
        return targets;
    }

    static List<MigrationChunkRecovery> verify(MigrationImporter.Operation operation, Map<String, Object> manifest)
        throws IOException {
        if (operation.recoveryChunks == null) throw new IOException("Нет списка внешних чанков для проверки");
        Set<String> seen = new HashSet<>();
        List<MigrationChunkRecovery> proofs = new ArrayList<>();
        for (int[] c : MigrationChunks.external(manifest)) {
            if (!operation.current()) throw new IOException("Сессия восстановления завершена");
            String key = MoveRecord.key(c);
            Path region = operation.recoveryChunks.get(key);
            if (region == null) throw new IOException("Неожиданный внешний чанк: " + key);
            proofs.add(
                verify(
                    MigrationStorage.child(operation.staging, MigrationChunks.path(c))
                        .toPath(),
                    region,
                    c));
            seen.add(key);
        }
        if (!seen.equals(operation.recoveryChunks.keySet()))
            throw new IOException("Список внешних чанков не совпадает с журналом");
        return proofs;
    }

    static MigrationChunkRecovery verify(Path archived, Path region, int[] position) throws IOException {
        Saved saved = read(region, position);
        NBTTagCompound expected;
        try (InputStream input = Files.newInputStream(archived)) {
            expected = CompressedStreamTools.readCompressed(input);
        }
        NBTTagCompound actual;
        try (
            InputStream input = saved.compression == 1 ? new GZIPInputStream(new ByteArrayInputStream(saved.payload))
                : new InflaterInputStream(new ByteArrayInputStream(saved.payload));
            DataInputStream data = new DataInputStream(input)) {
            actual = CompressedStreamTools.func_152456_a(data, new NBTSizeTracker(MAX_CHUNK_BYTES));
        }
        NBTTagCompound level = actual.getCompoundTag("Level");
        if (!actual.hasKey("Level", 10) || !level.hasKey("Sections", 9)
            || level.getInteger("xPos") != position[1]
            || level.getInteger("zPos") != position[2]
            || !expected.equals(actual))
            throw new IOException("Размещённый внешний чанк отличается от архива: " + MoveRecord.key(position));
        MigrationChunkRecovery proof = new MigrationChunkRecovery(region, position, saved);
        proof.unchanged();
        return proof;
    }

    /** Compare only this chunk. Other teams may keep saving neighbouring chunks in the same region file. */
    void unchanged() throws IOException {
        Saved now = read(region, position);
        if (location != now.location || timestamp != now.timestamp
            || compression != now.compression
            || !Arrays.equals(digest, hash(now.payload)))
            throw new IOException("Внешний чанк изменился во время проверки: " + MoveRecord.key(position));
    }

    private static Saved read(Path region, int[] c) throws IOException {
        if (!Files.isRegularFile(region, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Нет файла внешнего чанка: " + MoveRecord.key(c));
        int slot = (c[1] & 31) + (c[2] & 31) * 32;
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "r")) {
            if (file.length() < 8192) throw new IOException("Повреждён заголовок региона: " + region);
            file.seek(slot * 4L);
            int location = file.readInt();
            file.seek(4096 + slot * 4L);
            int timestamp = file.readInt();
            int sector = location >>> 8, count = location & 255;
            long start = sector * 4096L;
            if (sector < 2 || count == 0 || start + 5 > file.length())
                throw new IOException("Нет сохранённого внешнего чанка: " + MoveRecord.key(c));
            file.seek(start);
            int length = file.readInt();
            // Hodgepodge uses 255 as an extended sector count, inferred from the payload length.
            long sectors = count == 255 ? ((long) length + 4) / 4096 + 1 : count;
            if (length < 2 || length > MAX_CHUNK_BYTES
                || length + 4L > sectors * 4096
                || start + sectors * 4096 > file.length())
                throw new IOException("Некорректная длина внешнего чанка: " + MoveRecord.key(c));
            int compression = file.readUnsignedByte();
            if (compression != 1 && compression != 2)
                throw new IOException("Неизвестное сжатие внешнего чанка: " + compression);
            byte[] payload = new byte[length - 1];
            file.readFully(payload);
            file.seek(slot * 4L);
            if (file.readInt() != location) throw new IOException("Чанк изменился во время чтения");
            file.seek(4096 + slot * 4L);
            if (file.readInt() != timestamp) throw new IOException("Чанк изменился во время чтения");
            return new Saved(location, timestamp, compression, payload);
        }
    }

    private static byte[] hash(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Saved {

        final int location, timestamp, compression;
        final byte[] payload;

        Saved(int location, int timestamp, int compression, byte[] payload) {
            this.location = location;
            this.timestamp = timestamp;
            this.compression = compression;
            this.payload = payload;
        }
    }
}
