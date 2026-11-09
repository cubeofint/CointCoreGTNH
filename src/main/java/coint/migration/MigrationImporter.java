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

    private MigrationImporter() {}

    public static boolean isRunning() {
        return running;
    }

    /** The zips in migrations/ that can be imported. */
    public static List<String> available() {
        List<String> names = new ArrayList<>();
        File[] zips = migrationsDir().listFiles((d, n) -> n.endsWith(".zip"));
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
        return new File(serverDir(), "migrations");
    }

    /** Runs on the server thread. Null when the import started, else why not. */
    public static String start(ICommandSender sender, String fileName) {
        if (running || MigrationExporter.isRunning()) {
            return "Уже идёт перенос, дождитесь его окончания";
        }
        File zip = new File(migrationsDir(), new File(fileName).getName());
        if (!zip.isFile()) {
            return "Нет файла migrations/" + zip.getName();
        }
        File staging = new File(migrationsDir(), ".import-" + zip.getName());
        running = true;
        sender.addChatMessage(new ChatComponentText("§eРаспаковываю §f" + zip.getName() + "§e..."));
        Thread thread = new Thread(() -> {
            String error = null;
            try {
                deleteQuietly(staging);
                unzip(zip, staging);
            } catch (IOException e) {
                CointCore.LOG.error("[Migrate] Could not unpack {}", zip, e);
                error = "Не удалось распаковать архив: " + e.getMessage();
            }
            String failed = error;
            MAIN_THREAD.add(() -> attempt(sender, zip, staging, failed, 0));
        }, "CointCore migration import");
        thread.setDaemon(true);
        thread.start();
        return null;
    }

    /** One try at the import; a personal world still loaded here is unloaded and the import tried again shortly. */
    private static void attempt(ICommandSender sender, File zip, File staging, String failed, int tries) {
        boolean again = false;
        try {
            String result = failed != null ? failed : apply(sender, zip, staging);
            if (result == RETRY && tries < 10) {
                again = true;
                if (tries == 0) {
                    sender.addChatMessage(new ChatComponentText("§eПерсональный мир здесь загружен, выгружаю его..."));
                }
                later.add(new long[] { tick + 40 });
                laterTasks.add(() -> attempt(sender, zip, staging, null, tries + 1));
            } else if (result == RETRY) {
                sender.addChatMessage(
                    new ChatComponentText("§cИмпорт не выполнен: персональный мир не выгружается (чанклоадеры?)"));
            } else if (result != null) {
                sender.addChatMessage(new ChatComponentText("§cИмпорт не выполнен: " + result));
            }
        } catch (RuntimeException e) {
            CointCore.LOG.error("[Migrate] Import of {} failed", zip, e);
            sender.addChatMessage(new ChatComponentText("§cИмпорт прерван ошибкой: " + e + ". Подробности в логе."));
        } finally {
            if (!again) {
                deleteQuietly(staging);
                running = false;
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

    /** On the server thread, after unpacking. Null when done, else why nothing was changed. */
    private static String apply(ICommandSender sender, File zip, File staging) {
        Map<String, Object> manifest;
        try (Reader reader = new InputStreamReader(
            new FileInputStream(new File(staging, MANIFEST)),
            StandardCharsets.UTF_8)) {
            manifest = GSON.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
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
        File worldDir = DimensionManager.getCurrentSaveRootDirectory();
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
            if (d.get("missing") != null) {
                continue;
            }
            dimIds.add(dim);
            if (DimensionManager.getWorld(dim) != null) {
                if (!DimensionManager.getWorld(dim).playerEntities.isEmpty()) {
                    return "в измерении " + dim + " сейчас есть игроки";
                }
                DimensionManager.unloadWorld(dim);
                return RETRY;
            }
            Short owner = TeamsManager.get()
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
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        File replaced = new File(migrationsDir(), "replaced/" + stamp);
        int[] entry = team == null ? null : ints(team.get("entry"));
        try {
            for (Map<String, Object> d : dims) {
                if (d.get("missing") != null) {
                    continue;
                }
                String folder = str(d.get("folder"));
                move(new File(sourceRoot, folder), new File(worldDir, folder), new File(replaced, folder));
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
        MigrationPending.Entry pending = MigrationPending.Entry.from(manifest);
        String state = MigrationPending.add(pending);

        File done = new File(migrationsDir(), "imported/" + zip.getName());
        try {
            Files.createDirectories(
                done.getParentFile()
                    .toPath());
            Files.move(zip.toPath(), done.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            notes.add("§eархив не перемещён в migrations/imported: " + e.getMessage());
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
