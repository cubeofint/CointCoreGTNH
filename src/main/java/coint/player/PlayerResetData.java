package coint.player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import betterquesting.handlers.SaveLoadHandler;
import coint.commands.temprank.TempRankManager;
import coint.integration.BackpackTransfer;
import coint.migration.MigrationExporter;
import coint.migration.MigrationGuard;
import coint.migration.MigrationImporter;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.ReflectionHelper;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

/** Local, offline reset steps. All calls run on the server thread under the shared reset/admission fence. */
public final class PlayerResetData {

    private static final String RECEIPTS = "cointcore_donation_receipts";
    private static final String PAID_KITS = "cointcore_kit_bonus_remaining";

    private PlayerResetData() {}

    private static ForgePlayer player(UUID id, String name) throws IOException {
        ForgePlayer known = Universe.get()
            .getPlayer(id);
        ForgePlayer byName = PlayerLookup.exact(name);
        if (byName != null && !byName.getId()
            .equals(id)) throw new IOException("Имя принадлежит другому UUID: " + name);
        return known == null ? new ForgePlayer(Universe.get(), id, name) : known;
    }

    private static ForgeTeam exactTeam(String id) {
        for (ForgeTeam team : Universe.get()
            .getTeams())
            if (team.getId()
                .equals(id)) return team;
        return null;
    }

    public static void validate(UUID id, String name, String mode, String successor) throws Exception {
        if (!name.matches("[A-Za-z0-9_.]{1,32}")) throw new IOException("Небезопасное имя игрока: " + name);
        if (MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList.stream()
                .anyMatch(
                    p -> p.getUniqueID()
                        .equals(id)))
            throw new IOException("Игрок ещё не завершил выход");
        if (MigrationExporter.isRunning() || MigrationImporter.isRunning() || MigrationGuard.loginReason(id) != null)
            throw new IOException("Сначала завершите перенос/заморозку игрока");
        File pending = new File("cointcore/migration-pending.json");
        if (pending.isFile()) {
            com.google.gson.JsonElement root = new com.google.gson.JsonParser()
                .parse(new String(Files.readAllBytes(pending.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            for (com.google.gson.JsonElement value : root.getAsJsonArray()) {
                com.google.gson.JsonObject entry = value.getAsJsonObject();
                if ((entry.has("epochs") && entry.getAsJsonObject("epochs")
                    .has(id.toString())) || (entry.has("waiting")
                        && entry.getAsJsonArray("waiting")
                            .toString()
                            .contains(id.toString())))
                    throw new IOException("Незавершённое восстановление migration-pending для " + id);
            }
        }
        ForgePlayer target = player(id, name);
        if (mode.equals("all")) PlayerResetManager.validateFullReset(
            target,
            successor == null ? null
                : Universe.get()
                    .getPlayer(UUID.fromString(successor)));
    }

    /** Only paid kit balances and delivery receipts survive; ordinary kit counters are personal progress. */
    public static String capturePaidData(UUID id) throws IOException {
        NBTTagCompound retained = new NBTTagCompound();
        ForgePlayer known = Universe.get()
            .getPlayer(id);
        if (known != null && known.hasTeam()) retained.setString("ResetTeamId", known.team.getId());
        File file = new File(root(), "playerdata/" + id + ".dat");
        if (file.isFile()) {
            NBTTagCompound data;
            try (FileInputStream in = new FileInputStream(file)) {
                data = CompressedStreamTools.readCompressed(in);
            }
            NBTTagCompound persisted = data.getCompoundTag("ForgeData")
                .getCompoundTag("PlayerPersisted");
            for (String key : new String[] { RECEIPTS, PAID_KITS }) {
                if (persisted.hasKey(key)) retained.setTag(
                    key,
                    persisted.getTag(key)
                        .copy());
                else if (data.getCompoundTag("PlayerPersisted")
                    .hasKey(key))
                    retained.setTag(
                        key,
                        data.getCompoundTag("PlayerPersisted")
                            .getTag(key)
                            .copy());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(retained, out);
        return Base64.getEncoder()
            .encodeToString(out.toByteArray());
    }

    private static NBTTagCompound decode(String value) throws IOException {
        return value == null ? new NBTTagCompound()
            : CompressedStreamTools.readCompressed(
                new ByteArrayInputStream(
                    Base64.getDecoder()
                        .decode(value)));
    }

    public static void apply(UUID id, String name, String mode, String successor, Collection<String> retained,
        String homeData, String localData, boolean destination) throws Exception {
        ForgePlayer target = player(id, name);
        ForgeTeam oldTeam = target.hasTeam() ? target.team : null;
        String originalTeam = decode(localData).getString("ResetTeamId");
        if (oldTeam == null && !originalTeam.isEmpty()) oldTeam = exactTeam(originalTeam);
        if (mode.equals("all")) PlayerResetManager.detachTeamsForFullReset(
            target,
            successor == null ? null
                : Universe.get()
                    .getPlayer(UUID.fromString(successor)));
        if (mode.equals("all") || mode.equals("quests")) {
            PlayerResetManager.resetQuests(target);
            // saveDatabases logs failures instead of throwing. Await the actual futures before acknowledging.
            SaveLoadHandler.INSTANCE.savePlayerProgress(id)
                .get(30, TimeUnit.SECONDS);
            Method parties = SaveLoadHandler.class.getDeclaredMethod("saveParties");
            parties.setAccessible(true);
            ((Future<?>) parties.invoke(SaveLoadHandler.INSTANCE)).get(30, TimeUnit.SECONDS);
        }
        if (mode.equals("all") || mode.equals("thaum")) {
            PlayerResetManager.resetThaum(target);
            if (!target.getName()
                .equals(name)) PlayerResetManager.resetThaum(new ForgePlayer(Universe.get(), id, name));
        }
        if (mode.equals("pdim")) PlayerResetManager.resetPersonalDimension(target);
        if (mode.equals("all") || mode.equals("data")) {
            PlayerResetManager.resetPlayerData(target);
            clearStats(id);
            if (Loader.isModLoaded("Baubles")) {
                Class<?> handler = Class.forName("baubles.common.lib.PlayerHandler");
                Method clear = handler.getMethod("clearPlayerBaubles", String.class, boolean.class);
                clear.invoke(null, target.getName(), false);
                clear.invoke(null, name, false);
            }
            deleteSidecars(id, name, target.getName());
            if (Loader.isModLoaded("Backpack")) {
                // Personal slot storage is separate from the item inventory. Clear its live cache through the
                // existing transfer API; shared backpack UUID stores may belong to surviving teammates.
                NBTTagCompound empty = new NBTTagCompound();
                BackpackTransfer.save("Player", id.toString(), empty);
                BackpackTransfer.flush();
                BackpackTransfer.verify("Player", id.toString(), empty);
            }
            writeRetained(target, retained, homeData, destination);
        }
        flush(target, oldTeam, mode.equals("all") || mode.equals("data"));
        if (mode.equals("all") && !originalTeam.isEmpty()) {
            ForgeTeam team = exactTeam(originalTeam);
            if (team == null || !team.isValid()) {
                File directory = new File(Universe.get().dataFolder, "teams").getCanonicalFile();
                File file = new File(directory, originalTeam + ".dat").getCanonicalFile();
                if (!file.getParentFile()
                    .equals(directory)) throw new IOException("Unsafe original team id");
                Files.deleteIfExists(file.toPath());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearStats(UUID id) throws Exception {
        Map<UUID, ?> stats = (Map<UUID, ?>) ReflectionHelper
            .findField(ServerConfigurationManager.class, "field_148547_k", "playerStatFiles")
            .get(
                MinecraftServer.getServer()
                    .getConfigurationManager());
        stats.remove(id);
    }

    private static void deleteSidecars(UUID id, String... names) throws IOException {
        File directory = new File(root(), "playerdata");
        File[] files = directory.listFiles();
        if (files == null && directory.exists()) throw new IOException("Cannot list " + directory);
        if (files == null) return;
        for (File file : files)
            for (String suffix : new String[] { ".dat", ".dat_old", ".dat.tmp", ".baub", ".baubback", ".baubbak" }) {
                boolean match = file.getName()
                    .equalsIgnoreCase(id + suffix);
                for (String name : names) match |= file.getName()
                    .equalsIgnoreCase(name + suffix);
                if (match) {
                    Files.deleteIfExists(file.toPath());
                    break;
                }
            }
    }

    private static void writeRetained(ForgePlayer target, Collection<String> snapshots, String homeData,
        boolean destination) throws IOException {
        NBTTagCompound receipts = new NBTTagCompound();
        for (String snapshot : snapshots) {
            NBTTagCompound values = decode(snapshot).getCompoundTag(RECEIPTS);
            for (Object key : values.func_150296_c())
                if (values.getBoolean(key.toString())) receipts.setBoolean(key.toString(), true);
        }
        NBTTagCompound persisted = new NBTTagCompound();
        persisted.setTag(RECEIPTS, receipts);
        // Paid balances have one authoritative owner. Never sum stale copies from previous home nodes.
        if (destination) persisted.setTag(PAID_KITS, decode(homeData).getCompoundTag(PAID_KITS));
        // Entity.readFromNBT treats missing Pos/Rotation/health as zero. Serialize a real fresh server player,
        // without logging him in, so Forge extended defaults and vanilla spawn/health/game type are complete.
        MinecraftServer server = MinecraftServer.getServer();
        WorldServer world = server.worldServerForDimension(0);
        EntityPlayerMP fresh = new EntityPlayerMP(server, world, target.getProfile(), new ItemInWorldManager(world));
        ChunkCoordinates spawn = world.getSpawnPoint();
        int y = Math.max(spawn.posY, world.getTopSolidOrLiquidBlock(spawn.posX, spawn.posZ));
        fresh.setLocationAndAngles(spawn.posX + 0.5, y, spawn.posZ + 0.5, 0, 0);
        fresh.dimension = 0;
        fresh.setHealth(fresh.getMaxHealth());
        fresh.theItemInWorldManager.setGameType(server.getGameType());
        NBTTagCompound data = new NBTTagCompound();
        fresh.writeToNBT(data);
        NBTTagCompound forge = data.getCompoundTag("ForgeData");
        forge.setTag("PlayerPersisted", persisted);
        data.setTag("ForgeData", forge);
        write(new File(root(), "playerdata/" + target.getId() + ".dat"), data);
    }

    /** SU's safe writer swallows exceptions, so strictly persist the reset player/team and verify reads. */
    private static void flush(ForgePlayer player, ForgeTeam previousTeam, boolean resetRanks) throws Exception {
        Method save = Universe.class.getDeclaredMethod("save");
        save.setAccessible(true);
        save.invoke(Universe.get());
        NBTTagCompound nbt = player.serializeNBT();
        nbt.setString("Name", player.getName());
        nbt.setString(
            "UUID",
            player.getId()
                .toString()
                .replace("-", ""));
        nbt.setString("TeamID", player.team == null || !player.team.isValid() ? "" : player.team.getId());
        if (Universe.get()
            .getPlayer(player.getId()) != null) write(player.getDataFile(), nbt);
        if (previousTeam != null) {
            if (previousTeam.isValid()) {
                NBTTagCompound team = previousTeam.serializeNBT();
                team.setString("ID", previousTeam.getId());
                team.setShort("UID", previousTeam.getUID());
                team.setString("Type", previousTeam.type.getName());
                write(previousTeam.getDataFile(""), team);
            } else Files.deleteIfExists(
                previousTeam.getDataFile("")
                    .toPath());
        }
        MinecraftServer.getServer()
            .worldServerForDimension(0).mapStorage.saveAllData();
        NBTTagCompound bindings = new NBTTagCompound(), savedBindings = new NBTTagCompound();
        TeamsManager.get()
            .writeToNBT(bindings);
        savedBindings.setTag("data", bindings);
        write(new File(root(), "data/COINT_Teams.dat"), savedBindings);
        if (!resetRanks) return;
        // Retry persistence even if a previous failed write already removed the in-memory entry.
        Method saveTemporary = TempRankManager.class.getDeclaredMethod("save");
        saveTemporary.setAccessible(true);
        saveTemporary.invoke(TempRankManager.get());
        serverutils.ranks.Ranks ranks = serverutils.ranks.Ranks.INSTANCE;
        if (ranks != null) {
            ranks.save();
            File rankFile = (File) ReflectionHelper.findField(serverutils.ranks.Ranks.class, "playersFile")
                .get(ranks);
            if (rankFile != null && rankFile.isFile()) {
                String compact = player.getId()
                    .toString()
                    .replace("-", "");
                for (String line : Files.readAllLines(rankFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                    String value = line.trim()
                        .replace("-", "");
                    if (value.equalsIgnoreCase("[" + compact + "]"))
                        throw new IOException("Player ranks were not removed from " + rankFile);
                }
            }
        }
        // Replicas are rebuilt from the scrubbed shared snapshot.
        if (TempRankManager.get()
            .getAllEntries()
            .stream()
            .anyMatch(e -> e.playerUuid.equals(player.getId())) && !TempRankManager.replica())
            throw new IOException("Temporary ranks still contain reset player");
    }

    private static void write(File file, NBTTagCompound nbt) throws IOException {
        Files.createDirectories(
            file.toPath()
                .getParent());
        File temporary = new File(file.getPath() + ".reset-tmp");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(nbt, compressed);
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(compressed.toByteArray());
            out.getFD()
                .sync();
        }
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        try (FileInputStream in = new FileInputStream(file)) {
            if (!nbt.equals(CompressedStreamTools.readCompressed(in)))
                throw new IOException("Reset save verification failed: " + file);
        }
    }

    private static File root() throws IOException {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        if (root == null) throw new IOException("World directory unavailable");
        return root;
    }
}
