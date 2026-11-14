package coint.migration;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import coint.cluster.ClusterService;
import coint.commands.CommandMigrate;
import coint.integration.personalspace.PersonalSpaceBinding;
import coint.player.TeamsManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import me.eigenraven.personalspace.world.DimensionConfig;
import serverutils.data.ClaimedChunk;
import serverutils.data.ClaimedChunks;
import serverutils.lib.data.ForgePlayer;
import serverutils.lib.data.ForgeTeam;

/**
 * Packs everything a base needs to move to another server into one zip: the team's personal worlds and the
 * per-player / per-team files of vanilla and mods. Paths inside the zip mirror the server folder, so extracting it in
 * the target server's root puts every file in place.
 *
 * <p>
 * Files are copied on the server thread right after a full save, so the snapshot is consistent even though
 * chunk-loaded bases keep ticking; compression runs in the background afterwards.
 */
@EventBusSubscriber
public final class MigrationExporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .create();
    private static final ConcurrentLinkedQueue<Runnable> MAIN_THREAD = new ConcurrentLinkedQueue<>();
    private static volatile boolean running;
    private static volatile MigrationStorage.Session exportSession;
    private static Thread worker;

    public static synchronized void stop() {
        exportSession = null;
        if (worker != null) worker.interrupt();
        worker = null;
        running = false;
        MAIN_THREAD.clear();
    }

    private MigrationExporter() {}

    public static boolean isRunning() {
        return running;
    }

    /** Runs on the server thread. Returns null on success, or the reason the export could not start. */
    public static String export(ICommandSender sender, ForgePlayer target) {
        return export(sender, target, archive -> {});
    }

    public static String export(ICommandSender sender, ForgePlayer target, java.util.function.Consumer<String> ready) {
        return export(sender, target, ready, null);
    }

    static String export(ICommandSender sender, ForgePlayer target, java.util.function.Consumer<String> ready,
        MoveRecord move) {
        if (!CommandMigrate.allowed(sender)) return "Нет права на миграцию";
        if (running || MigrationImporter.isRunning()) {
            return "Уже идёт другой экспорт, дождитесь его окончания";
        }
        MinecraftServer server = MinecraftServer.getServer();
        final MigrationStorage.Session session;
        try {
            session = MigrationStorage.session();
            session.check();
        } catch (IOException e) {
            return e.getMessage();
        }
        ForgeTeam team = move != null && move.joining() ? null : target.hasTeam() ? target.team : null;

        List<ForgePlayer> players = new ArrayList<>();
        if (team != null) {
            players.addAll(team.getMembers());
        } else {
            players.add(target);
        }
        for (ForgePlayer player : players) {
            if (player.isOnline()) {
                return "Игрок " + player.getName() + " в сети. Для экспорта вся команда должна быть офлайн";
            }
        }

        Set<Integer> dims = findTeamDimensions(team);
        if (move != null) for (MigrationStations.Station station : move.stations) dims.add(station.id);
        if (move != null && !dims.equals(new LinkedHashSet<>(move.dimensions))) return "Список персоналок изменился";
        File worldDir = session.world;
        String worldName = worldDir.getName();

        // 1. Same as /save-all: players, every world (fires WorldEvent.Save, so ServerUtilities and
        // BetterQuesting write their files too), then wait for the chunk IO thread to flush.
        try {
            saveEverything(server);
        } catch (Exception e) {
            CointCore.LOG.error("[Migrate] Save before export failed", e);
            return "Не удалось сохранить мир перед экспортом: " + e.getMessage();
        }

        // 2. Collect the files.
        Map<File, String> files = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<Map<String, Object>> dimInfo = new ArrayList<>();
        try {
            for (int dimId : dims) {
                MigrationStations.Station station = MigrationStations.get(move, dimId);
                DimensionConfig config = DimensionConfig.getForDimension(dimId, false);
                String folder = station != null ? MigrationStations.folder(station)
                    : config == null ? "PERSONAL_DIM_" + dimId : config.getSaveDir(dimId);
                if (dimId <= 1 || !folder.equals(MigrationStations.folder(move, dimId)))
                    throw new IOException("Unsupported personal dimension folder: " + folder);
                File dimDir = MigrationStorage.child(worldDir, folder);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("dimension", dimId);
                info.put("folder", folder);
                if (station != null) {
                    MigrationStations.check(move, station, false);
                    MigrationMotherships.snapshot(move, station, worldDir);
                    MigrationStations.same(
                        station,
                        MigrationStations
                            .read(MigrationStorage.child(worldDir, MigrationStations.metadata(station)), dimId));
                    addFile(worldDir, worldName, MigrationStations.metadata(station), files, missing);
                }
                if (dimDir.isDirectory()) {
                    addTree(dimDir, worldName + "/" + folder, files);
                } else {
                    missing.add(worldName + "/" + folder);
                    info.put("missing", true);
                }
                dimInfo.add(info);
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            CointCore.LOG.error("[Migrate] Could not collect personal dimension files", e);
            return "Экспорт отменён: не удалось полностью прочитать персональный мир: " + e.getMessage();
        }

        List<Map<String, Object>> playerInfo = new ArrayList<>();
        for (ForgePlayer player : players) {
            UUID id = player.getId();
            String name = player.getName();
            addFile(worldDir, worldName, "playerdata/" + id + ".dat", files, missing);
            for (String ext : new String[] { ".thaum", ".thaumback", ".baub", ".baubback" }) {
                addFileIfExists(
                    worldDir,
                    worldName,
                    findCaseInsensitive(new File(worldDir, "playerdata"), name + ext),
                    files);
            }
            addFileIfExists(worldDir, worldName, "betterquesting/QuestProgress/" + id + ".json", files);
            if (move == null || !move.joining())
                addFileIfExists(worldDir, worldName, "gtnhteams/" + id + ".json", files);
            addFileIfExists(worldDir, worldName, "stats/" + id + ".json", files);
            addFileIfExists(worldDir, worldName, "serverutilities/players/" + name.toLowerCase() + ".dat", files);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", name);
            info.put("uuid", id.toString());
            MigrationData.describePlayer(id, name, info);
            playerInfo.add(info);
        }

        Map<String, Object> teamInfo = null;
        if (team != null) {
            File teamsDir = new File(worldDir, "serverutilities/teams");
            addFileIfExists(worldDir, worldName, "serverutilities/teams/" + team.getId() + ".dat", files);
            // Team extension data (claimed chunks and others) lives in teams/<extension>/<team>.dat
            File[] extDirs = teamsDir.listFiles(File::isDirectory);
            if (extDirs != null) {
                for (File extDir : extDirs) {
                    addFileIfExists(
                        worldDir,
                        worldName,
                        "serverutilities/teams/" + extDir.getName() + "/" + team.getId() + ".dat",
                        files);
                }
            }
            teamInfo = new LinkedHashMap<>();
            teamInfo.put("id", team.getId());
            teamInfo.put("uid", team.getUID());
            teamInfo.put(
                "title",
                team.getTitle()
                    .getUnformattedText());
            TeamsManager manager = TeamsManager.get();
            teamInfo.put("boundPersonalDimension", manager.getDim(team));
            teamInfo.put("personalPortalRewardClaimed", manager.isPDimRewardTeamClaimed(team));
            MigrationData.describeTeam(team, dims, teamInfo);
            if (move != null) teamInfo.put("claims", move.claims);
        }
        byte[] party = move != null && move.joining() ? null : MigrationData.partyOf(players);

        // 3. Copy to a staging folder now, while nothing can change (we are on the server thread).
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String baseName = sanitize(target.getName()) + "_" + stamp + "_" + UUID.randomUUID();
        File migrations = MigrationStorage.archives();
        File staging = new File(MigrationStorage.localDirectory(), ".staging-" + baseName);
        Map<File, Path> deferredPersonalFiles = new LinkedHashMap<>();
        long bytes = 0;
        try {
            for (Map.Entry<File, String> entry : files.entrySet()) {
                Path dest = staging.toPath()
                    .resolve(entry.getValue());
                if (move != null && (entry.getValue()
                    .startsWith(worldName + "/PERSONAL_DIM_")
                    || entry.getValue()
                        .startsWith(worldName + "/DIM_SPACESTATION")
                    || entry.getValue()
                        .startsWith(worldName + "/DIM_MOTHERSHIP"))) {
                    // The move already unloaded and locked these worlds. Large region files are immutable now.
                    deferredPersonalFiles.put(entry.getKey(), dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(
                        entry.getKey()
                            .toPath(),
                        dest,
                        StandardCopyOption.REPLACE_EXISTING);
                }
                bytes += entry.getKey()
                    .length();
            }
            if (party != null) {
                Path partyFile = staging.toPath()
                    .resolve(MigrationData.PARTY_FILE);
                Files.createDirectories(partyFile.getParent());
                Files.write(partyFile, party);
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            if (move != null) {
                manifest.put("moveId", move.id);
                if (move.joining()) {
                    manifest.put("joinTeam", move.joinTeam);
                    manifest.put("joinOwner", move.joinOwner);
                }
                List<int[]> external = new ArrayList<>();
                for (int[] c : move.claims) if (!dims.contains(c[0])) external.add(c);
                manifest.put("externalChunks", external);
                MigrationChunks.snapshot(staging, move);
                MigrationGlobalData.write(staging, move);
                MigrationGlobalData.retain(staging, move);
            }
            manifest.put("format", MigrationData.FORMAT);
            manifest.put("idMap", MigrationData.idMap());
            manifest.put("exportedAt", new Date().toString());
            manifest.put("sourceServer", CointConfig.api.serverTag);
            manifest.put("sourceNode", ClusterService.isRunning() ? ClusterService.nodeId() : "");
            manifest.put("worldFolder", worldName);
            manifest.put("team", teamInfo);
            manifest.put("players", playerInfo);
            manifest.put("personalDimensions", dimInfo);
            manifest.put("files", files.size());
            manifest.put("missing", missing);
            manifest.put(
                "notes",
                Arrays.asList(
                    "Import on the target server with /migrate import <file> (it may keep running).",
                    "The target world must be a copy of this one (same block/item ID map): the import checks it.",
                    move == null ? "Not included: anything outside the personal worlds, wireless EU, EnderStorage."
                        : "Move includes claimed external chunks, private EnderStorage, wireless EU and GT space projects. Public shared channels are not team-owned."));
            try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(new File(staging, "migration-manifest.json")),
                StandardCharsets.UTF_8)) {
                GSON.toJson(manifest, writer);
            }
        } catch (IOException e) {
            CointCore.LOG.error("[Migrate] Copy failed", e);
            deleteQuietly(staging);
            return "Ошибка копирования файлов: " + e.getMessage();
        }

        // 4. Compress in the background.
        running = true;
        exportSession = session;
        File zip = new File(migrations, baseName + ".zip");
        int fileCount = files.size();
        long totalBytes = bytes;
        int dimCount = dims.size();
        List<String> missingCopy = new ArrayList<>(missing);
        sender.addChatMessage(
            new ChatComponentText(
                "§eФайлов в снимке: §f" + fileCount
                    + "§e ("
                    + (totalBytes / (1024 * 1024))
                    + " МБ, персональных миров: "
                    + dimCount
                    + "). Упаковываю в архив..."));
        Thread thread = new Thread(() -> {
            String result;
            boolean complete = false;
            try {
                for (Map.Entry<File, Path> file : deferredPersonalFiles.entrySet()) {
                    session.check();
                    Files.createDirectories(
                        file.getValue()
                            .getParent());
                    Files.copy(
                        file.getKey()
                            .toPath(),
                        file.getValue());
                }
                zipDirectory(staging.toPath(), zip, session);
                complete = true;
                result = "§aАрхив готов: §f" + zip.getAbsolutePath()
                    + " §a("
                    + (zip.length() / (1024 * 1024))
                    + " МБ). /migrate ui";
                if (!missingCopy.isEmpty()) {
                    result += "\n§eНе найдены (пропущены): §f" + String.join(", ", missingCopy);
                }
                CointCore.LOG.info("[Migrate] Exported {} to {}", target.getName(), zip);
            } catch (IOException | RuntimeException e) {
                CointCore.LOG.error("[Migrate] Zip failed", e);
                result = "§cОшибка упаковки архива: " + e.getMessage();
            } finally {
                deleteQuietly(staging);
            }
            String message = result;
            boolean published = complete;
            synchronized (MigrationExporter.class) {
                if (exportSession == session && session.current()) {
                    MAIN_THREAD.add(() -> {
                        if (exportSession != session || !session.current()) return;
                        running = false;
                        exportSession = null;
                        worker = null;
                        if (!CommandMigrate.allowed(sender)) return;
                        sender.addChatMessage(new ChatComponentText(message));
                        if (published) ready.accept(zip.getName());
                    });
                } else if (exportSession == session) {
                    exportSession = null;
                    worker = null;
                    running = false;
                }
            }
        }, "CointCore migration export");
        thread.setDaemon(false);
        worker = thread;
        thread.start();
        return null;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Runnable task;
        while ((task = MAIN_THREAD.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException e) {
                CointCore.LOG.warn("[Migrate] Could not deliver message", e);
            }
        }
    }

    static Set<Integer> findTeamDimensions(ForgeTeam team) {
        Set<Integer> dims = new LinkedHashSet<>();
        if (team == null) return dims;
        int bound = TeamsManager.get()
            .getDim(team);
        if (bound != 0 && PersonalSpaceBinding.isPersonalDimension(bound)) {
            dims.add(bound);
        }
        if (ClaimedChunks.instance != null) {
            for (ClaimedChunk claimed : ClaimedChunks.instance.getTeamChunks(team, OptionalInt.empty(), true)) {
                int dimId = claimed.getPos().dim;
                if (PersonalSpaceBinding.isPersonalDimension(dimId)) {
                    dims.add(dimId);
                }
            }
        }
        return dims;
    }

    static void saveEverything(MinecraftServer server) throws MinecraftException, InterruptedException {
        server.getConfigurationManager()
            .saveAllPlayerData();
        for (WorldServer world : DimensionManager.getWorlds()) {
            if (world == null) continue;
            boolean saving = world.levelSaving;
            world.levelSaving = false;
            try {
                world.saveAllChunks(true, null);
            } finally {
                world.levelSaving = saving;
            }
        }
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
    }

    private static void addTree(File dir, String archivePrefix, Map<File, String> files) throws IOException {
        Files.walkFileTree(dir.toPath(), new java.nio.file.SimpleFileVisitor<Path>() {

            @Override
            public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attrs)
                throws IOException {
                if (!attrs.isRegularFile()) throw new IOException("Unsupported file in dimension: " + path);
                String rel = dir.toPath()
                    .relativize(path)
                    .toString()
                    .replace(File.separatorChar, '/');
                if (!rel.equals("session.lock")) files.put(path.toFile(), archivePrefix + "/" + rel);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void addFile(File worldDir, String worldName, String rel, Map<File, String> files,
        List<String> missing) {
        File file = new File(worldDir, rel);
        if (file.isFile()) {
            files.put(file, worldName + "/" + rel);
        } else {
            missing.add(worldName + "/" + rel);
        }
    }

    private static void addFileIfExists(File worldDir, String worldName, String rel, Map<File, String> files) {
        if (rel == null) return;
        File file = new File(worldDir, rel);
        if (file.isFile()) {
            files.put(file, worldName + "/" + rel);
        }
    }

    /** Thaumcraft and Baubles files are named after the player with its exact case. */
    private static String findCaseInsensitive(File dir, String fileName) {
        File[] matches = dir.listFiles((d, name) -> name.equalsIgnoreCase(fileName));
        return matches == null || matches.length == 0 ? null : "playerdata/" + matches[0].getName();
    }

    private static void zipDirectory(Path root, File zipFile, MigrationStorage.Session session) throws IOException {
        Files.createDirectories(
            zipFile.toPath()
                .getParent());
        File partial = new File(zipFile.getPath() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(partial)));
            Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                session.check();
                String name = root.relativize(path)
                    .toString()
                    .replace(File.separatorChar, '/');
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        synchronized (MigrationExporter.class) {
            session.check();
            if (exportSession != session) throw new IOException("Export cancelled");
            Files.move(partial.toPath(), zipFile.toPath());
        }
    }

    private static void deleteQuietly(File dir) {
        if (!dir.exists()) return;
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted((a, b) -> b.compareTo(a))
                .forEach(
                    path -> path.toFile()
                        .delete());
        } catch (IOException e) {
            CointCore.LOG.warn("[Migrate] Could not clean {}", dir, e);
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
