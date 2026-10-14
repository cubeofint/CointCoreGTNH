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

    private MigrationExporter() {}

    public static boolean isRunning() {
        return running;
    }

    /** Runs on the server thread. Returns null on success, or the reason the export could not start. */
    public static String export(ICommandSender sender, ForgePlayer target) {
        if (running) {
            return "Уже идёт другой экспорт, дождитесь его окончания";
        }
        MinecraftServer server = MinecraftServer.getServer();
        ForgeTeam team = target.hasTeam() ? target.team : null;

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
        File worldDir = DimensionManager.getCurrentSaveRootDirectory();
        File serverDir = worldDir.getAbsoluteFile()
            .getParentFile();
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
        for (int dimId : dims) {
            DimensionConfig config = DimensionConfig.getForDimension(dimId, false);
            String folder = config == null ? "PERSONAL_DIM_" + dimId : config.getSaveDir(dimId);
            File dimDir = new File(worldDir, folder);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("dimension", dimId);
            info.put("folder", folder);
            if (dimDir.isDirectory()) {
                addTree(dimDir, worldName + "/" + folder, files);
            } else {
                missing.add(worldName + "/" + folder);
                info.put("missing", true);
            }
            dimInfo.add(info);
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
            addFileIfExists(worldDir, worldName, "gtnhteams/" + id + ".json", files);
            addFileIfExists(worldDir, worldName, "stats/" + id + ".json", files);
            addFileIfExists(worldDir, worldName, "serverutilities/players/" + name.toLowerCase() + ".dat", files);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", name);
            info.put("uuid", id.toString());
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
        }

        // 3. Copy to a staging folder now, while nothing can change (we are on the server thread).
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String baseName = sanitize(target.getName()) + "_" + stamp;
        File migrations = new File(serverDir, "migrations");
        File staging = new File(migrations, ".staging-" + baseName);
        long bytes = 0;
        try {
            for (Map.Entry<File, String> entry : files.entrySet()) {
                Path dest = staging.toPath()
                    .resolve(entry.getValue());
                Files.createDirectories(dest.getParent());
                Files.copy(
                    entry.getKey()
                        .toPath(),
                    dest,
                    StandardCopyOption.REPLACE_EXISTING);
                bytes += entry.getKey()
                    .length();
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("exportedAt", new Date().toString());
            manifest.put("sourceServer", CointConfig.api.serverTag);
            manifest.put("worldFolder", worldName);
            manifest.put("team", teamInfo);
            manifest.put("players", playerInfo);
            manifest.put("personalDimensions", dimInfo);
            manifest.put("files", files.size());
            manifest.put("missing", missing);
            manifest.put(
                "notes",
                Arrays.asList(
                    "Extract into the target server root (the folder that contains '" + worldName
                        + "'), with that server stopped.",
                    "If the target world folder has another name, move the files from '" + worldName + "/' into it.",
                    "The target world must be a copy of this one (same block/item ID map), otherwise blocks change.",
                    "Personal dimension numbers must be free on the target server.",
                    "Not included (shared world files): CointCore dimension binding / portal reward, BetterQuesting"
                        + " parties, wireless EU, Blood Magic soul network, EnderStorage."));
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
        File zip = new File(migrations, baseName + ".zip");
        int fileCount = files.size();
        long totalBytes = bytes;
        int dimCount = dims.size();
        List<String> missingCopy = new ArrayList<>(missing);
        sender.addChatMessage(
            new ChatComponentText(
                "§eСкопировано файлов: §f" + fileCount
                    + "§e ("
                    + (totalBytes / (1024 * 1024))
                    + " МБ, персональных миров: "
                    + dimCount
                    + "). Упаковываю в архив..."));
        Thread thread = new Thread(() -> {
            String result;
            try {
                zipDirectory(staging.toPath(), zip);
                result = "§aАрхив готов: §f" + serverDir.toPath()
                    .relativize(zip.toPath()) + " §a(" + (zip.length() / (1024 * 1024)) + " МБ)";
                if (!missingCopy.isEmpty()) {
                    result += "\n§eНе найдены (пропущены): §f" + String.join(", ", missingCopy);
                }
                CointCore.LOG.info("[Migrate] Exported {} to {}", target.getName(), zip);
            } catch (IOException e) {
                CointCore.LOG.error("[Migrate] Zip failed", e);
                result = "§cОшибка упаковки архива: " + e.getMessage();
            } finally {
                deleteQuietly(staging);
                running = false;
            }
            String message = result;
            MAIN_THREAD.add(() -> sender.addChatMessage(new ChatComponentText(message)));
        }, "CointCore migration export");
        thread.setDaemon(false);
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

    private static Set<Integer> findTeamDimensions(ForgeTeam team) {
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

    private static void saveEverything(MinecraftServer server) throws MinecraftException, InterruptedException {
        server.getConfigurationManager()
            .saveAllPlayerData();
        for (WorldServer world : DimensionManager.getWorlds()) {
            if (world == null) continue;
            boolean saving = world.levelSaving;
            world.levelSaving = false;
            world.saveAllChunks(true, null);
            world.levelSaving = saving;
        }
        ThreadedFileIOBase.threadedIOInstance.waitForFinish();
    }

    private static void addTree(File dir, String archivePrefix, Map<File, String> files) {
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.filter(Files::isRegularFile)
                .forEach(path -> {
                    String rel = dir.toPath()
                        .relativize(path)
                        .toString()
                        .replace(File.separatorChar, '/');
                    // session.lock belongs to the running server, never ship it
                    if (!rel.equals("session.lock")) {
                        files.put(path.toFile(), archivePrefix + "/" + rel);
                    }
                });
        } catch (IOException e) {
            CointCore.LOG.error("[Migrate] Could not list {}", dir, e);
        }
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

    private static void zipDirectory(Path root, File zipFile) throws IOException {
        Files.createDirectories(
            zipFile.toPath()
                .getParent());
        File partial = new File(zipFile.getPath() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(partial)));
            Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                String name = root.relativize(path)
                    .toString()
                    .replace(File.separatorChar, '/');
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        Files.move(partial.toPath(), zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
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
