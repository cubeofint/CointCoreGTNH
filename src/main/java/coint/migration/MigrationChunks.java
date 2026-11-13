package coint.migration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;

import serverutils.data.ClaimedChunk;
import serverutils.data.ClaimedChunks;
import serverutils.data.ServerUtilitiesLoadedChunkManager;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;
import serverutils.lib.math.ChunkDimPos;

/** Whole external chunks, never region files (which would overwrite other teams' neighbouring chunks). */
public final class MigrationChunks {

    private MigrationChunks() {}

    static String path(int[] c) {
        return "cointcore-migration/chunks/" + c[0] + "_" + c[1] + "_" + c[2] + ".dat";
    }

    static void checkClaims(MoveRecord move, boolean target) throws IOException {
        MigrationGuard.checkConflict(move);
        if (ClaimedChunks.instance == null) throw new IOException("ServerUtilities claims are unavailable");
        ClaimedChunks.instance.processQueue();
        ForgeTeam team = Universe.get()
            .getTeam(move.team);
        if (!target) {
            if (team == null || !team.isValid()) throw new IOException("Исходная команда больше не существует");
            Set<String> members = new HashSet<>(), positions = new HashSet<>(), expected = new HashSet<>();
            for (serverutils.lib.data.ForgePlayer player : team.getMembers()) members.add(
                player.getId()
                    .toString());
            for (ClaimedChunk claim : ClaimedChunks.instance.getTeamChunks(team, java.util.OptionalInt.empty(), true))
                positions.add(claim.getPos().dim + ":" + claim.getPos().posX + ":" + claim.getPos().posZ);
            for (int[] c : move.claims) expected.add(MoveRecord.key(c));
            if (!members.equals(new HashSet<>(move.players)) || !positions.equals(expected))
                throw new IOException("Состав команды или список приватов изменился после запроса");
        }
        if (target) for (String id : move.players) {
            serverutils.lib.data.ForgePlayer player = Universe.get()
                .getPlayer(java.util.UUID.fromString(id));
            if (player != null && player.hasTeam()
                && !player.team.getId()
                    .equals(move.team))
                throw new IOException("На цели игрок состоит в другой команде: " + player.getName());
        }
        if (target && team != null && team.isValid()) {
            for (serverutils.lib.data.ForgePlayer member : team.getMembers()) {
                if (!move.players.contains(
                    member.getId()
                        .toString())) {
                    throw new IOException("На цели ID команды занят другой командой: " + move.team);
                }
            }
        }
        for (int[] c : move.claims) {
            if (!move.dimensions.contains(c[0]) && !DimensionManager.isDimensionRegistered(c[0]))
                throw new IOException("Нет дополнительного измерения " + c[0]);
            if (target && MigrationGuard.sourceChunk(DimensionManager.getWorld(c[0]), c[1], c[2]))
                throw new IOException("На цели чанк уже архивирован прежним переносом: " + MoveRecord.key(c));
            ClaimedChunk existing = ClaimedChunks.instance.getChunk(new ChunkDimPos(c[1], c[2], c[0]));
            if (existing != null && !existing.getTeam()
                .getId()
                .equals(move.team)) {
                throw new IOException(
                    "Чанк занят другой командой: измерение " + c[0]
                        + ", чанк "
                        + c[1]
                        + ", "
                        + c[2]
                        + ". Автоперенос невозможен.");
            }
            if (!target && (existing == null || !existing.getTeam()
                .getId()
                .equals(move.team))) {
                throw new IOException("Приват источника изменился: " + MoveRecord.key(c));
            }
            if (!move.dimensions.contains(c[0])) checkPlayers(c);
        }
        // A personal world is copied in full, so a foreign claim anywhere in it is a conflict too.
        for (ClaimedChunk claim : ClaimedChunks.instance.getAllChunks()) {
            if (!claim.isInvalid() && move.dimensions.contains(claim.getPos().dim)
                && !claim.getTeam()
                    .getId()
                    .equals(move.team)) {
                throw new IOException(
                    "В персональном измерении " + claim.getPos().dim + " есть чужой приват. Автоперенос невозможен.");
            }
        }
        for (int dim : move.dimensions) {
            if (target && MigrationGuard.sourceDimension(dim))
                throw new IOException("На цели персоналка архивирована прежним переносом: " + dim);
            Short owner = coint.player.TeamsManager.get()
                .getTeamUidForDim(dim);
            ForgeTeam holder = owner == null ? null
                : Universe.get()
                    .getTeam(owner);
            if (holder != null && holder.isValid()
                && !holder.getId()
                    .equals(move.team)) {
                throw new IOException("Персональное измерение " + dim + " занято другой командой");
            }
            WorldServer world = DimensionManager.getWorld(dim);
            if (world != null && !world.playerEntities.isEmpty())
                throw new IOException("В измерении " + dim + " есть игроки");
        }
    }

    private static void checkPlayers(int[] c) throws IOException {
        int distance = MinecraftServer.getServer()
            .getConfigurationManager()
            .getViewDistance() + 2;
        for (Object value : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) value;
            if (player.dimension == c[0] && Math.abs((player.chunkCoordX) - (long) c[1]) <= distance
                && Math.abs((player.chunkCoordZ) - (long) c[2]) <= distance) {
                throw new IOException(
                    "Игрок " + player.getCommandSenderName()
                        + " рядом с чанком "
                        + MoveRecord.key(c)
                        + "; нужно отойти за дальность прорисовки");
            }
        }
    }

    static void materialize(MoveRecord move) throws IOException {
        for (int[] c : move.claims) {
            if (move.dimensions.contains(c[0]) && !DimensionManager.isDimensionRegistered(c[0])) continue;
            WorldServer world = MinecraftServer.getServer()
                .worldServerForDimension(c[0]);
            if (world == null || world.provider.dimensionId != c[0]
                || !(world.theChunkProviderServer.currentChunkLoader instanceof AnvilChunkLoader))
                throw new IOException("Несовместимый формат измерения " + c[0]);
            // syncChunkLoad joins an earlier player-manager async job for these coordinates.
            world.theChunkProviderServer.loadChunk(c[1], c[2]);
        }
    }

    static void unforce(MoveRecord move) {
        for (int[] c : move.claims) {
            ClaimedChunk claim = ClaimedChunks.instance.getChunk(new ChunkDimPos(c[1], c[2], c[0]));
            if (claim != null) {
                claim.setLoaded(false);
                claim.forced = null;
                ServerUtilitiesLoadedChunkManager.INSTANCE.unforceChunk(claim);
            }
        }
        for (WorldServer world : DimensionManager.getWorlds()) {
            for (Map.Entry<ChunkCoordIntPair, ForgeChunkManager.Ticket> entry : new ArrayList<>(
                ForgeChunkManager.getPersistentChunksFor(world)
                    .entries())) {
                ChunkCoordIntPair pos = entry.getKey();
                if (MigrationGuard.chunk(world, pos.chunkXPos, pos.chunkZPos))
                    ForgeChunkManager.unforceChunk(entry.getValue(), pos);
            }
        }
    }

    static void snapshot(File staging, MoveRecord move) throws IOException {
        for (int[] c : move.claims) {
            if (move.dimensions.contains(c[0])) continue;
            WorldServer world = DimensionManager.getWorld(c[0]);
            net.minecraft.world.WorldProvider provider = world == null ? DimensionManager.createProviderFor(c[0])
                : world.provider;
            File folder = MigrationStorage.session().world;
            if (provider.getSaveFolder() != null) folder = MigrationStorage.child(folder, provider.getSaveFolder());
            NBTTagCompound root;
            try (java.io.DataInputStream input = RegionFileCache.getChunkInputStream(folder, c[1], c[2])) {
                if (input == null) throw new IOException("Нет сохранённого чанка " + MoveRecord.key(c));
                root = CompressedStreamTools.read(input);
            }
            File file = MigrationStorage.child(staging, path(c));
            Files.createDirectories(
                file.toPath()
                    .getParent());
            try (OutputStream output = Files.newOutputStream(file.toPath())) {
                CompressedStreamTools.writeCompressed(root, output);
            }
        }
    }

    static boolean drainSource(MoveRecord move) throws IOException {
        boolean drained = true;
        for (int[] c : move.claims) {
            if (move.dimensions.contains(c[0])) continue;
            WorldServer world = DimensionManager.getWorld(c[0]);
            if (world == null) continue;
            Chunk chunk = (Chunk) world.theChunkProviderServer.loadedChunkHashMap
                .getValueByKey(ChunkCoordIntPair.chunkXZ2Int(c[1], c[2]));
            if (chunk == null) continue;
            chunk.onChunkUnload();
            try {
                world.theChunkProviderServer.currentChunkLoader.saveChunk(world, chunk);
            } catch (net.minecraft.world.MinecraftException e) {
                throw new IOException(e);
            }
            world.theChunkProviderServer.loadedChunkHashMap.remove(ChunkCoordIntPair.chunkXZ2Int(c[1], c[2]));
            world.theChunkProviderServer.loadedChunks.remove(chunk);
            drained = false;
        }
        for (int dim : move.dimensions) {
            if (DimensionManager.getWorld(dim) != null) {
                DimensionManager.unloadWorld(dim);
                drained = false;
            }
        }
        return drained;
    }

    static List<int[]> external(Map<String, Object> manifest) throws IOException {
        List<int[]> result = new ArrayList<>();
        Object raw = manifest.get("externalChunks");
        if (!(raw instanceof List<?>list)) return result;
        Set<String> seen = new HashSet<>();
        for (Object entry : list) {
            int[] c = MigrationImporter.ints(entry);
            if (c == null || c.length != 4 || !seen.add(MoveRecord.key(c)))
                throw new IOException("Invalid external chunk manifest");
            result.add(c);
        }
        return result;
    }

    static void validate(File staging, Map<String, Object> manifest) throws IOException {
        for (int[] c : external(manifest)) {
            NBTTagCompound root = read(MigrationStorage.child(staging, path(c)));
            NBTTagCompound level = root.getCompoundTag("Level");
            if (!root.hasKey("Level", 10) || level.getInteger("xPos") != c[1]
                || level.getInteger("zPos") != c[2]
                || !level.hasKey("Sections", 9))
                throw new IOException("Invalid chunk coordinates/data: " + MoveRecord.key(c));
        }
    }

    /** Drain chunk and tile unload callbacks before writing incoming chunks. No region file is replaced. */
    static boolean prepare(Map<String, Object> manifest) throws IOException {
        boolean unloaded = false;
        for (int[] c : external(manifest)) {
            checkPlayers(c);
            WorldServer world = MinecraftServer.getServer()
                .worldServerForDimension(c[0]);
            if (world == null || world.provider.dimensionId != c[0]
                || !(world.theChunkProviderServer.currentChunkLoader instanceof AnvilChunkLoader))
                throw new IOException("Несовместимое измерение " + c[0]);
            ChunkCoordinates spawn = world.getSpawnPoint();
            if (world.provider.canRespawnHere() && DimensionManager.shouldLoadSpawn(c[0])
                && Math.abs((long) c[1] * 16 + 8 - spawn.posX) < 144
                && Math.abs((long) c[2] * 16 + 8 - spawn.posZ) < 144)
                throw new IOException("Перенос внешних спавн-чанков запрещён: " + MoveRecord.key(c));
            Chunk chunk = (Chunk) world.theChunkProviderServer.loadedChunkHashMap
                .getValueByKey(ChunkCoordIntPair.chunkXZ2Int(c[1], c[2]));
            if (chunk != null) {
                chunk.onChunkUnload();
                try {
                    world.theChunkProviderServer.currentChunkLoader.saveChunk(world, chunk);
                } catch (net.minecraft.world.MinecraftException e) {
                    throw new IOException(e);
                }
                world.theChunkProviderServer.loadedChunkHashMap.remove(ChunkCoordIntPair.chunkXZ2Int(c[1], c[2]));
                world.theChunkProviderServer.loadedChunks.remove(chunk);
                unloaded = true;
            }
        }
        return !unloaded;
    }

    static void install(File staging, Map<String, Object> manifest, File backup) throws IOException {
        try {
            ThreadedFileIOBase.threadedIOInstance.waitForFinish();
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new IOException(e);
        }
        for (int[] c : external(manifest)) {
            WorldServer world = DimensionManager.getWorld(c[0]);
            File folder = world.getSaveHandler()
                .getWorldDirectory();
            String dimensionFolder = world.provider.getSaveFolder();
            if (dimensionFolder != null) folder = MigrationStorage.child(folder, dimensionFolder);
            File copy = MigrationStorage.child(backup, path(c));
            Files.createDirectories(
                copy.toPath()
                    .getParent());
            try (java.io.DataInputStream input = RegionFileCache.getChunkInputStream(folder, c[1], c[2])) {
                if (input != null) {
                    NBTTagCompound old = CompressedStreamTools.read(input);
                    try (OutputStream output = Files.newOutputStream(copy.toPath())) {
                        CompressedStreamTools.writeCompressed(old, output);
                    }
                }
            }
            NBTTagCompound incoming = read(MigrationStorage.child(staging, path(c)));
            try (java.io.DataOutputStream output = RegionFileCache.getChunkOutputStream(folder, c[1], c[2])) {
                CompressedStreamTools.write(incoming, output);
            }
        }
    }

    private static NBTTagCompound read(File file) throws IOException {
        try (InputStream input = Files.newInputStream(file.toPath())) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    public static void activate(MoveRecord move) {
        ClaimedChunks.instance.processQueue();
        for (int[] c : move.claims) {
            ClaimedChunk claim = ClaimedChunks.instance.getChunk(new ChunkDimPos(c[1], c[2], c[0]));
            if (claim != null && claim.getTeam()
                .getId()
                .equals(move.team)) {
                claim.forced = null;
                claim.setLoaded(c[3] != 0);
            }
        }
        ClaimedChunks.instance.markDirty();
    }
}
