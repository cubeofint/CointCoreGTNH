package coint.migration;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChunkCoordinates;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import coint.commands.CommandMigrate;
import coint.player.TeamsManager;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import me.eigenraven.personalspace.net.Packets;
import me.eigenraven.personalspace.world.DimensionConfig;
import serverutils.lib.data.ForgeTeam;
import serverutils.lib.data.Universe;

/**
 * Brings a team exported by {@link MigrationExporter} into this running server: the zip is unpacked in the background,
 * then, on the server thread and with every member offline, the personal worlds and player files are put in place
 * and registered, quests, party and the Blood Magic network are loaded, and the team with its claims, epoch ranks and
 * dimension binding is set up (for players this server has never seen, when they first join: {@link MigrationPending}).
 *
 * <p>
 * Whatever the import replaces is kept in {@code migrations/replaced/<stamp>/}.
 */
@EventBusSubscriber
public final class MigrationImporter {

    private static final Gson GSON = new Gson();
    private static final String MANIFEST = "migration-manifest.json";
    private static final String DIM_METADATA = "personalspace_metadata.cfg";
    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> MAIN_THREAD = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile boolean running;
    private static volatile Operation activeOperation;

    private static final class Operation {

        final ICommandSender sender;
        final MigrationStorage.Session session;
        final File original, workspace, staging, snapshot, archiveDirectory;
        final Path lock;
        final java.util.function.Consumer<Boolean> done;
        final String expectedSource;
        final MoveRecord move;
        Thread worker;
        boolean preparing = true, cancelled, cleaned;

        Operation(ICommandSender sender, MigrationStorage.Session session, File original, Path lock,
            String expectedSource, java.util.function.Consumer<Boolean> done, MoveRecord move) {
            this.sender = sender;
            this.session = session;
            this.original = original;
            this.lock = lock;
            this.expectedSource = expectedSource;
            this.done = done;
            this.move = move;
            archiveDirectory = original.getParentFile();
            workspace = new File(session.world.getParentFile(), "migrations/.import-" + UUID.randomUUID());
            staging = new File(workspace, "unpacked");
            snapshot = new File(workspace, original.getName());
        }

        boolean current() {
            return !cancelled && activeOperation == this && session.current();
        }

        synchronized void clean() {
            if (cleaned) return;
            cleaned = true;
            deleteQuietly(workspace);
            try {
                Files.deleteIfExists(lock);
            } catch (IOException e) {
                CointCore.LOG.error("[Migrate] Could not release shared import lock {}", lock, e);
            }
        }
    }

    public static void stop() {
        Operation operation = activeOperation;
        activeOperation = null;
        running = false;
        if (operation != null) {
            synchronized (operation) {
                operation.cancelled = true;
                if (operation.worker != null) operation.worker.interrupt();
                if (!operation.preparing) operation.clean();
            }
        }
        MAIN_THREAD.clear();
        later.clear();
        laterTasks.clear();
        tick = 0;
    }

    private MigrationImporter() {}

    public static boolean isRunning() {
        return running;
    }

    /** The zips in migrations/ that can be imported. */
    public static List<String> available() {
        List<String> names = new ArrayList<>();
        File[] zips = MigrationStorage.archives()
            .listFiles(f -> f.isFile() && MigrationStorage.validArchiveName(f.getName()));
        if (zips != null) {
            for (File zip : zips) {
                names.add(zip.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    static File serverDir() {
        return DimensionManager.getCurrentSaveRootDirectory()
            .getAbsoluteFile()
            .getParentFile();
    }

    static File migrationsDir() {
        return MigrationStorage.localDirectory();
    }

    /** Runs on the server thread. Null when the import started, else why not. */
    public static String start(ICommandSender sender, String fileName) {
        return start(sender, fileName, success -> {});
    }

    /** Completion runs on the server thread, once the existing validation/apply path finishes. */
    public static String start(ICommandSender sender, String fileName, java.util.function.Consumer<Boolean> done) {
        return start(sender, fileName, null, null, done);
    }

    public static String start(ICommandSender sender, String fileName, String expectedSha256, String expectedSourceNode,
        java.util.function.Consumer<Boolean> done) {
        return start(sender, fileName, expectedSha256, expectedSourceNode, done, null);
    }

    static String start(ICommandSender sender, String fileName, String expectedSha256, String expectedSourceNode,
        java.util.function.Consumer<Boolean> done, MoveRecord move) {
        if (!CommandMigrate.allowed(sender)) return "Нет права на миграцию";
        if (running || MigrationExporter.isRunning()) {
            return "Уже идёт перенос, дождитесь его окончания";
        }
        if (!MigrationStorage.validArchiveName(fileName)) {
            return "Недопустимое имя ZIP: укажите только имя архива";
        }
        File zip = new File(MigrationStorage.archives(), fileName);
        if (!zip.isFile()) {
            return "Нет файла " + zip.getAbsolutePath();
        }
        if (expectedSha256 != null && !expectedSha256.matches("[a-fA-F0-9]{64}")) return "Некорректный SHA-256";
        final Operation operation;
        try {
            MigrationStorage.Session session = MigrationStorage.session();
            session.check();
            operation = new Operation(sender, session, zip, MigrationStorage.lock(), expectedSourceNode, done, move);
        } catch (IOException e) {
            return "Общая папка недоступна или занята импортом (.import.lock): " + e.getMessage();
        }
        running = true;
        activeOperation = operation;
        sender.addChatMessage(new ChatComponentText("§eРаспаковываю §f" + zip.getName() + "§e..."));
        Thread thread = new Thread(() -> {
            String error = null;
            try {
                operation.session.check();
                Files.createDirectories(operation.workspace.toPath());
                // Only this local snapshot is hashed, unpacked, and later retained as the imported archive.
                Files.copy(zip.toPath(), operation.snapshot.toPath());
                String hash = MigrationStorage.sha256(operation.snapshot.toPath());
                if (expectedSha256 != null && !hash.equalsIgnoreCase(expectedSha256)) {
                    throw new IOException("SHA-256 архива отличается от подтверждённого запроса");
                }
                operation.session.check();
                unzip(operation.snapshot, operation.staging);
                Map<String, Object> manifest = readManifest(operation.staging);
                if (expectedSourceNode != null && !expectedSourceNode.equals(str(manifest.get("sourceNode")))) {
                    throw new IOException("sourceNode архива отличается от подтверждённого запроса");
                }
                validatePaths(operation, manifest);
                operation.session.check();
            } catch (IOException | RuntimeException e) {
                CointCore.LOG.error("[Migrate] Could not unpack {}", zip, e);
                error = "Не удалось распаковать архив: " + e.getMessage();
            }
            synchronized (operation) {
                operation.preparing = false;
                if (operation.current()) {
                    String failed = error;
                    MAIN_THREAD.add(() -> attempt(operation, failed, 0));
                } else operation.clean();
            }
        }, "CointCore migration import");
        thread.setDaemon(true);
        operation.worker = thread;
        thread.start();
        return null;
    }

    /** One try at the import; a personal world still loaded here is unloaded and the import tried again shortly. */
    private static void attempt(Operation operation, String failed, int tries) {
        if (!operation.current()) {
            operation.clean();
            if (activeOperation == operation) {
                activeOperation = null;
                running = false;
            }
            return;
        }
        ICommandSender sender = operation.sender;
        File zip = operation.snapshot;
        File staging = operation.staging;
        boolean again = false;
        boolean success = false;
        try {
            String result = !CommandMigrate.allowed(sender) ? "право на миграцию отозвано"
                : failed != null ? failed : apply(operation);
            if (result == RETRY && tries < 10) {
                again = true;
                if (tries == 0) {
                    sender.addChatMessage(new ChatComponentText("§eПерсональный мир здесь загружен, выгружаю его..."));
                }
                later.add(new long[] { tick + 40 });
                laterTasks.add(() -> attempt(operation, null, tries + 1));
            } else if (result == RETRY) {
                sender.addChatMessage(
                    new ChatComponentText("§cИмпорт не выполнен: персональный мир не выгружается (чанклоадеры?)"));
            } else if (result != null) {
                sender.addChatMessage(new ChatComponentText("§cИмпорт не выполнен: " + result));
            } else {
                success = true;
            }
        } catch (RuntimeException e) {
            CointCore.LOG.error("[Migrate] Import of {} failed", zip, e);
            sender.addChatMessage(new ChatComponentText("§cИмпорт прерван ошибкой: " + e + ". Подробности в логе."));
        } finally {
            if (!again) {
                boolean notify = operation.current();
                operation.clean();
                if (activeOperation == operation) {
                    activeOperation = null;
                    running = false;
                }
                if (notify && operation.done != null) operation.done.accept(success);
            }
        }
    }

    private static final String RETRY = "retry";
    private static long tick;
    private static final List<long[]> later = new ArrayList<>();
    private static final List<Runnable> laterTasks = new ArrayList<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick++;
        for (int i = later.size() - 1; i >= 0; i--) {
            if (later.get(i)[0] <= tick) {
                later.remove(i);
                MAIN_THREAD.add(laterTasks.remove(i));
            }
        }
        Runnable task;
        while ((task = MAIN_THREAD.poll()) != null) {
            task.run();
        }
    }

    // ---------------------------------------------------------------- the import itself

    private static Map<String, Object> readManifest(File staging) throws IOException {
        try (Reader reader = new InputStreamReader(
            new FileInputStream(new File(staging, MANIFEST)),
            StandardCharsets.UTF_8)) {
            Map<String, Object> manifest = GSON.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
            if (manifest == null) throw new IOException("Empty manifest");
            return manifest;
        } catch (RuntimeException e) {
            throw new IOException("Invalid manifest", e);
        }
    }

    /** All paths, metadata IDs and player names are checked together before unloading or moving anything. */
    private static void validatePaths(Operation operation, Map<String, Object> manifest) throws IOException {
        if (manifest.containsKey("moveId")) {
            if (operation.move == null || !operation.move.id.equals(str(manifest.get("moveId"))))
                throw new IOException("Архив перемещения разрешён только своему журналу /migrate move");
            MigrationMove.validateManifest(operation.move, manifest);
        } else if (operation.move != null || !MigrationChunks.external(manifest)
            .isEmpty()) {
                throw new IOException("Missing move identity");
            }
        MigrationChunks.validate(operation.staging, manifest);
        String world = str(manifest.get("worldFolder"));
        if (world.isEmpty() || !world.equals(new File(world).getName())
            || world.contains("/")
            || world.contains("\\")) {
            throw new IOException("Invalid worldFolder: " + world);
        }
        File source = MigrationStorage.child(operation.staging, world);
        if (!source.isDirectory()) throw new IOException("Missing world folder: " + world);
        File destination = operation.session.world;
        File backupRoot = MigrationStorage.child(destination.getParentFile(), "migrations/replaced");
        Set<Integer> dimensions = new HashSet<>();
        Set<String> folders = new HashSet<>();
        for (Map<String, Object> dim : list(manifest.get("personalDimensions"))) {
            double number = num(dim.get("dimension"));
            int id = (int) number;
            MigrationStations.Station station = MigrationStations.get(operation.move, id);
            String folder = str(dim.get("folder"));
            if (number != id || id <= 1
                || !folder.equals(MigrationStations.folder(operation.move, id))
                || !dimensions.add(id)
                || !folders.add(folder)) {
                throw new IOException("Invalid personal dimension/folder: " + id + " / " + folder);
            }
            File incoming = MigrationStorage.child(source, folder);
            MigrationStorage.child(destination, folder);
            MigrationStorage.child(backupRoot, folder);
            if (dim.get("missing") == null) {
                if (station != null) {
                    if (!incoming.isDirectory()) throw new IOException("Нет папки станции " + id);
                    MigrationStations.same(
                        station,
                        MigrationStations
                            .read(MigrationStorage.child(source, MigrationStations.metadata(station)), id));
                    continue;
                }
                File metadata = MigrationStorage.child(incoming, DIM_METADATA);
                if (!incoming.isDirectory() || !metadata.isFile())
                    throw new IOException("Missing personal dimension metadata: " + folder);
                net.minecraftforge.common.config.Configuration config = new net.minecraftforge.common.config.Configuration(
                    metadata);
                config.load();
                if (config.get("worldgen", "dimId", Integer.MIN_VALUE)
                    .getInt() != id) {
                    throw new IOException("Personal dimension metadata ID mismatch: " + folder);
                }
            }
        }
        Set<String> uuids = new HashSet<>();
        for (Map<String, Object> player : list(manifest.get("players"))) {
            String uuid = str(player.get("uuid"));
            String name = str(player.get("name"));
            if (!UUID.fromString(uuid)
                .toString()
                .equals(uuid) || !uuids.add(uuid) || !name.matches("[A-Za-z0-9_]{1,16}")) {
                throw new IOException("Invalid player identity");
            }
            for (String relative : new String[] { "playerdata/" + uuid + ".dat", "stats/" + uuid + ".json",
                "gtnhteams/" + uuid + ".json", "betterquesting/QuestProgress/" + uuid + ".json" }) {
                MigrationStorage.child(source, relative);
                MigrationStorage.child(destination, relative);
                MigrationStorage.child(backupRoot, relative);
            }
            for (File file : playerFiles(source, uuid, name)) {
                String relative = source.toPath()
                    .relativize(file.toPath())
                    .toString()
                    .replace(File.separatorChar, '/');
                MigrationStorage.child(source, relative);
                MigrationStorage.child(destination, relative);
                MigrationStorage.child(backupRoot, relative);
            }
        }
    }

    /** On the server thread, after unpacking. Null when done, else why nothing was changed. */
    private static String apply(Operation operation) {
        if (!operation.current() || !CommandMigrate.allowed(operation.sender))
            return "сессия или право миграции изменились";
        ICommandSender sender = operation.sender;
        File zip = operation.snapshot;
        File staging = operation.staging;
        Map<String, Object> manifest;
        try {
            manifest = readManifest(staging);
            validatePaths(operation, manifest);
        } catch (IOException | RuntimeException e) {
            return "нет или битый " + MANIFEST + " (" + e.getMessage() + ")";
        }
        if (num(manifest.get("format")) < MigrationData.FORMAT) {
            return "архив сделан старой версией экспорта: сделайте экспорт заново";
        }

        // 1. Checks: nothing is touched unless all of them pass.
        @SuppressWarnings("unchecked")
        List<String> mismatches = MigrationData
            .idMismatches((Map<String, Map<String, Number>>) (Map<?, ?>) manifest.get("idMap"));
        if (!mismatches.isEmpty()) {
            CointCore.LOG.warn("[Migrate] ID map differs from {}: {}", zip.getName(), mismatches);
            return "карта ID блоков/предметов отличается (" + mismatches.size()
                + "), например: "
                + String.join("; ", mismatches.subList(0, Math.min(3, mismatches.size())))
                + ". Мир должен быть копией исходного.";
        }
        File worldDir = operation.session.world;
        String sourceWorld = str(manifest.get("worldFolder"));
        File sourceRoot = new File(staging, sourceWorld);
        if (sourceWorld.isEmpty() || !sourceRoot.isDirectory()) {
            return "в архиве нет папки мира " + sourceWorld;
        }
        List<Map<String, Object>> players = list(manifest.get("players"));
        Map<String, Object> team = map(manifest.get("team"));
        List<Map<String, Object>> dims = list(manifest.get("personalDimensions"));
        for (Map<String, Object> p : players) {
            EntityPlayerMP online = online(UUID.fromString(str(p.get("uuid"))));
            if (online != null) {
                return "игрок " + online.getCommandSenderName() + " сейчас на этом сервере: вся команда должна выйти";
            }
        }
        Set<Integer> dimIds = new HashSet<>();
        String teamId = team == null ? "" : str(team.get("id"));
        for (Map<String, Object> d : dims) {
            int dim = (int) num(d.get("dimension"));
            MigrationStations.Station station = MigrationStations.get(operation.move, dim);
            if (d.get("missing") != null) {
                continue;
            }
            dimIds.add(dim);
            if (station == null && DimensionManager.isDimensionRegistered(dim)
                && DimensionConfig.getForDimension(dim, false) == null) {
                return "измерение " + dim + " занято другим модом";
            }
            if (DimensionManager.getWorld(dim) != null) {
                if (!DimensionManager.getWorld(dim).playerEntities.isEmpty()) {
                    return "в измерении " + dim + " сейчас есть игроки";
                }
                DimensionManager.unloadWorld(dim);
                return RETRY;
            }
            Short owner = station != null ? null
                : TeamsManager.get()
                    .getTeamUidForDim(dim);
            ForgeTeam holder = owner == null ? null
                : Universe.get()
                    .getTeam(owner);
            if (holder != null && holder.isValid()
                && !holder.getId()
                    .equals(teamId)) {
                return "измерение " + dim + " здесь принадлежит другой команде (" + holder.getId() + ")";
            }
        }

        // 2. Files. Whatever is replaced goes to migrations/replaced/<stamp>/.
        if (operation.move != null) {
            try {
                MigrationChunks.checkClaims(operation.move, true);
                MigrationGlobalData.preflight(operation.move);
                MigrationGlobalData.read(staging);
                if (!MigrationChunks.prepare(manifest)) return RETRY;
            } catch (IOException e) {
                return e.getMessage();
            }
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "-" + UUID.randomUUID();
        File replaced = new File(worldDir.getParentFile(), "migrations/replaced/" + stamp);
        if (!operation.current() || !CommandMigrate.allowed(sender)) return "сессия или право миграции изменились";
        int[] entry = team == null ? null : ints(team.get("entry"));
        try {
            if (operation.move != null) {
                MigrationGlobalData.write(replaced, operation.move);
                backupServerUtilities(operation.move, replaced);
                MigrationChunks.install(staging, manifest, replaced);
            }
            for (Map<String, Object> d : dims) {
                if (d.get("missing") != null) {
                    continue;
                }
                String folder = str(d.get("folder"));
                move(new File(sourceRoot, folder), new File(worldDir, folder), new File(replaced, folder));
                int id = (int) num(d.get("dimension"));
                if (MigrationStations.get(operation.move, id) != null) {
                    String metadata = MigrationStations.metadata(MigrationStations.get(operation.move, id));
                    move(
                        MigrationStorage.child(sourceRoot, metadata),
                        MigrationStorage.child(worldDir, metadata),
                        MigrationStorage.child(replaced, metadata));
                }
            }
            for (Map<String, Object> p : players) {
                String uuid = str(p.get("uuid"));
                File data = new File(sourceRoot, "playerdata/" + uuid + ".dat");
                if (data.isFile()) {
                    placePlayer(data, dimIds, entry);
                }
                for (File file : playerFiles(sourceRoot, uuid, str(p.get("name")))) {
                    String rel = sourceRoot.toPath()
                        .relativize(file.toPath())
                        .toString();
                    move(file, new File(worldDir, rel), new File(replaced, rel));
                }
            }
        } catch (IOException e) {
            CointCore.LOG.error("[Migrate] Moving files of {} failed", zip.getName(), e);
            return "ошибка при раскладке файлов (" + e.getMessage()
                + "). Часть файлов уже на месте, заменённые лежат в migrations/replaced/"
                + stamp;
        }

        // 3. The personal worlds become known to Personal Space (and to everyone's client).
        List<String> notes = new ArrayList<>();
        for (Map<String, Object> d : dims) {
            if (d.get("missing") != null) {
                continue;
            }
            File folder = new File(worldDir, str(d.get("folder")));
            MigrationStations.Station station = MigrationStations.get(operation.move, (int) num(d.get("dimension")));
            if (station != null) {
                try {
                    MigrationStations.register(operation.move, station, worldDir);
                } catch (IOException | RuntimeException e) {
                    return "Ошибка регистрации станции " + station.id + ": " + e.getMessage();
                }
                continue;
            }
            DimensionConfig config = new DimensionConfig();
            int dim = config.syncWithFile(new File(folder, DIM_METADATA), false, 0);
            if (dim != (int) num(d.get("dimension"))) {
                notes.add(
                    "§eв настройках мира " + folder.getName() + " номер " + dim + ", ожидался " + d.get("dimension"));
            }
            config.setSaveDirOverride(folder.getName());
            config.registerWithDimensionManager(dim, false);
        }
        if (!dims.isEmpty()) {
            Packets.INSTANCE.sendWorldList()
                .sendToClients();
        }

        // 4. Quests, party, Blood Magic.
        if (Loader.isModLoaded("betterquesting")) {
            for (Map<String, Object> p : players) {
                String uuid = str(p.get("uuid"));
                File progress = new File(sourceRoot, "betterquesting/QuestProgress/" + uuid + ".json");
                if (progress.isFile()) {
                    QuestData.mergeProgress(progress, UUID.fromString(uuid));
                }
            }
            File party = new File(staging, MigrationData.PARTY_FILE);
            if (party.isFile()) {
                try (InputStream in = new ByteArrayInputStream(Files.readAllBytes(party.toPath()))) {
                    QuestData.restoreParty(CompressedStreamTools.readCompressed(in));
                } catch (IOException e) {
                    notes.add("§eпати не восстановлена: " + e.getMessage());
                }
            }
        }
        if (Loader.isModLoaded("AWWayofTime")) {
            for (Map<String, Object> p : players) {
                if (p.get("bloodEssence") != null) {
                    BloodMagicData
                        .restore(str(p.get("name")), (int) num(p.get("bloodEssence")), (int) num(p.get("bloodOrb")));
                }
            }
        }

        // 5. Team, claims, epoch ranks, dimension binding: now, or when each player first joins.
        if (operation.move != null) {
            try {
                MigrationGlobalData.apply(MigrationGlobalData.read(staging), operation.move, false);
            } catch (IOException e) {
                return e.getMessage();
            }
        }
        MigrationPending.Entry pending = MigrationPending.Entry.from(manifest);
        if (operation.move != null) {
            for (int i = 0; i < operation.move.players.size(); i++) {
                UUID id = UUID.fromString(operation.move.players.get(i));
                Universe universe = Universe.get();
                if (universe.getPlayer(id) == null) {
                    serverutils.lib.data.ForgePlayer player = new serverutils.lib.data.ForgePlayer(
                        universe,
                        id,
                        operation.move.names.get(i));
                    universe.players.put(id, player);
                    player.markDirty();
                }
                serverutils.lib.data.ForgePlayer player = universe.getPlayer(id);
                File su = new File(
                    sourceRoot,
                    "serverutilities/players/" + operation.move.names.get(i)
                        .toLowerCase(java.util.Locale.ROOT) + ".dat");
                if (su.isFile()) {
                    try (InputStream input = Files.newInputStream(su.toPath())) {
                        player.deserializeNBT(CompressedStreamTools.readCompressed(input));
                    } catch (IOException e) {
                        return "Не удалось восстановить данные SU игрока: " + e.getMessage();
                    }
                }
                player.clearCache();
                player.markDirty();
            }
        }
        String state = MigrationPending.add(pending);
        if (operation.move != null && operation.move.joining()) {
            try {
                MigrationJoining.install(operation.move);
            } catch (IOException e) {
                return e.getMessage();
            }
        }
        if (operation.move != null && !operation.move.joining() && pending.teamDone) {
            File teamFile = new File(sourceRoot, "serverutilities/teams/" + operation.move.team + ".dat");
            ForgeTeam restored = Universe.get()
                .getTeam(operation.move.team);
            if (teamFile.isFile() && restored != null && restored.isValid()) {
                try (InputStream input = Files.newInputStream(teamFile.toPath())) {
                    restored.getData()
                        .deserializeNBT(
                            CompressedStreamTools.readCompressed(input)
                                .getCompoundTag("Data"));
                    restored.clearCache();
                    restored.markDirty();
                } catch (IOException e) {
                    return "Не удалось восстановить настройки команды SU: " + e.getMessage();
                }
            }
        }
        if (operation.move != null && (!pending.finished() || !notes.isEmpty()))
            return "Импорт требует проверки: " + state + "; " + notes;
        if (operation.move != null) {
            try {
                MigrationExporter.saveEverything(MinecraftServer.getServer());
            } catch (Exception e) {
                return "Не удалось сохранить импорт: " + e.getMessage();
            }
        }

        File done = new File(operation.archiveDirectory, "imported/" + zip.getName());
        try {
            Files.createDirectories(
                done.getParentFile()
                    .toPath());
            // Keep exactly the bytes that were verified. Never move/delete the mutable shared input by name.
            Files.copy(zip.toPath(), done.toPath());
        } catch (IOException e) {
            notes.add("§eархив не перемещён в " + done.getParent() + ": " + e.getMessage());
        }
        CointCore.LOG.info("[Migrate] Imported {} ({} players, worlds {})", zip.getName(), players.size(), dimIds);
        sender.addChatMessage(
            new ChatComponentText(
                "§aИмпорт готов: §f" + players
                    .size() + "§a игрок(ов), персональные миры §f" + dimIds + "§a. " + state));
        for (String note : notes) {
            sender.addChatMessage(new ChatComponentText(note));
        }
        return null;
    }

    private static void backupServerUtilities(MoveRecord move, File backup) throws IOException {
        for (int i = 0; i < move.players.size(); i++) {
            serverutils.lib.data.ForgePlayer player = Universe.get()
                .getPlayer(UUID.fromString(move.players.get(i)));
            if (player == null) continue;
            File file = MigrationStorage.child(
                backup,
                "serverutilities/players/" + move.names.get(i)
                    .toLowerCase(java.util.Locale.ROOT) + ".dat");
            Files.createDirectories(
                file.toPath()
                    .getParent());
            try (java.io.OutputStream output = Files.newOutputStream(file.toPath())) {
                CompressedStreamTools.writeCompressed(player.serializeNBT(), output);
            }
        }
        ForgeTeam team = Universe.get()
            .getTeam(move.joining() ? move.joinTeam : move.team);
        if (team != null && team.isValid()) {
            File file = MigrationStorage.child(backup, "serverutilities/teams/" + team.getId() + ".dat");
            Files.createDirectories(
                file.toPath()
                    .getParent());
            try (java.io.OutputStream output = Files.newOutputStream(file.toPath())) {
                CompressedStreamTools.writeCompressed(team.serializeNBT(), output);
            }
        }
    }

    /** Vanilla and mod files of one player inside the unpacked world: quest progress is merged, not copied. */
    private static List<File> playerFiles(File root, String uuid, String name) {
        List<File> files = new ArrayList<>();
        files.add(new File(root, "playerdata/" + uuid + ".dat"));
        File[] mod = new File(root, "playerdata").listFiles(
            (d, n) -> n.equalsIgnoreCase(name + ".thaum") || n.equalsIgnoreCase(name + ".thaumback")
                || n.equalsIgnoreCase(name + ".baub")
                || n.equalsIgnoreCase(name + ".baubback"));
        if (mod != null) {
            Collections.addAll(files, mod);
        }
        files.add(new File(root, "stats/" + uuid + ".json"));
        files.add(new File(root, "gtnhteams/" + uuid + ".json"));
        files.removeIf(f -> !f.isFile());
        return files;
    }

    /** A player who was not in his personal world is put at its entrance (or this server's spawn). */
    private static void placePlayer(File data, Set<Integer> dims, int[] entry) throws IOException {
        NBTTagCompound nbt;
        try (InputStream in = new BufferedInputStream(new FileInputStream(data))) {
            nbt = CompressedStreamTools.readCompressed(in);
        }
        if (dims.contains(nbt.getInteger("Dimension"))) {
            return;
        }
        double x, y, z;
        int dim;
        if (entry != null && entry.length == 4 && dims.contains(entry[0])) {
            dim = entry[0];
            x = entry[1] + 0.5;
            y = entry[2];
            z = entry[3] + 0.5;
        } else {
            ChunkCoordinates spawn = MinecraftServer.getServer()
                .worldServerForDimension(0)
                .getSpawnPoint();
            dim = 0;
            x = spawn.posX + 0.5;
            y = spawn.posY + 1;
            z = spawn.posZ + 0.5;
        }
        nbt.setInteger("Dimension", dim);
        nbt.setFloat("FallDistance", 0);
        NBTTagList pos = new NBTTagList();
        pos.appendTag(new NBTTagDouble(x));
        pos.appendTag(new NBTTagDouble(y));
        pos.appendTag(new NBTTagDouble(z));
        nbt.setTag("Pos", pos);
        try (java.io.OutputStream out = new java.io.FileOutputStream(data)) {
            CompressedStreamTools.writeCompressed(nbt, out);
        }
    }

    /** Puts {@code from} at {@code to}, moving what was there into {@code backup}. */
    private static void move(File from, File to, File backup) throws IOException {
        if (!from.exists()) {
            return;
        }
        if (to.exists()) {
            Files.createDirectories(
                backup.getParentFile()
                    .toPath());
            Files.move(to.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(
            to.getParentFile()
                .toPath());
        Files.move(from.toPath(), to.toPath());
    }

    private static void unzip(File zip, File into) throws IOException {
        Path root = into.toPath()
            .toAbsolutePath()
            .normalize();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = root.resolve(e.getName())
                    .normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Недопустимый путь в архиве: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteQuietly(File dir) {
        if (!dir.exists()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted((a, b) -> b.compareTo(a))
                .forEach(
                    path -> path.toFile()
                        .delete());
        } catch (IOException e) {
            CointCore.LOG.warn("[Migrate] Could not clean {}", dir, e);
        }
    }

    static EntityPlayerMP online(UUID uuid) {
        for (Object o : MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (p.getUniqueID()
                .equals(uuid)) {
                return p;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- manifest values

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?>m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) {
        return o instanceof List<?>l ? (List<Map<String, Object>>) l : Collections.emptyList();
    }

    static int[] ints(Object o) {
        if (!(o instanceof List<?>l)) {
            return null;
        }
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (int) num(l.get(i));
        }
        return out;
    }
}
